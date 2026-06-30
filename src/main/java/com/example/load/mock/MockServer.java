package com.example.load.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny in-JVM REST server used as the load-test target, so the whole thing is reproducible with
 * no external services. It exposes a CRUD resource under /items:
 *
 * <pre>
 *   POST   /items          -&gt; 201  {"id":N,"name":"..."}
 *   GET    /items          -&gt; 200  [ ... ]
 *   GET    /items/{id}     -&gt; 200 if present, else 404
 *   PUT    /items/{id}     -&gt; 200 if present, else 404
 *   DELETE /items/{id}     -&gt; 200 if present, else 404
 *   GET    /health         -&gt; 200
 * </pre>
 *
 * This mirrors how the original test hit a co-located service on localhost. To load test a real
 * service instead, set START_EMBEDDED_SERVER=false and point TARGET_BASE_URL at it.
 */
public class MockServer {

    private static final Logger log = LoggerFactory.getLogger(MockServer.class);
    private static final Pattern ITEM_ID = Pattern.compile("^/items/(\\d+)$");

    private final int port;
    private final Map<Long, String> store = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);
    private HttpServer server;
    private ExecutorService serverExecutor;

    public MockServer(final int port) {
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/items", this::handleItems);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        // Generous (daemon) pool so the mock never becomes the bottleneck for the load generator.
        // Daemon threads + explicit shutdown in stop() ensure the JVM can always exit cleanly.
        serverExecutor = Executors.newFixedThreadPool(100, r -> {
            final Thread t = new Thread(r, "mock-server");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(serverExecutor);
        server.start();
        log.info("Mock server listening on http://localhost:{}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            // HttpServer.stop() does NOT shut down a user-supplied executor, so do it ourselves.
            if (serverExecutor != null) {
                serverExecutor.shutdownNow();
                try {
                    serverExecutor.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("Mock server stopped (held {} items)", store.size());
        }
    }

    private void handleItems(final HttpExchange exchange) throws IOException {
        try {
            final String method = exchange.getRequestMethod();
            final String path = exchange.getRequestURI().getPath();
            final Matcher m = ITEM_ID.matcher(path);
            final boolean collection = "/items".equals(path);

            if (collection && "POST".equals(method)) {
                final String body = readBody(exchange);
                final long id = idSeq.incrementAndGet();
                store.put(id, body);
                respond(exchange, 201, "{\"id\":" + id + ",\"name\":" + nameOrNull(body) + "}");
            } else if (collection && "GET".equals(method)) {
                respond(exchange, 200, "{\"count\":" + store.size() + "}");
            } else if (m.matches()) {
                final long id = Long.parseLong(m.group(1));
                switch (method) {
                    case "GET":
                        if (store.containsKey(id)) {
                            respond(exchange, 200, "{\"id\":" + id + ",\"name\":" + nameOrNull(store.get(id)) + "}");
                        } else {
                            respond(exchange, 404, "{\"error\":\"not found\"}");
                        }
                        break;
                    case "PUT":
                        if (store.containsKey(id)) {
                            store.put(id, readBody(exchange));
                            respond(exchange, 200, "{\"id\":" + id + "}");
                        } else {
                            respond(exchange, 404, "{\"error\":\"not found\"}");
                        }
                        break;
                    case "DELETE":
                        if (store.remove(id) != null) {
                            respond(exchange, 200, "{\"id\":" + id + "}");
                        } else {
                            respond(exchange, 404, "{\"error\":\"not found\"}");
                        }
                        break;
                    default:
                        respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                }
            } else {
                respond(exchange, 404, "{\"error\":\"not found\"}");
            }
        } catch (Exception e) {
            respond(exchange, 500, "{\"error\":\"server error\"}");
        }
    }

    /** Pre-create {@code count} items so read scenarios have data to hit. Returns the first id used. */
    public long seed(final int count) {
        final long first = idSeq.get() + 1;
        for (int i = 0; i < count; i++) {
            final long id = idSeq.incrementAndGet();
            store.put(id, "{\"name\":\"seed-" + id + "\"}");
        }
        log.info("Seeded {} items (ids {}..{})", count, first, idSeq.get());
        return first;
    }

    private static String readBody(final HttpExchange exchange) throws IOException {
        final byte[] bytes = exchange.getRequestBody().readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String nameOrNull(final String body) {
        if (body == null) {
            return "null";
        }
        final Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? '"' + m.group(1) + '"' : "null";
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) {
        try {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            // client went away; nothing useful to do under load
        }
    }
}
