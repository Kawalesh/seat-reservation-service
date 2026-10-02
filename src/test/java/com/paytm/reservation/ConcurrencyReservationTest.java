package com.paytm.reservation;

import com.paytm.reservation.domain.SeatStatus;
import com.paytm.reservation.dto.*;
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
public class ConcurrencyReservationTest {

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

    private ShowResponse createShow(String name, List<String> seats, long pricePaise, int perUserLimit) {
        CreateShowRequest request = new CreateShowRequest(name, seats, pricePaise, perUserLimit);
        ResponseEntity<ShowResponse> response = restTemplate.postForEntity(baseUrl + "/shows", request, ShowResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    @DisplayName("Hot-seat storm: 50 concurrent buyers fight for 1 seat -> exactly one 201, 49 declines (409), zero 5xx")
    void testHotSeatConcurrencyBurst() throws InterruptedException {
        int seatsCount = 10;
        List<String> seats = new ArrayList<>();
        for (int i = 1; i <= seatsCount; i++) {
            seats.add("A" + i);
        }
        ShowResponse show = createShow("Concert-HotSeat", seats, 25000L, 4);

        int concurrentBuyers = 50;
        String hotSeat = "A1";

        ExecutorService executor = Executors.newFixedThreadPool(concurrentBuyers);
        CountDownLatch readyLatch = new CountDownLatch(concurrentBuyers);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger status201Count = new AtomicInteger(0);
        AtomicInteger status409Count = new AtomicInteger(0);
        AtomicInteger status5xxCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentBuyers; i++) {
            final String userId = "buyer_" + i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Simultaneous stampede
                    ReserveSeatRequest req = new ReserveSeatRequest(List.of(hotSeat), UUID.randomUUID().toString());
                    HttpEntity<ReserveSeatRequest> entity = new HttpEntity<>(req, authHeaders(userId));

                    ResponseEntity<String> res = restTemplate.postForEntity(
                            baseUrl + "/shows/" + show.getId() + "/reserve",
                            entity,
                            String.class
                    );

                    if (res.getStatusCode() == HttpStatus.CREATED) {
                        status201Count.incrementAndGet();
                    } else if (res.getStatusCode() == HttpStatus.CONFLICT) {
                        status409Count.incrementAndGet();
                    } else if (res.getStatusCode().is5xxServerError()) {
                        status5xxCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    status5xxCount.incrementAndGet();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // INVARIANT 1: Exactly one winner
        assertThat(status201Count.get()).isEqualTo(1);
        // INVARIANT 2: 49 clean declines (409)
        assertThat(status409Count.get()).isEqualTo(concurrentBuyers - 1);
        // INVARIANT 3: Zero 5xx server errors
        assertThat(status5xxCount.get()).isEqualTo(0);

        // INVARIANT 4: Reconciliation holds
        ResponseEntity<ShowResponse> showStateRes = restTemplate.getForEntity(baseUrl + "/shows/" + show.getId(), ShowResponse.class);
        ShowResponse updatedShow = showStateRes.getBody();
        assertThat(updatedShow).isNotNull();
        assertThat(updatedShow.getCounts().getAvailable() + updatedShow.getCounts().getHeld() + updatedShow.getCounts().getConfirmed())
                .isEqualTo(show.getTotalSeats());
        assertThat(updatedShow.getCounts().getConfirmed()).isEqualTo(1);
        assertThat(updatedShow.getCounts().getAvailable()).isEqualTo(seatsCount - 1);
    }

    @Test
    @DisplayName("Per-user limit under concurrency: same user fires 10 parallel single-seat reservations on limit=4 show -> at most 4 confirmed")
    void testPerUserLimitConcurrency() throws InterruptedException {
        List<String> seats = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            seats.add("B" + i);
        }
        ShowResponse show = createShow("Limit-Show", seats, 10000L, 4);

        int parallelAttempts = 10;
        String userId = "greedy_user";

        ExecutorService executor = Executors.newFixedThreadPool(parallelAttempts);
        CountDownLatch readyLatch = new CountDownLatch(parallelAttempts);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger confirmedCount = new AtomicInteger(0);
        AtomicInteger declinedCount = new AtomicInteger(0);
        AtomicInteger serverErrors = new AtomicInteger(0);

        for (int i = 1; i <= parallelAttempts; i++) {
            final String seatToBook = "B" + i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    ReserveSeatRequest req = new ReserveSeatRequest(List.of(seatToBook), UUID.randomUUID().toString());
                    HttpEntity<ReserveSeatRequest> entity = new HttpEntity<>(req, authHeaders(userId));

                    ResponseEntity<String> res = restTemplate.postForEntity(
                            baseUrl + "/shows/" + show.getId() + "/reserve",
                            entity,
                            String.class
                    );

                    if (res.getStatusCode() == HttpStatus.CREATED) {
                        confirmedCount.incrementAndGet();
                    } else if (res.getStatusCode() == HttpStatus.CONFLICT) {
                        declinedCount.incrementAndGet();
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
        executor.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(serverErrors.get()).isEqualTo(0);
        assertThat(confirmedCount.get()).isLessThanOrEqualTo(4);
        assertThat(confirmedCount.get() + declinedCount.get()).isEqualTo(parallelAttempts);

        // Check show reconciliation
        ResponseEntity<ShowResponse> showRes = restTemplate.getForEntity(baseUrl + "/shows/" + show.getId(), ShowResponse.class);
        ShowResponse updated = showRes.getBody();
        assertThat(updated).isNotNull();
        assertThat(updated.getCounts().getConfirmed()).isEqualTo(confirmedCount.get());
        assertThat(updated.getCounts().getAvailable() + updated.getCounts().getHeld() + updated.getCounts().getConfirmed())
                .isEqualTo(show.getTotalSeats());
    }

    @Test
    @DisplayName("Idempotency exact-once execution: concurrent retries with same key return identical reservation, different body returns 409")
    void testIdempotencyExactOnceAndMismatch() throws InterruptedException {
        ShowResponse show = createShow("Idempotent-Show", List.of("C1", "C2", "C3"), 50000L, 4);

        String idempotencyKey = "key-" + UUID.randomUUID();
        String userId = "idempotent_user";
        int retries = 10;

        ExecutorService executor = Executors.newFixedThreadPool(retries);
        CountDownLatch readyLatch = new CountDownLatch(retries);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<String> reservationIds = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < retries; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    ReserveSeatRequest req = new ReserveSeatRequest(List.of("C1"), idempotencyKey);
                    HttpEntity<ReserveSeatRequest> entity = new HttpEntity<>(req, authHeaders(userId));

                    ResponseEntity<ReservationResponse> res = restTemplate.postForEntity(
                            baseUrl + "/shows/" + show.getId() + "/reserve",
                            entity,
                            ReservationResponse.class
                    );

                    if (res.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                        reservationIds.add(res.getBody().getReservationId());
                    }
                } catch (Exception ignored) {
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(retries);
        // All retries must return the exact same reservation ID
        Set<String> distinctIds = new HashSet<>(reservationIds);
        assertThat(distinctIds).hasSize(1);

        // Same key, different body -> 409 Conflict
        ReserveSeatRequest differentReq = new ReserveSeatRequest(List.of("C2"), idempotencyKey);
        HttpEntity<ReserveSeatRequest> diffEntity = new HttpEntity<>(differentReq, authHeaders(userId));
        ResponseEntity<String> conflictRes = restTemplate.postForEntity(
                baseUrl + "/shows/" + show.getId() + "/reserve",
                diffEntity,
                String.class
        );
        assertThat(conflictRes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictRes.getBody()).contains("IDEMPOTENCY_PAYLOAD_MISMATCH");
    }

    @Test
    @DisplayName("Cancellation and Re-booking: only owner may cancel, released seat becomes immediately re-bookable")
    void testCancellationAndRebooking() {
        ShowResponse show = createShow("Cancel-Show", List.of("D1"), 15000L, 4);

        // User A reserves D1
        ReserveSeatRequest reqA = new ReserveSeatRequest(List.of("D1"), "key-a");
        ResponseEntity<ReservationResponse> resA = restTemplate.postForEntity(
                baseUrl + "/shows/" + show.getId() + "/reserve",
                new HttpEntity<>(reqA, authHeaders("user_A")),
                ReservationResponse.class
        );
        assertThat(resA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String reservationId = resA.getBody().getReservationId();

        // User B attempts to cancel User A's reservation -> 403 Forbidden
        ResponseEntity<String> imposterCancel = restTemplate.postForEntity(
                baseUrl + "/reservations/" + reservationId + "/cancel",
                new HttpEntity<>(null, authHeaders("user_B")),
                String.class
        );
        assertThat(imposterCancel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // User A cancels their own reservation -> 200 OK
        ResponseEntity<String> ownerCancel = restTemplate.postForEntity(
                baseUrl + "/reservations/" + reservationId + "/cancel",
                new HttpEntity<>(null, authHeaders("user_A")),
                String.class
        );
        assertThat(ownerCancel.getStatusCode()).isEqualTo(HttpStatus.OK);

        // User B can now cleanly reserve D1 -> 201 Created
        ReserveSeatRequest reqB = new ReserveSeatRequest(List.of("D1"), "key-b");
        ResponseEntity<ReservationResponse> resB = restTemplate.postForEntity(
                baseUrl + "/shows/" + show.getId() + "/reserve",
                new HttpEntity<>(reqB, authHeaders("user_B")),
                ReservationResponse.class
        );
        assertThat(resB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resB.getBody().getUserId()).isEqualTo("user_B");
    }
}
