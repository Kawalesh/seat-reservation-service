package com.paytm.reservation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ReservationMetrics {

    private final MeterRegistry meterRegistry;

    private final Counter confirmedCounter;
    private final Counter replayedCounter;

    private final ConcurrentHashMap<String, Counter> declinedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> availableSeatsGauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> confirmedSeatsGauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> heldSeatsGauges = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.confirmedCounter = Counter.builder("reservations_confirmed_total")
                .description("Total number of successfully confirmed seat reservations")
                .register(meterRegistry);

        this.replayedCounter = Counter.builder("reservations_replayed_total")
                .description("Total number of idempotent reservation replays")
                .register(meterRegistry);
    }

    public void incrementConfirmed(int seatsCount) {
        confirmedCounter.increment(seatsCount);
    }

    public void incrementDeclined(String reason) {
        declinedCounters.computeIfAbsent(reason, r ->
                Counter.builder("reservations_declined_total")
                        .description("Total number of declined reservations grouped by reason")
                        .tag("reason", r)
                        .register(meterRegistry)
        ).increment();
    }

    public void incrementReplayed() {
        replayedCounter.increment();
    }

    public void updateShowGauges(String showId, long available, long held, long confirmed) {
        availableSeatsGauges.computeIfAbsent(showId, id -> {
            AtomicLong val = new AtomicLong(available);
            meterRegistry.gauge("seats_available", io.micrometer.core.instrument.Tags.of("show_id", id), val);
            return val;
        }).set(available);

        heldSeatsGauges.computeIfAbsent(showId, id -> {
            AtomicLong val = new AtomicLong(held);
            meterRegistry.gauge("seats_held", io.micrometer.core.instrument.Tags.of("show_id", id), val);
            return val;
        }).set(held);

        confirmedSeatsGauges.computeIfAbsent(showId, id -> {
            AtomicLong val = new AtomicLong(confirmed);
            meterRegistry.gauge("seats_confirmed", io.micrometer.core.instrument.Tags.of("show_id", id), val);
            return val;
        }).set(confirmed);
    }
}
