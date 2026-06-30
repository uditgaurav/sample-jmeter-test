package com.example.load;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.example.load.metrics.LoadMetrics;
import org.apache.jmeter.protocol.java.sampler.JUnitSampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared infrastructure for the load test, mirroring the original project's BasicTest:
 * the target URL, the worker thread pools, the "run this step a bit later" executor, the metric
 * registry + reporter, and the helpers that read parameters out of the JMeter thread context.
 *
 * Everything is designed to run in two modes:
 * <ol>
 *   <li><b>Under JMeter</b> – JMeter's JUnitSampler invokes the @Test methods and the parameter
 *       helpers read JMeter variables.</li>
 *   <li><b>Standalone</b> – {@code mvn exec:java} (or {@code java -jar fat-...jar}) runs
 *       {@code LoadTest.main}; the same helpers fall back to {@code -D} system properties and
 *       defaults, so no JMeter is required.</li>
 * </ol>
 */
public class BasicTest {

    // ---- parameter names (kept in one place, like the original) ----------------------------
    public static final String P_TARGET_BASE_URL = "TARGET_BASE_URL";
    public static final String P_START_EMBEDDED = "START_EMBEDDED_SERVER";
    public static final String P_EMBEDDED_PORT = "EMBEDDED_PORT";
    public static final String P_THREAD_NUMBER = "THREAD_NUMBER";
    public static final String P_DURATION_SECONDS = "DURATION_SECONDS";
    public static final String P_RESOURCE_COUNT = "RESOURCE_COUNT";
    public static final String P_PERCENTAGE_404 = "PERCENTAGE_404";
    public static final String P_SCENARIO = "SCENARIO";
    public static final String P_STEP_DELAY_MS = "STEP_DELAY_MS";

    private static final Logger log = LoggerFactory.getLogger(BasicTest.class);

    // Worker pools that actually run the async request chains (JMeter only triggers them).
    protected static final ExecutorService fixedThreadsExecutor = Executors.newFixedThreadPool(200);
    protected static final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(50);

    // Small shared scheduler used to delay chained steps without holding a worker thread.
    private static final ScheduledExecutorService SCHEDULER = new ScheduledThreadPoolExecutor(2);

    // Reassigned in LoadTest.init() once STEP_DELAY_MS is known. Spaces out steps within a flow,
    // exactly like the original's "afterThreeSecs" executor.
    protected static volatile Executor afterDelay =
            delayedExecutor(500, TimeUnit.MILLISECONDS, fixedThreadsExecutor);

    // Metrics: the local stand-in for the internal metrics + DataDog reporter.
    protected static final MetricRegistry metricsRegistry = new MetricRegistry();
    protected final LoadMetrics metrics = new LoadMetrics(metricsRegistry);
    private static volatile ConsoleReporter consoleReporter;

    // Fallback counters for standalone mode, where there is no JMeter iteration/thread context.
    private static final AtomicInteger fallbackIteration = new AtomicInteger(0);

    public BasicTest() {
    }

    // JMeter's JUnitSampler can call a one-String constructor; kept for parity with the original.
    public BasicTest(final String label) {
        this();
    }

    protected static Executor delayedExecutor(final long delay, final TimeUnit unit, final Executor executor) {
        return r -> SCHEDULER.schedule(() -> executor.execute(r), delay, unit);
    }

    // ---- parameter resolution --------------------------------------------------------------

    /**
     * Resolve a parameter from (in priority order): a {@code -D} system property, the current
     * JMeter variable, then the supplied default. The JMeter lookup is wrapped in a broad
     * try/catch so it is a no-op (and the class still loads) when JMeter is absent.
     */
    protected static String param(final String name, final String def) {
        final String sys = System.getProperty(name);
        if (sys != null && !sys.isEmpty()) {
            return sys;
        }
        try {
            final JUnitSampler sampler = new JUnitSampler();
            if (sampler.getThreadContext() != null && sampler.getThreadContext().getVariables() != null) {
                final String v = sampler.getThreadContext().getVariables().get(name);
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
        } catch (Throwable ignore) {
            // No JMeter context (standalone run) – fall through to default.
        }
        return def;
    }

    protected static int intParam(final String name, final int def) {
        try {
            return Integer.parseInt(param(name, Integer.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    protected static long longParam(final String name, final long def) {
        try {
            return Long.parseLong(param(name, Long.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    protected static boolean boolParam(final String name, final boolean def) {
        return Boolean.parseBoolean(param(name, Boolean.toString(def)).trim());
    }

    // ---- JMeter context accessors (guarded for standalone mode) -----------------------------

    public int getIteration() {
        try {
            final JUnitSampler sampler = new JUnitSampler();
            if (sampler.getThreadContext() != null && sampler.getThreadContext().getVariables() != null) {
                return sampler.getThreadContext().getVariables().getIteration();
            }
        } catch (Throwable ignore) {
            // standalone
        }
        return fallbackIteration.getAndIncrement();
    }

    protected int getThread() {
        try {
            final JUnitSampler sampler = new JUnitSampler();
            if (sampler.getThreadContext() != null && sampler.getThreadContext().getThread() != null) {
                return sampler.getThreadContext().getThread().getThreadNum();
            }
        } catch (Throwable ignore) {
            // standalone
        }
        return 0;
    }

    // ---- reporting --------------------------------------------------------------------------

    protected void startReporter() {
        log.info("Starting console metrics reporter (every 10s).");
        consoleReporter = ConsoleReporter.forRegistry(metricsRegistry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
        consoleReporter.start(10, TimeUnit.SECONDS);
    }

    protected void stopReporterWithFinalReport() {
        if (consoleReporter != null) {
            consoleReporter.report();
            consoleReporter.stop();
        }
    }

    /** Gracefully drains and shuts the shared pools so a standalone JVM can exit cleanly. */
    protected static void shutdownPools() {
        final ExecutorService[] pools = {fixedThreadsExecutor, scheduledExecutor, SCHEDULER};
        for (ExecutorService pool : pools) {
            pool.shutdown();
        }
        try {
            for (ExecutorService pool : pools) {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Pool did not terminate within 30s");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
