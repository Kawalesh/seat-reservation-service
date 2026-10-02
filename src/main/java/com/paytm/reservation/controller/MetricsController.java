package com.paytm.reservation.controller;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {

    private final ObjectProvider<PrometheusMeterRegistry> prometheusMeterRegistryProvider;

    public MetricsController(ObjectProvider<PrometheusMeterRegistry> prometheusMeterRegistryProvider) {
        this.prometheusMeterRegistryProvider = prometheusMeterRegistryProvider;
    }

    /**
     * Exposes Prometheus scrape format directly at /metrics in addition to /actuator/prometheus
     */
    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public String scrapeMetrics() {
        PrometheusMeterRegistry registry = prometheusMeterRegistryProvider.getIfAvailable();
        if (registry != null) {
            return registry.scrape();
        }
        return "# Prometheus metrics registry not active\n";
    }
}
