package com.example.load.metrics;

import com.codahale.metrics.MetricRegistry;

/**
 * Standalone analog of the original project's GrrMetrics.
 *
 * The original used Twilio's tagged meters shipped to DataDog. Plain Dropwizard metrics have no
 * tags, so we encode the "tags" into the metric name instead (e.g.
 * {@code requests.getItem.status.404}). A ConsoleReporter (configured in BasicTest) prints these
 * counts and the request-duration timers to the terminal, which is our local stand-in for the
 * DataDog dashboard.
 */
public class LoadMetrics {

    private final MetricRegistry registry;

    public LoadMetrics(final MetricRegistry registry) {
        this.registry = registry;
    }

    // ---- request lifecycle (one HTTP call) -------------------------------------------------

    public void markRequestStart(final String endpoint) {
        registry.meter(name("requests", endpoint, "start")).mark();
    }

    public void markRequestFinished(final String endpoint, final int statusCode) {
        registry.meter(name("requests", endpoint, "status", String.valueOf(statusCode))).mark();
    }

    public void markRequestSuccess(final String endpoint) {
        registry.meter(name("requests", endpoint, "success")).mark();
    }

    public void markRequestFailed(final String endpoint) {
        registry.meter(name("requests", endpoint, "failed")).mark();
    }

    /** Records the intent of a request (e.g. "Regular" vs "404"), like the original's markRequestGoal. */
    public void markRequestGoal(final String goal) {
        registry.meter(name("requests", "goal", goal)).mark();
    }

    // ---- test/scenario lifecycle (one full user flow) --------------------------------------

    public void markTestStart(final String test) {
        registry.meter(name("tests", test, "start")).mark();
    }

    public void markTestSuccess(final String test) {
        registry.meter(name("tests", test, "success")).mark();
    }

    public void markTestFailed(final String test) {
        registry.meter(name("tests", test, "failed")).mark();
    }

    // ---- data setup / teardown (the "DB" population phase) ---------------------------------

    public void markDataSuccess(final String endpoint) {
        registry.meter(name("data", endpoint, "success")).mark();
    }

    public void markDataFailed(final String endpoint) {
        registry.meter(name("data", endpoint, "failed")).mark();
    }

    private static String name(final String... parts) {
        return MetricRegistry.name("load", parts);
    }
}
