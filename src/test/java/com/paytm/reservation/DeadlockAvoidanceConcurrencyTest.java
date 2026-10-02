package com.paytm.reservation;

import com.paytm.reservation.dto.CreateShowRequest;
import com.paytm.reservation.dto.ReserveSeatRequest;
import com.paytm.reservation.dto.ShowResponse;
import com.paytm.reservation.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
public class DeadlockAvoidanceConcurrencyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSeatRepository reservationSeatRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        idempotencyRecordRepository.deleteAll();
        reservationSeatRepository.deleteAll();
        reservationRepository.deleteAll();
        seatRepository.deleteAll();
        showRepository.deleteAll();
    }

    private HttpHeaders authHeaders(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(userId);
        return headers;
    }

    @Test
    @DisplayName("Deadlock avoidance: concurrent multi-seat requests with inverted order [A1,A2] vs [A2,A1] complete race-free without deadlocks")
    void testReverseOrderMultiSeatContention() throws InterruptedException {
        CreateShowRequest showReq = new CreateShowRequest("Deadlock-Test-Show", List.of("A1", "A2", "A3", "A4"), 30000L, 4);
        ResponseEntity<ShowResponse> showRes = restTemplate.postForEntity(baseUrl + "/shows", showReq, ShowResponse.class);
        assertThat(showRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String showId = showRes.getBody().getId();

        int threadCount = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger wins = new AtomicInteger(0);
        AtomicInteger cleanDeclines = new AtomicInteger(0);
        AtomicInteger serverErrors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            final String userId = "racer_" + i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    // Alternating reverse seat order to trigger deadlock in naive systems:
                    List<String> seatsWanted;
                    if (index % 3 == 0) {
                        seatsWanted = List.of("A1", "A2");
                    } else if (index % 3 == 1) {
                        seatsWanted = List.of("A2", "A1"); // inverted order
                    } else {
                        seatsWanted = List.of("A3", "A2", "A1"); // overlapping 3 seats
                    }

                    ReserveSeatRequest req = new ReserveSeatRequest(seatsWanted, UUID.randomUUID().toString());
                    HttpEntity<ReserveSeatRequest> entity = new HttpEntity<>(req, authHeaders(userId));

                    ResponseEntity<String> res = restTemplate.postForEntity(
                            baseUrl + "/shows/" + showId + "/reserve",
                            entity,
                            String.class
                    );

                    if (res.getStatusCode() == HttpStatus.CREATED) {
                        wins.incrementAndGet();
                    } else if (res.getStatusCode() == HttpStatus.CONFLICT) {
                        cleanDeclines.incrementAndGet();
                    } else if (res.getStatusCode().is5xxServerError()) {
                        serverErrors.incrementAndGet();
                    }
                } catch (Exception e) {
                    serverErrors.incrementAndGet();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // INVARIANT 1: Zero 5xx and zero deadlock exceptions
        assertThat(serverErrors.get()).isEqualTo(0);

        // INVARIANT 2: Exactly 1 winner gets the contested seats A1 & A2
        assertThat(wins.get()).isEqualTo(1);
        assertThat(cleanDeclines.get()).isEqualTo(threadCount - 1);

        // INVARIANT 3: Reconciliation holds
        ResponseEntity<ShowResponse> finalStateRes = restTemplate.getForEntity(baseUrl + "/shows/" + showId, ShowResponse.class);
        ShowResponse finalState = finalStateRes.getBody();
        assertThat(finalState).isNotNull();
        assertThat(finalState.getCounts().getAvailable() + finalState.getCounts().getHeld() + finalState.getCounts().getConfirmed())
                .isEqualTo(4);
    }
}
