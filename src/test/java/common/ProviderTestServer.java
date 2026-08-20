package common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class ProviderTestServer implements Closeable {
    private final HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> emptyPage = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    final AtomicInteger hits = new AtomicInteger();
    final AtomicReference<URI> lastRequest = new AtomicReference<>();

    ProviderTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url(String path) {
        String normalized = path == null || path.isEmpty() ? "/" : path;
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        return "http://127.0.0.1:" + server.getAddress().getPort() + normalized;
    }

    void json(String value) {
        status.set(200);
        body.set(value);
        emptyPage.set(null);
    }

    void pagedJson(String firstPage, String laterPages) {
        status.set(200);
        body.set(firstPage);
        emptyPage.set(laterPages);
    }

    void failure(int responseStatus, String value) {
        status.set(responseStatus);
        body.set(value);
        emptyPage.set(null);
    }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        lastRequest.set(exchange.getRequestURI());
        String response = body.get();
        String later = emptyPage.get();
        String query = exchange.getRequestURI().getRawQuery();
        if (later != null && hasNonZeroOffset(query)) {
            response = later;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status.get(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static boolean hasNonZeroOffset(String query) {
        if (query == null) return false;
        for (String component : query.split("&")) {
            if (!component.startsWith("offset=")) continue;
            return !"0".equals(component.substring("offset=".length()));
        }
        return false;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
