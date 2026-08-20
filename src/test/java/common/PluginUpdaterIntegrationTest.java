package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PluginUpdaterIntegrationTest {
    private static final String PLUGIN_NAME = "Example";
    private static final String SOURCE = "https://modrinth.com/plugin/example";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private UpdateOptionsSnapshot originalOptions;
    private ModrinthServer server;
    private Path dataFolder;
    private Path pluginsFolder;
    private Path installedJar;
    private Logger logger;

    @Before
    public void setUp() throws Exception {
        originalOptions = UpdateOptionsSnapshot.capture();
        dataFolder = temporaryFolder.newFolder("data").toPath();
        pluginsFolder = temporaryFolder.newFolder("plugins").toPath();
        Path downloadsFolder = temporaryFolder.newFolder("downloads").toPath();
        installedJar = pluginsFolder.resolve(PLUGIN_NAME + ".jar");
        server = new ModrinthServer();

        logger = Logger.getLogger("PluginUpdaterIntegrationTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);

        UpdateOptions.zipFileCheck = true;
        UpdateOptions.ignoreDuplicates = true;
        UpdateOptions.sslVerify = true;
        UpdateOptions.tempPath = downloadsFolder.toString();
        UpdateOptions.updatePath = null;
        UpdateOptions.filePath = pluginsFolder.toString();
        UpdateOptions.maxParallel = 1;
        UpdateOptions.maxPerHost = 1;
        UpdateOptions.connectTimeoutMs = 3000;
        UpdateOptions.readTimeoutMs = 3000;
        UpdateOptions.perDownloadTimeoutSec = 0;
        UpdateOptions.maxRetries = 2;
        UpdateOptions.backoffBaseMs = 1;
        UpdateOptions.backoffMaxMs = 2;
        UpdateOptions.debug = false;
        UpdateOptions.userAgents = new ArrayList<String>();
        UpdateOptions.useUpdateFolder = false;
        UpdateOptions.manualMode = false;
        UpdateOptions.hostSemaphores.clear();
        UpdateOptions.rollbackEnabled = false;
        UpdateOptions.restartAfterUpdate = false;

        UpdateOptions.metadataCacheEnabled = true;
        UpdateOptions.metadataCacheFile = "metadata.json";
        UpdateOptions.metadataCacheTtlMinutes = 0;
        UpdateOptions.skipDownloadWhenMetadataUnchanged = true;
        UpdateOptions.cacheDirectUrlHeadMetadata = false;

        UpdateOptions.updateLogEnabled = true;
        UpdateOptions.updateLogPath = "logs";
        UpdateOptions.updateLogFilePattern = "yyyy-MM-dd'.log'";
        UpdateOptions.updateLogCommandPageSize = 100;
        UpdateOptions.updateLogIncludeUnchanged = true;
        UpdateOptions.updateLogIncludeChecks = true;

        UpdateOptions.serverMinecraftVersion = "1.21.8";
        UpdateOptions.modrinthMinecraftVersionCheck = true;
        UpdateOptions.strictMinecraftVersionMetadata = true;
        UpdateOptions.allowPreReleaseDefault = false;
        UpdateOptions.versionPolicyDefault = "any";
        UpdateOptions.unknownVersionPolicy = "allow";
        UpdateOptions.allowSameVersionSnapshotUpdates = true;
        UpdateOptions.allowSameVersionReleaseHashUpdates = false;
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        if (originalOptions != null) {
            originalOptions.restore();
        }
    }

    @Test
    public void installUsesCacheForceAndVersionPolicyAcrossRealTransfers() throws Exception {
        Files.write(installedJar, pluginJar("1.0.0"));
        byte[] version110 = pluginJar("1.1.0");
        server.release("1.1.0", version110);
        PluginUpdater updater = updater();

        assertTrue(runInstall(updater, SOURCE));
        assertArrayEquals(version110, Files.readAllBytes(installedJar));
        assertEquals("1.1.0", JarMetadata.read(installedJar).version);
        assertEquals(1, server.metadataHits.get());
        int payloadsAfterInstall = server.payloadHits.get();
        assertTrue(payloadsAfterInstall > 0);

        Path cacheFile = dataFolder.resolve("metadata.json");
        assertTrue(Files.isRegularFile(cacheFile));
        JsonNode entries = new ObjectMapper().readTree(cacheFile.toFile()).path("entries");
        assertEquals(1, entries.size());
        JsonNode cached = entries.elements().next();
        assertEquals("1.1.0", cached.path("version").asText());
        assertTrue(cached.path("metadataId").asText().contains("release-1-1-0"));
        assertTrue(history(updater).stream().anyMatch(line ->
                line.contains("APPLIED Example 1.0.0 -> 1.1.0")));

        assertFalse(runInstall(updater, SOURCE));
        assertEquals(2, server.metadataHits.get());
        assertEquals(payloadsAfterInstall, server.payloadHits.get());
        assertTrue(history(updater).stream().anyMatch(line ->
                line.contains("SKIPPED Example") && line.contains("metadata unchanged")));

        assertFalse(runInstall(updater, SOURCE + "?force=true"));
        assertEquals(3, server.metadataHits.get());
        assertTrue(server.payloadHits.get() > payloadsAfterInstall);
        assertArrayEquals(version110, Files.readAllBytes(installedJar));

        byte[] version120 = pluginJar("1.2.0");
        server.release("1.2.0", version120);
        int payloadsBeforeBlockedUpdate = server.payloadHits.get();
        assertFalse(runInstall(updater, SOURCE + "?versionPolicy=patch"));

        assertEquals(4, server.metadataHits.get());
        assertEquals(payloadsBeforeBlockedUpdate, server.payloadHits.get());
        assertArrayEquals(version110, Files.readAllBytes(installedJar));
        assertEquals("1.1.0", JarMetadata.read(installedJar).version);
        assertTrue(history(updater).stream().anyMatch(line ->
                line.contains("BLOCKED Example 1.1.0 -> 1.2.0")
                        && line.contains("versionPolicy patch")));
    }

    @Test
    public void checkModeRecordsAvailablePendingUpdateWithoutChangingFiles() throws Exception {
        byte[] version100 = pluginJar("1.0.0");
        Files.write(installedJar, version100);
        server.release("1.1.0", pluginJar("1.1.0"));
        PluginUpdater updater = updater();

        PluginUpdater.RunSummary summary = runCheck(updater, SOURCE);

        assertEquals(PluginUpdater.ExecutionMode.CHECK, summary.mode);
        assertEquals(1, summary.total);
        assertEquals(1, summary.available);
        assertEquals(0, summary.unchanged);
        assertEquals(0, summary.applied);
        assertEquals(0, summary.failed);
        assertEquals(1, summary.pendingCount);
        assertEquals(PluginUpdater.EntryResult.AVAILABLE, summary.entries.get(PLUGIN_NAME));
        assertArrayEquals(version100, Files.readAllBytes(installedJar));
        assertEquals("1.0.0", JarMetadata.read(installedJar).version);
        assertFalse(Files.exists(pluginsFolder.resolve(PLUGIN_NAME + ".jar.temp")));
        assertTrue(server.payloadHits.get() > 0);

        PluginUpdater.PendingUpdate pending = updater.getPendingUpdates().get(PLUGIN_NAME);
        assertNotNull(pending);
        assertEquals("modrinth", pending.provider);
        assertEquals("1.1.0", pending.remoteVersion);
        assertEquals(installedJar.toAbsolutePath().normalize().toString(), pending.targetPath);
        assertTrue(history(updater).stream().anyMatch(line ->
                line.contains("AVAILABLE Example 1.0.0 -> 1.1.0")));
    }

    @Test
    public void incompatibleProviderMetadataIsReportedAsBlockedNotFailed() throws Exception {
        Files.write(installedJar, pluginJar("1.0.0"));
        server.release("1.1.0", pluginJar("1.1.0"), "1.21.9");
        PluginUpdater updater = updater();

        PluginUpdater.RunSummary summary = runCheck(updater, SOURCE + "?versionCheck=true");

        assertEquals(0, summary.available);
        assertEquals(1, summary.unchanged);
        assertEquals(0, summary.failed);
        assertEquals(0, server.payloadHits.get());
        assertTrue(history(updater).stream().anyMatch(line ->
                line.contains("BLOCKED Example")
                        && line.contains("No compatible Modrinth version")));
    }

    @Test
    public void directHeadMetadataSupportsEntryCacheAndRefreshWithoutRedownloading() throws Exception {
        Files.write(installedJar, pluginJar("1.0.0"));
        byte[] updated = pluginJar("1.1.0");
        UpdateOptions.metadataCacheEnabled = false;
        UpdateOptions.cacheDirectUrlHeadMetadata = true;

        try (DirectServer direct = new DirectServer(updated)) {
            PluginUpdater updater = new PluginUpdater(logger, dataFolder);
            String source = direct.url("/Example.jar?cache=true");

            assertTrue(runInstall(updater, source));
            assertArrayEquals(updated, Files.readAllBytes(installedJar));
            assertEquals(1, direct.getHits.get());
            assertEquals(1, direct.headHits.get());
            assertTrue(Files.isRegularFile(dataFolder.resolve("metadata.json")));

            assertFalse(runInstall(updater, source));
            assertEquals(1, direct.getHits.get());
            assertEquals(2, direct.headHits.get());

            assertFalse(runInstall(updater, direct.url("/Example.jar?cache=true&refreshCache=true")));
            assertEquals("refreshCache must not force the payload", 1, direct.getHits.get());
            assertEquals(3, direct.headHits.get());

            UpdateOptions.metadataCacheFile = "metadata-reloaded.json";
            updater.reloadPersistence();
            assertFalse(runInstall(updater, source));
            assertEquals("a reloaded empty cache must perform one comparison", 2, direct.getHits.get());
            assertTrue(Files.isRegularFile(dataFolder.resolve("metadata-reloaded.json")));
        }
    }

    @Test
    public void genericDirectTransferEnforcesPerEntryMajorVersionPolicyBeforeInstall() throws Exception {
        byte[] original = pluginJar("1.4.0");
        Files.write(installedJar, original);
        try (DirectServer direct = new DirectServer(pluginJar("2.0.0"))) {
            PluginUpdater updater = new PluginUpdater(logger, dataFolder);

            assertFalse(runInstall(updater,
                    direct.url("/Example.jar?versionPolicy=same-major")));

            assertArrayEquals(original, Files.readAllBytes(installedJar));
            assertEquals("1.4.0", JarMetadata.read(installedJar).version);
            assertEquals(1, direct.getHits.get());
            assertTrue(history(updater).stream().anyMatch(line ->
                    line.contains("BLOCKED Example 1.4.0 -> 2.0.0")
                            && line.contains("versionPolicy same_major")));
        }
    }

    @Test
    public void legacyJenkinsArchiveFallbackEnforcesPerEntryMajorVersionPolicyBeforeInstall()
            throws Exception {
        byte[] original = pluginJar("1.4.0");
        Files.write(installedJar, original);
        try (LegacyJenkinsServer jenkins = new LegacyJenkinsServer(pluginJar("2.0.0"))) {
            PluginUpdater updater = new PluginUpdater(logger, dataFolder);

            assertFalse(runInstall(updater,
                    jenkins.jobUrl() + "?versionPolicy=same-major"));

            assertArrayEquals(original, Files.readAllBytes(installedJar));
            assertEquals("1.4.0", JarMetadata.read(installedJar).version);
            assertEquals(1, jenkins.archiveHits.get());
            assertTrue(history(updater).stream().anyMatch(line ->
                    line.contains("BLOCKED Example 1.4.0 -> 2.0.0")
                            && line.contains("versionPolicy same_major")));
        }
    }

    private PluginUpdater updater() {
        return new PluginUpdater(logger, dataFolder,
                new ModrinthProvider(server.url("/v2")),
                new HangarProvider(),
                new ExtendedClipProvider(),
                new GitLabProvider(logger));
    }

    private boolean runInstall(PluginUpdater updater, String source) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean applied = new AtomicBoolean(false);
        updater.updateEntries(entry(source), "paper", null, value -> {
            applied.set(value);
            finished.countDown();
        });
        assertTrue("install run timed out", finished.await(10, TimeUnit.SECONDS));
        return applied.get();
    }

    private PluginUpdater.RunSummary runCheck(PluginUpdater updater, String source) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<PluginUpdater.RunSummary> result = new AtomicReference<PluginUpdater.RunSummary>();
        updater.checkEntries(entry(source), "paper", null, summary -> {
            result.set(summary);
            finished.countDown();
        });
        assertTrue("check run timed out", finished.await(10, TimeUnit.SECONDS));
        assertNotNull(result.get());
        return result.get();
    }

    private static Map<String, String> entry(String source) {
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put(PLUGIN_NAME, source);
        return entries;
    }

    private static List<String> history(PluginUpdater updater) {
        return updater.readHistory(LocalDate.now(ZoneOffset.UTC), 1).lines;
    }

    private static byte[] pluginJar(String version) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            JarEntry descriptor = new JarEntry("plugin.yml");
            descriptor.setTime(0L);
            jar.putNextEntry(descriptor);
            jar.write(("name: " + PLUGIN_NAME + "\nversion: " + version + "\n")
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

    private static String sha1(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest(bytes)) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class ModrinthServer implements Closeable {
        private final HttpServer http;
        private final AtomicReference<Release> current = new AtomicReference<Release>();
        private final AtomicInteger metadataHits = new AtomicInteger();
        private final AtomicInteger payloadHits = new AtomicInteger();

        private ModrinthServer() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", this::handle);
            http.start();
        }

        private void release(String version, byte[] payload) {
            release(version, payload, "1.21.8");
        }

        private void release(String version, byte[] payload, String gameVersion) {
            current.set(new Release(version, payload.clone(), gameVersion));
        }

        private String url(String path) {
            String normalized = path.startsWith("/") ? path : "/" + path;
            return "http://127.0.0.1:" + http.getAddress().getPort() + normalized;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            Release release = current.get();
            if ("/v2/project/example/version".equals(path) && release != null) {
                metadataHits.incrementAndGet();
                String json = "[{\"id\":\"release-" + release.version.replace('.', '-') + "\","
                        + "\"project_id\":\"example-project\","
                        + "\"version_number\":\"" + release.version + "\","
                        + "\"version_type\":\"release\","
                        + "\"date_published\":\"2026-08-19T00:00:00Z\","
                        + "\"loaders\":[\"paper\"],\"game_versions\":[\"" + release.gameVersion + "\"],"
                        + "\"changelog\":\"Integration release " + release.version + "\","
                        + "\"files\":[{\"url\":\"" + url("/files/Example.jar") + "\","
                        + "\"filename\":\"Example.jar\",\"primary\":true,"
                        + "\"size\":" + release.payload.length + ","
                        + "\"hashes\":{\"sha1\":\"" + sha1(release.payload) + "\"}}]}]";
                send(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/files/Example.jar".equals(path) && release != null) {
                payloadHits.incrementAndGet();
                send(exchange, 200, "application/java-archive", release.payload);
                return;
            }
            send(exchange, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
        }

        private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
                throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        @Override
        public void close() {
            http.stop(0);
        }

        private static final class Release {
            private final String version;
            private final byte[] payload;
            private final String gameVersion;

            private Release(String version, byte[] payload, String gameVersion) {
                this.version = version;
                this.payload = payload;
                this.gameVersion = gameVersion;
            }
        }
    }

    private static final class DirectServer implements Closeable {
        private final HttpServer http;
        private final byte[] payload;
        private final AtomicInteger headHits = new AtomicInteger();
        private final AtomicInteger getHits = new AtomicInteger();

        private DirectServer(byte[] payload) throws IOException {
            this.payload = payload.clone();
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/Example.jar", this::handle);
            http.start();
        }

        private String url(String path) {
            return "http://127.0.0.1:" + http.getAddress().getPort() + path;
        }

        private void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("ETag", "\"direct-v1\"");
            exchange.getResponseHeaders().set("Last-Modified", "Wed, 19 Aug 2026 00:00:00 GMT");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=Example.jar");
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                headHits.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(payload.length));
                exchange.sendResponseHeaders(200, -1L);
                exchange.close();
                return;
            }
            getHits.incrementAndGet();
            ModrinthServer.send(exchange, 200, "application/java-archive", payload);
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    private static final class LegacyJenkinsServer implements Closeable {
        private static final String ARCHIVE_PATH =
                "/job/Example/lastSuccessfulBuild/artifact/*zip*/archive.zip";

        private final HttpServer http;
        private final byte[] payload;
        private final AtomicInteger archiveHits = new AtomicInteger();

        private LegacyJenkinsServer(byte[] payload) throws IOException {
            this.payload = payload.clone();
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", this::handle);
            http.start();
        }

        private String jobUrl() {
            return "http://127.0.0.1:" + http.getAddress().getPort() + "/job/Example/";
        }

        private void handle(HttpExchange exchange) throws IOException {
            if (ARCHIVE_PATH.equals(exchange.getRequestURI().getPath())) {
                archiveHits.incrementAndGet();
                ModrinthServer.send(exchange, 200, "application/java-archive", payload);
                return;
            }
            ModrinthServer.send(exchange, 404, "application/json; charset=utf-8",
                    "{\"message\":\"legacy Jenkins API unavailable\"}"
                            .getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    /** Captures every current and future static option, including mutable final collections. */
    private static final class UpdateOptionsSnapshot {
        private final List<SavedField> fields;

        private UpdateOptionsSnapshot(List<SavedField> fields) {
            this.fields = fields;
        }

        private static UpdateOptionsSnapshot capture() throws IllegalAccessException {
            List<SavedField> saved = new ArrayList<SavedField>();
            for (Field field : UpdateOptions.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
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
                if (!Modifier.isFinal(saved.field.getModifiers()) && saved.field.get(null) != saved.original) {
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
