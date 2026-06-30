package com.example.load;

import com.example.load.client.ClientRequests;
import com.example.load.mock.MockServer;
import com.example.load.util.ItemIdTracker;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The class JMeter drives, mirroring the original GRRLoadTest.
 *
 * Each {@code @Test} method is one JMeter step (wired up in the .jmx via a JUnitSampler):
 * <ul>
 *   <li>{@link #init()} – setup thread group: read params, start the (optional) embedded server,
 *       start the metrics reporter, build the HTTP client.</li>
 *   <li>{@link #setUpData()} – "create the DB": POST baseline items so reads have something to hit.</li>
 *   <li>{@link #crudScenario()} / {@link #readScenario()} – the actual load steps, repeated by
 *       JMeter's threads. Like the original, they <b>fire-and-forget</b>: they submit an async
 *       request chain and return immediately, so JMeter measures only the trigger while the real
 *       latency is captured by the metric timers.</li>
 *   <li>{@link #teardown()} – post thread group: drain pools, print the final metrics report.</li>
 * </ul>
 *
 * It also has a {@link #main(String[])} so the whole thing can run without JMeter at all.
 */
public class LoadTest extends BasicTest {

    public static final String CRUD_TEST = "CrudFlow";
    public static final String READ_TEST = "ReadFlow";

    private static final Logger log = LoggerFactory.getLogger(LoadTest.class);
    private static final int REQUEST_404 = 0;
    private static final int REQUEST_REGULAR = 1;

    private static ClientRequests requests;
    private static MockServer mockServer;
    private static ItemIdTracker readItems;
    private static double[] requestTypeChances;
    private static String scenario = "All";

    public LoadTest() {
        super();
    }

    // Required by JMeter's JUnitSampler one-String constructor form.
    public LoadTest(final String label) {
        super(label);
    }

    // ---- JMeter step 1: setup ---------------------------------------------------------------

    @Test
    public void init() {
        final long start = System.currentTimeMillis();
        scenario = param(P_SCENARIO, "All");
        final boolean startEmbedded = boolParam(P_START_EMBEDDED, true);
        final int port = intParam(P_EMBEDDED_PORT, 18150);
        final long stepDelayMs = longParam(P_STEP_DELAY_MS, 500);
        afterDelay = delayedExecutor(stepDelayMs, TimeUnit.MILLISECONDS, fixedThreadsExecutor);

        startReporter();

        final String baseUrl;
        if (startEmbedded) {
            mockServer = new MockServer(port);
            try {
                mockServer.start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start embedded server on port " + port, e);
            }
            baseUrl = "http://localhost:" + port;
        } else {
            baseUrl = param(P_TARGET_BASE_URL, "http://localhost:" + port);
        }

        requests = new ClientRequests(baseUrl);
        initRequestChances();

        log.info("init() done in {} ms | scenario={} target={} embedded={} stepDelay={}ms",
                System.currentTimeMillis() - start, scenario, baseUrl, startEmbedded, stepDelayMs);
    }

    // ---- JMeter step 2: create baseline data ------------------------------------------------

    @Test
    public void setUpData() {
        if (requests == null) {
            log.warn("setUpData called before init(); skipping");
            return;
        }
        final int count = intParam(P_RESOURCE_COUNT, 200);
        log.info("Creating {} baseline items via the API...", count);

        final List<CompletableFuture<Long>> creations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            creations.add(requests.createItemAsync("seed-" + i));
        }

        readItems = new ItemIdTracker();
        int created = 0;
        for (CompletableFuture<Long> f : creations) {
            try {
                readItems.add(String.valueOf(f.join()));
                metrics.markDataSuccess(ClientRequests.CREATE_ITEM);
                created++;
            } catch (Throwable t) {
                metrics.markDataFailed(ClientRequests.CREATE_ITEM);
                log.error("Failed to create a baseline item", t);
            }
        }
        readItems.startIterators();
        log.info("Baseline data ready: {} of {} items created", created, count);
    }

    // ---- JMeter step 3: the load steps ------------------------------------------------------

    @Test
    public void crudScenario() {
        final int iteration = getIteration();
        try {
            fixedThreadsExecutor.submit(() -> crudRequests(iteration));
        } catch (final Exception e) {
            log.error("Failed to submit CRUD scenario (iteration {})", iteration, e);
            throw e;
        }
    }

    @Test
    public void readScenario() {
        final int iteration = getIteration();
        try {
            fixedThreadsExecutor.submit(() -> readRequest(iteration));
        } catch (final Exception e) {
            log.error("Failed to submit read scenario (iteration {})", iteration, e);
            throw e;
        }
    }

    // ---- JMeter step 4: teardown ------------------------------------------------------------

    @Test
    public void teardown() {
        log.info("Teardown: draining in-flight requests...");
        shutdownPools();
        if (requests != null) {
            requests.close();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
        stopReporterWithFinalReport();
        log.info("Teardown complete.");
    }

    // ---- scenario implementations -----------------------------------------------------------

    /** Full CRUD flow: create -> get -> list -> update -> get -> delete, spaced by afterDelay. */
    private void crudRequests(final int iteration) {
        metrics.markTestStart(CRUD_TEST);
        final String name = "item-" + iteration;
        requests.createItemAsync(name)
                .thenComposeAsync(id -> requests.getItemAsync(id).thenApply(r -> id), afterDelay)
                .thenComposeAsync(id -> requests.listItemsAsync().thenApply(r -> id), afterDelay)
                .thenComposeAsync(id -> requests.updateItemAsync(id, name + "-upd").thenApply(r -> id), afterDelay)
                .thenComposeAsync(id -> requests.getItemAsync(id).thenApply(r -> id), afterDelay)
                .thenComposeAsync(id -> requests.deleteItemAsync(id).thenApply(r -> id), afterDelay)
                .thenAccept(id -> metrics.markTestSuccess(CRUD_TEST))
                .exceptionally(ex -> {
                    metrics.markTestFailed(CRUD_TEST);
                    log.error("CRUD flow failed: {}", ex.getMessage());
                    return null;
                });
    }

    /** Read flow: GET an existing id (expect 200) or a never-created id (expect 404). */
    private void readRequest(final int iteration) {
        metrics.markTestStart(READ_TEST);
        final long id = pickReadId();
        requests.readItemAsync(id)
                .thenAccept(r -> metrics.markTestSuccess(READ_TEST))
                .exceptionally(ex -> {
                    metrics.markTestFailed(READ_TEST);
                    log.error("Read flow failed: {}", ex.getMessage());
                    return null;
                });
    }

    /** Picks an id biased by PERCENTAGE_404, exactly like the original's getResource(). */
    private long pickReadId() {
        if (readItems == null || readItems.getSize() == 0) {
            metrics.markRequestGoal("404");
            return 999_999L;
        }
        int event = Arrays.binarySearch(requestTypeChances, Math.random());
        if (event < 0) {
            event = -event - 1;
        }
        if (event == REQUEST_404) {
            metrics.markRequestGoal("404");
            return readItems.getUniqueUnusedItem();
        }
        metrics.markRequestGoal("Regular");
        return Long.parseLong(readItems.getNextInLoop());
    }

    private void initRequestChances() {
        int percentage404 = intParam(P_PERCENTAGE_404, 25);
        if (percentage404 < 0 || percentage404 > 99) {
            log.warn("PERCENTAGE_404={} out of range, using 25", percentage404);
            percentage404 = 25;
        }
        final double chance404 = percentage404 / 100.0;
        requestTypeChances = new double[]{chance404, 1.0};
    }

    private static boolean wantsCrud(final String sc) {
        return "All".equalsIgnoreCase(sc) || "Crud".equalsIgnoreCase(sc);
    }

    private static boolean wantsRead(final String sc) {
        return "All".equalsIgnoreCase(sc) || "Read".equalsIgnoreCase(sc);
    }

    // ---- standalone runner (no JMeter required) ---------------------------------------------

    public static void main(final String[] args) throws Exception {
        final LoadTest test = new LoadTest();
        test.init();
        test.setUpData();

        final int threads = intParam(P_THREAD_NUMBER, 10);
        final int durationSec = intParam("STANDALONE_RUN_SECONDS", intParam(P_DURATION_SECONDS, 30));
        final String sc = scenario;
        final long end = System.currentTimeMillis() + durationSec * 1000L;
        log.info("Standalone run: {} driver threads, {}s, scenario={}", threads, durationSec, sc);

        final ExecutorService driver = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            driver.submit(() -> {
                while (System.currentTimeMillis() < end) {
                    if (wantsCrud(sc)) {
                        test.crudScenario();
                    }
                    if (wantsRead(sc)) {
                        test.readScenario();
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }
        driver.shutdown();
        driver.awaitTermination(durationSec + 30L, TimeUnit.SECONDS);

        Thread.sleep(2000); // let the last async chains drain
        test.teardown();
        log.info("Standalone run finished.");
    }
}
