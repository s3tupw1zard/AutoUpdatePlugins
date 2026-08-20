package common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DirectUrlProviderTest {
    @After
    public void clearHeaders() {
        PluginDownloader.setHttpHeaders(Collections.<String, String>emptyMap(), null);
    }

    @Test
    public void resolvesValidatorsAndRedactsQueryFromPersistentSource() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.getResponseHeaders().set("ETag", "\"release-7\"");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=Plugin.jar");
            exchange.sendResponseHeaders(200, -1L);
            exchange.close();
        });
        try {
            String base = url(server, "/download.jar");
            ResolvedUpdate update = new DirectUrlProvider().resolve("Plugin", base + "?signature=secret");

            assertNotNull(update);
            assertEquals("direct", update.provider);
            assertEquals(base, update.normalizedSource);
            assertTrue(update.downloadUrl.endsWith("?signature=secret"));
            assertEquals("Plugin.jar", update.fileName);
            assertTrue(update.metadataId().startsWith("head:"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void fallsBackWhenOriginProvidesNoStableValidator() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, -1L);
            exchange.close();
        });
        try {
            assertNull(new DirectUrlProvider().resolve("Plugin", url(server, "/download.jar")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void fallsBackWhenOriginProvidesOnlyAWeakEtag() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.getResponseHeaders().set("ETag", "W/\"release-7\"");
            exchange.sendResponseHeaders(200, -1L);
            exchange.close();
        });
        try {
            assertNull(new DirectUrlProvider().resolve("Plugin", url(server, "/download.jar")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void stripsConfiguredHeadersAcrossMetadataRedirectOrigins() throws Exception {
        AtomicReference<String> destinationSecret = new AtomicReference<String>();
        HttpServer destination = server(exchange -> {
            destinationSecret.set(exchange.getRequestHeaders().getFirst("X-Private-Token"));
            exchange.getResponseHeaders().set("ETag", "\"redirected\"");
            exchange.sendResponseHeaders(200, -1L);
            exchange.close();
        });
        HttpServer origin = server(exchange -> {
            exchange.getResponseHeaders().set("Location", url(destination, "/artifact.jar"));
            exchange.sendResponseHeaders(302, -1L);
            exchange.close();
        });
        PluginDownloader.setHttpHeaders(Collections.singletonMap("X-Private-Token", "secret"), null);
        try {
            assertNotNull(new DirectUrlProvider().resolve("Plugin", url(origin, "/redirect")));
            assertNull(destinationSecret.get());
        } finally {
            origin.stop(0);
            destination.stop(0);
        }
    }

    private static HttpServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
