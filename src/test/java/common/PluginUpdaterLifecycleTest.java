package common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginUpdaterLifecycleTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private UpdateOptionsSnapshot originalOptions;
    private Path dataFolder;
    private Path pluginsFolder;
    private TimeoutLogHandler timeoutLogs;
    private Logger logger;

    @Before
    public void setUp() throws Exception {
        originalOptions = UpdateOptionsSnapshot.capture();
        dataFolder = temporaryFolder.newFolder("data").toPath();
        pluginsFolder = temporaryFolder.newFolder("plugins").toPath();
        Path downloadsFolder = temporaryFolder.newFolder("downloads").toPath();

        timeoutLogs = new TimeoutLogHandler();
        logger = Logger.getLogger(getClass().getName() + '.' + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(timeoutLogs);

        UpdateOptions.zipFileCheck = true;
        UpdateOptions.ignoreDuplicates = true;
        UpdateOptions.sslVerify = true;
        UpdateOptions.tempPath = downloadsFolder.toString();
        UpdateOptions.updatePath = null;
        UpdateOptions.filePath = pluginsFolder.toString();
        UpdateOptions.maxParallel = 2;
        UpdateOptions.maxPerHost = 2;
        UpdateOptions.connectTimeoutMs = 1000;
        UpdateOptions.readTimeoutMs = 400;
        UpdateOptions.perDownloadTimeoutSec = 0;
        UpdateOptions.maxRetries = 1;
        UpdateOptions.backoffBaseMs = 1;
        UpdateOptions.backoffMaxMs = 2;
        UpdateOptions.debug = false;
        UpdateOptions.userAgents = new ArrayList<String>();
        UpdateOptions.useUpdateFolder = false;
        UpdateOptions.manualMode = false;
        UpdateOptions.hostSemaphores.clear();
        UpdateOptions.rollbackEnabled = false;
        UpdateOptions.restartAfterUpdate = false;
        UpdateOptions.metadataCacheEnabled = false;
        UpdateOptions.cacheDirectUrlHeadMetadata = false;
        UpdateOptions.updateLogEnabled = false;
        UpdateOptions.serverMinecraftVersion = "";
        UpdateOptions.versionPolicyDefault = "any";
        UpdateOptions.unknownVersionPolicy = "allow";
    }

    @After
    public void tearDown() throws Exception {
        if (originalOptions != null) originalOptions.restore();
    }

    @Test
    public void stopDuringBlockedMultiEntryRunCompletesOnceMarksAllTerminalAndAllowsReuse()
            throws Exception {
        byte[] payload = pluginJar("FastPlugin", "2.0.0");
        try (LifecycleServer server = new LifecycleServer(payload)) {
            PluginUpdater updater = new PluginUpdater(logger, dataFolder);
            LinkedHashMap<String, String> blocked = new LinkedHashMap<String, String>();
            blocked.put("BlockedOne", server.url("/blocked/one.jar"));
            blocked.put("BlockedTwo", server.url("/blocked/two.jar"));
            blocked.put("BlockedQueued", server.url("/blocked/queued.jar"));

            RunCapture first = new RunCapture();
            updater.checkEntries(blocked, "paper", null, first::complete);
            assertTrue("two parallel HTTP requests did not block",
                    server.blockedRequestsStarted.await(5, TimeUnit.SECONDS));
            assertTrue(updater.isUpdating());

            assertTrue("stop request was not accepted", updater.stopUpdates());
            assertTrue("stopped run never completed", first.finished.await(10, TimeUnit.SECONDS));

            PluginUpdater.RunSummary stopped = first.summary.get();
            assertNotNull(stopped);
            assertEquals(1, first.callbacks.get());
            assertEquals(3, stopped.total);
            assertEquals(0, stopped.available);
            assertEquals(0, stopped.unchanged);
            assertEquals(0, stopped.applied);
            assertEquals(3, stopped.failed);
            assertEquals(3, stopped.entries.size());
            for (String name : blocked.keySet()) {
                assertEquals(PluginUpdater.EntryResult.FAILED, stopped.entries.get(name));
            }
            assertFalse(updater.isUpdating());
            assertFalse("completed updater still accepted a stop request", updater.stopUpdates());

            server.releaseBlockedRequests();
            PluginUpdater.RunSummary second = runCheck(updater,
                    Collections.singletonMap("FastPlugin", server.url("/fast/FastPlugin.jar")));
            assertEquals(1, second.total);
            assertEquals(1, second.available);
            assertEquals(PluginUpdater.EntryResult.AVAILABLE,
                    second.entries.get("FastPlugin"));
            assertEquals("first callback was invoked again", 1, first.callbacks.get());
            assertFalse(updater.isUpdating());
            assertFalse(Files.exists(pluginsFolder.resolve("FastPlugin.jar")));
        }
    }

    @Test
    public void immediateStopCompletesEvenWhenCoordinatorHasNotPublishedItsExecutor()
            throws Exception {
        try (LifecycleServer server = new LifecycleServer(pluginJar("Immediate", "1.0.0"))) {
            PluginUpdater updater = new PluginUpdater(logger, dataFolder);
            LinkedHashMap<String, String> entries = new LinkedHashMap<String, String>();
            entries.put("ImmediateOne", server.url("/blocked/immediate-one.jar"));
            entries.put("ImmediateTwo", server.url("/blocked/immediate-two.jar"));
            RunCapture capture = new RunCapture();

            updater.checkEntries(entries, "paper", null, capture::complete);
            assertTrue("immediate stop request was not accepted", updater.stopUpdates());
            server.releaseBlockedRequests();

            assertTrue("immediately stopped run never completed",
                    capture.finished.await(10, TimeUnit.SECONDS));
            assertEquals(1, capture.callbacks.get());
            assertNotNull(capture.summary.get());
            assertEquals(2, capture.summary.get().total);
            assertEquals(2, capture.summary.get().failed);
            assertEquals(2, capture.summary.get().entries.size());
            assertFalse(updater.isUpdating());
        }
    }

    @Test
    public void perEntryTimeoutCannotInstallPayloadReleasedAfterDeadline() throws Exception {
        byte[] original = pluginJar("LatePlugin", "1.0.0");
        byte[] replacement = pluginJar("LatePlugin", "2.0.0");
        Path installed = pluginsFolder.resolve("LatePlugin.jar");
        Files.write(installed, original);

        UpdateOptions.maxParallel = 1;
        UpdateOptions.maxPerHost = 1;
        UpdateOptions.readTimeoutMs = 5000;
        UpdateOptions.perDownloadTimeoutSec = 1;

        try (LifecycleServer server = new LifecycleServer(replacement)) {
            PluginUpdater updater = new PluginUpdater(logger, dataFolder);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            AtomicBoolean applied = new AtomicBoolean(true);

            updater.updateEntries(Collections.singletonMap(
                    "LatePlugin", server.url("/late/LatePlugin.jar")), "paper", null, value -> {
                callbacks.incrementAndGet();
                applied.set(value);
                finished.countDown();
            });

            assertTrue("late payload request did not start",
                    server.lateRequestStarted.await(5, TimeUnit.SECONDS));
            assertTrue("per-entry timeout did not fire",
                    timeoutLogs.timeoutObserved.await(5, TimeUnit.SECONDS));
            // The timeout warning is emitted immediately before the worker interrupt. Give that
            // interrupt a narrow deterministic window, then let every outstanding HTTP request
            // receive a valid JAR to prove a late response cannot cross the install boundary.
            Thread.sleep(150L);
            server.releaseLateRequests();

            assertTrue("timed-out run never completed", finished.await(10, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
            assertFalse("timed-out run reported an applied update", applied.get());
            assertFalse(updater.isUpdating());
            assertArrayEquals("payload arriving after timeout replaced the installed JAR",
                    original, Files.readAllBytes(installed));
            assertFalse(Files.exists(pluginsFolder.resolve("LatePlugin.jar.temp")));
            assertNoLiveThreadNamed("aup-download-timeout", 3000L);
        }
    }

    private PluginUpdater.RunSummary runCheck(PluginUpdater updater, Map<String, String> entries)
            throws Exception {
        RunCapture capture = new RunCapture();
        updater.checkEntries(entries, "paper", null, capture::complete);
        assertTrue("check run timed out", capture.finished.await(10, TimeUnit.SECONDS));
        assertEquals(1, capture.callbacks.get());
        assertNotNull(capture.summary.get());
        return capture.summary.get();
    }

    private static void assertNoLiveThreadNamed(String name, long waitMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        do {
            boolean found = false;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.isAlive() && name.equals(thread.getName())) {
                    found = true;
                    break;
                }
            }
            if (!found) return;
            Thread.sleep(25L);
        } while (System.nanoTime() < deadline);
        fail("Thread still alive after run completion: " + name);
    }

    private static byte[] pluginJar(String pluginName, String version) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            JarEntry descriptor = new JarEntry("plugin.yml");
            descriptor.setTime(0L);
            jar.putNextEntry(descriptor);
            jar.write(("name: " + pluginName + "\nversion: " + version + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();

            JarEntry content = new JarEntry("version.txt");
            content.setTime(0L);
            jar.putNextEntry(content);
            jar.write(version.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static final class RunCapture {
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicInteger callbacks = new AtomicInteger();
        private final AtomicReference<PluginUpdater.RunSummary> summary =
                new AtomicReference<PluginUpdater.RunSummary>();

        private void complete(PluginUpdater.RunSummary value) {
            callbacks.incrementAndGet();
            summary.set(value);
            finished.countDown();
        }
    }

    private static final class TimeoutLogHandler extends Handler {
        private final CountDownLatch timeoutObserved = new CountDownLatch(1);

        @Override
        public void publish(LogRecord record) {
            if (record != null && record.getMessage() != null
                    && record.getMessage().contains("Update timed out for LatePlugin")) {
                timeoutObserved.countDown();
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static final class LifecycleServer implements Closeable {
        private final HttpServer http;
        private final ExecutorService executor;
        private final byte[] payload;
        private final CountDownLatch blockedRequestsStarted = new CountDownLatch(2);
        private final CountDownLatch blockedRelease = new CountDownLatch(1);
        private final CountDownLatch lateRequestStarted = new CountDownLatch(1);
        private final CountDownLatch lateRelease = new CountDownLatch(1);

        private LifecycleServer(byte[] payload) throws IOException {
            this.payload = payload.clone();
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "aup-lifecycle-http");
                thread.setDaemon(true);
                return thread;
            });
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(executor);
            http.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    String path = exchange.getRequestURI().getPath();
                    if (path.startsWith("/blocked/")) {
                        blockedRequestsStarted.countDown();
                        awaitRelease(blockedRelease);
                        send(exchange, 503, "text/plain", "stopped".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    if (path.startsWith("/late/")) {
                        lateRequestStarted.countDown();
                        awaitRelease(lateRelease);
                        send(exchange, 200, "application/java-archive", payload);
                        return;
                    }
                    if (path.startsWith("/fast/")) {
                        send(exchange, 200, "application/java-archive", payload);
                        return;
                    }
                    send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                }
            });
            http.start();
        }

        private String url(String path) {
            return "http://127.0.0.1:" + http.getAddress().getPort() + path;
        }

        private void releaseBlockedRequests() {
            blockedRelease.countDown();
        }

        private void releaseLateRequests() {
            lateRelease.countDown();
        }

        private static void awaitRelease(CountDownLatch release) throws IOException {
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IOException("test server release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("test server interrupted", interrupted);
            }
        }

        private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
                throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=plugin.jar");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        @Override
        public void close() {
            blockedRelease.countDown();
            lateRelease.countDown();
            http.stop(0);
            executor.shutdownNow();
        }
    }

    /** Captures every static option, including mutable final collections. */
    private static final class UpdateOptionsSnapshot {
        private final List<SavedField> fields;

        private UpdateOptionsSnapshot(List<SavedField> fields) {
            this.fields = fields;
        }

        private static UpdateOptionsSnapshot capture() throws IllegalAccessException {
            List<SavedField> saved = new ArrayList<SavedField>();
            for (Field field : UpdateOptions.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object original = field.get(null);
                if (original instanceof Map) {
                    synchronized (original) {
                        saved.add(new SavedField(field, original,
                                new LinkedHashMap<Object, Object>((Map<?, ?>) original), SavedKind.MAP));
                    }
                } else if (original instanceof Collection) {
                    synchronized (original) {
                        saved.add(new SavedField(field, original,
                                new ArrayList<Object>((Collection<?>) original), SavedKind.COLLECTION));
                    }
                } else if (!Modifier.isFinal(field.getModifiers())) {
                    saved.add(new SavedField(field, original, null, SavedKind.VALUE));
                }
            }
            return new UpdateOptionsSnapshot(saved);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void restore() throws IllegalAccessException {
            for (SavedField saved : fields) {
                if (saved.kind == SavedKind.VALUE) {
                    saved.field.set(null, saved.original);
                    continue;
                }
                if (!Modifier.isFinal(saved.field.getModifiers())
                        && saved.field.get(null) != saved.original) {
                    saved.field.set(null, saved.original);
                }
                synchronized (saved.original) {
                    if (saved.kind == SavedKind.MAP) {
                        Map map = (Map) saved.original;
                        map.clear();
                        map.putAll((Map) saved.contents);
                    } else {
                        Collection collection = (Collection) saved.original;
                        collection.clear();
                        collection.addAll((Collection) saved.contents);
                    }
                }
            }
        }
    }

    private enum SavedKind {
        VALUE,
        MAP,
        COLLECTION
    }

    private static final class SavedField {
        private final Field field;
        private final Object original;
        private final Object contents;
        private final SavedKind kind;

        private SavedField(Field field, Object original, Object contents, SavedKind kind) {
            this.field = field;
            this.original = original;
            this.contents = contents;
            this.kind = kind;
        }
    }
}
