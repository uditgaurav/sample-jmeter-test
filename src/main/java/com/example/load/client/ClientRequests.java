package com.example.load.client;

import com.codahale.metrics.Timer;
import com.example.load.BasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps the async HTTP client and turns every endpoint into a metric-instrumented
 * {@code CompletableFuture}, exactly like the original GRRClientRequests. Each call:
 * marks the request start, times it, fires it asynchronously, then on completion records the
 * status code and a success/failure verdict.
 *
 * Extends {@link BasicTest} purely to reuse the shared metrics, timers and thread pool (the same
 * trick the original used).
 */
public class ClientRequests extends BasicTest {

    public static final String CREATE_ITEM = "createItem";
    public static final String GET_ITEM = "getItem";
    public static final String LIST_ITEMS = "listItems";
    public static final String UPDATE_ITEM = "updateItem";
    public static final String DELETE_ITEM = "deleteItem";
    public static final String READ_ITEM = "readItem";

    private static final Logger log = LoggerFactory.getLogger(ClientRequests.class);
    private static final Pattern ID_IN_BODY = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private final String baseUrl;
    private final AsyncHttpClient httpClient;

    public ClientRequests(final String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        final DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setRequestTimeout(50_000)
                .setConnectTimeout(5_000)
                .setMaxConnections(1_000)
                .build();
        this.httpClient = new DefaultAsyncHttpClient(config);
        log.info("HTTP client initialised against {}", this.baseUrl);
    }

    public void close() {
        try {
            httpClient.close();
        } catch (Exception e) {
            log.warn("Error closing HTTP client", e);
        }
    }

    // ---- write endpoints -------------------------------------------------------------------

    /** POST /items -> resolves to the new item id. */
    public CompletableFuture<Long> createItemAsync(final String name) {
        return instrument(CREATE_ITEM, 200, 299,
                () -> httpClient.preparePost(baseUrl + "/items")
                        .addHeader("Content-Type", "application/json")
                        .setBody("{\"name\":\"" + name + "\"}")
                        .execute().toCompletableFuture(),
                ClientRequests::parseId);
    }

    /** PUT /items/{id} */
    public CompletableFuture<Response> updateItemAsync(final long id, final String name) {
        return instrument(UPDATE_ITEM, 200, 299,
                () -> httpClient.preparePut(baseUrl + "/items/" + id)
                        .addHeader("Content-Type", "application/json")
                        .setBody("{\"name\":\"" + name + "\"}")
                        .execute().toCompletableFuture(),
                Function.identity());
    }

    /** DELETE /items/{id} */
    public CompletableFuture<Response> deleteItemAsync(final long id) {
        return instrument(DELETE_ITEM, 200, 299,
                () -> httpClient.prepareDelete(baseUrl + "/items/" + id)
                        .execute().toCompletableFuture(),
                Function.identity());
    }

    // ---- read endpoints --------------------------------------------------------------------

    /** GET /items/{id} expecting the item to exist (200). */
    public CompletableFuture<Response> getItemAsync(final long id) {
        return instrument(GET_ITEM, 200, 299,
                () -> httpClient.prepareGet(baseUrl + "/items/" + id)
                        .execute().toCompletableFuture(),
                Function.identity());
    }

    /** GET /items (collection summary). */
    public CompletableFuture<Response> listItemsAsync() {
        return instrument(LIST_ITEMS, 200, 299,
                () -> httpClient.prepareGet(baseUrl + "/items")
                        .execute().toCompletableFuture(),
                Function.identity());
    }

    /**
     * GET /items/{id} where a 404 is an acceptable, expected outcome (the load test deliberately
     * asks for some never-created ids). Mirrors the original CurrentRoutes 200-or-404 logic.
     */
    public CompletableFuture<Response> readItemAsync(final long id) {
        return instrument(READ_ITEM, 200, 404,
                () -> httpClient.prepareGet(baseUrl + "/items/" + id)
                        .execute().toCompletableFuture(),
                Function.identity());
    }

    // ---- shared instrumentation ------------------------------------------------------------

    /**
     * Single place that wires metrics + timing around every call, so each endpoint reads the
     * same way and we can never forget to stop a timer:
     * <ol>
     *   <li>mark request start + start the duration timer</li>
     *   <li>fire the call</li>
     *   <li>on the response: record the status code, validate it, mark success, map the result</li>
     *   <li>always stop the timer; on any failure mark it failed</li>
     * </ol>
     */
    private <T> CompletableFuture<T> instrument(final String endpoint, final int minOk, final int maxOk,
                                                final Supplier<CompletableFuture<Response>> caller,
                                                final Function<Response, T> mapper) {
        metrics.markRequestStart(endpoint);
        final Timer.Context ctx = metricsRegistry.timer("load.request.duration." + endpoint).time();
        return caller.get()
                .thenApplyAsync(response -> {
                    final int status = response.getStatusCode();
                    metrics.markRequestFinished(endpoint, status);
                    if (status < minOk || status > maxOk) {
                        throw new RuntimeException(endpoint + " returned unexpected status " + status);
                    }
                    metrics.markRequestSuccess(endpoint);
                    return mapper.apply(response);
                }, fixedThreadsExecutor)
                .whenCompleteAsync((result, ex) -> {
                    ctx.stop();
                    if (ex != null) {
                        metrics.markRequestFailed(endpoint);
                        log.error("{} failed: {}", endpoint, ex.getMessage());
                    }
                }, fixedThreadsExecutor);
    }

    private static long parseId(final Response response) {
        final Matcher m = ID_IN_BODY.matcher(response.getResponseBody());
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        throw new RuntimeException("No id in create response: " + response.getResponseBody());
    }
}
