package common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PluginDownloaderRedirectSecurityTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String oldTempPath;
    private int oldRetries;
    private int oldBackoffBase;
    private int oldBackoffMax;

    @Before
    public void saveOptions() {
        oldTempPath = UpdateOptions.tempPath;
        oldRetries = UpdateOptions.maxRetries;
        oldBackoffBase = UpdateOptions.backoffBaseMs;
        oldBackoffMax = UpdateOptions.backoffMaxMs;
        UpdateOptions.maxRetries = 2;
        UpdateOptions.backoffBaseMs = 0;
        UpdateOptions.backoffMaxMs = 0;
    }

    @After
    public void restoreOptions() {
        UpdateOptions.tempPath = oldTempPath;
        UpdateOptions.maxRetries = oldRetries;
        UpdateOptions.backoffBaseMs = oldBackoffBase;
        UpdateOptions.backoffMaxMs = oldBackoffMax;
    }

    @Test
    public void providerTokenIsRemovedOnCrossOriginRedirect() throws Exception {
        final byte[] jar = pluginJar();
        final AtomicReference<String> originToken = new AtomicReference<String>();
        final AtomicReference<String> targetToken = new AtomicReference<String>();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/payload.jar", exchange -> {
            targetToken.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            send(exchange, 200, jar);
        });
        target.start();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/package.jar", exchange -> {
            originToken.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:"
                    + target.getAddress().getPort() + "/payload.jar");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        origin.start();
        try {
            Path root = temporaryFolder.newFolder("download").toPath();
            Path plugins = root.resolve("plugins");
            UpdateOptions.tempPath = root.resolve("tmp").toString();
            String source = "http://127.0.0.1:" + origin.getAddress().getPort() + "/package.jar";
            ResolvedUpdate expected = ResolvedUpdate.builder("Redirected", "gitlab", source)
                    .normalizedSource("gitlab:test/project")
                    .versionId("1")
                    .versionLabel("1.0.0")
                    .fileName("Redirected.jar")
                    .size(jar.length)
                    .build();

            TransferOutcome outcome = new PluginDownloader(testLogger()).transferRemotePluginDetailed(
                    source, "Redirected", null,
                    "filePath=" + plugins + "|useUpdateFolder=false", true, expected,
                    Collections.singletonMap("PRIVATE-TOKEN", "top-secret"));

            assertEquals(TransferOutcome.Status.APPLIED, outcome.status);
            assertEquals("top-secret", originToken.get());
            assertNull(targetToken.get());
            assertTrue(Files.isRegularFile(plugins.resolve("Redirected.jar")));
        } finally {
            origin.stop(0);
            target.stop(0);
        }
    }

    private static byte[] pluginJar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            jar.write("name: Redirected\nversion: 1.0.0\nmain: test.Main\n"
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Logger testLogger() {
        Logger logger = Logger.getLogger("PluginDownloaderRedirectSecurityTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        return logger;
    }
}
