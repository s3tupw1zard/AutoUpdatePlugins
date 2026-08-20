package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UpdateMetadataCacheTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private boolean originalEnabled;
    private String originalFile;
    private int originalTtl;
    private boolean originalSkip;

    @Before
    public void rememberConfiguration() {
        originalEnabled = UpdateOptions.metadataCacheEnabled;
        originalFile = UpdateOptions.metadataCacheFile;
        originalTtl = UpdateOptions.metadataCacheTtlMinutes;
        originalSkip = UpdateOptions.skipDownloadWhenMetadataUnchanged;

        UpdateOptions.metadataCacheEnabled = true;
        UpdateOptions.metadataCacheFile = "metadata.json";
        UpdateOptions.metadataCacheTtlMinutes = 0;
        UpdateOptions.skipDownloadWhenMetadataUnchanged = true;
    }

    @After
    public void restoreConfiguration() {
        UpdateOptions.metadataCacheEnabled = originalEnabled;
        UpdateOptions.metadataCacheFile = originalFile;
        UpdateOptions.metadataCacheTtlMinutes = originalTtl;
        UpdateOptions.skipDownloadWhenMetadataUnchanged = originalSkip;
    }

    @Test
    public void emptyCacheLoadsWithoutCreatingAFile() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("empty").toPath();

        UpdateMetadataCache cache = UpdateMetadataCache.open(dataFolder, testLogger());

        assertNull(cache.get("missing"));
        assertFalse(Files.exists(dataFolder.resolve("metadata.json")));
    }

    @Test
    public void putSaveAndReloadUsesValidAtomicJson() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("persisted").toPath();
        UpdateMetadataCache first = UpdateMetadataCache.open(dataFolder, testLogger());
        UpdateMetadataCache.CacheEntry entry = sampleEntry(dataFolder.resolve("Plugin.jar"));
        entry.setHash("sha1", "abc123");

        first.put("Plugin|modrinth|plugin|selector", entry);
        first.save();

        Path cacheFile = dataFolder.resolve("metadata.json");
        assertTrue(Files.isRegularFile(cacheFile));
        assertFalse(Files.exists(dataFolder.resolve("metadata.json.tmp")));
        JsonNode json = new ObjectMapper().readTree(cacheFile.toFile());
        assertEquals(1, json.path("schema").asInt());
        assertEquals("metadata:1", json.path("entries")
                .path("Plugin|modrinth|plugin|selector").path("metadataId").asText());

        UpdateMetadataCache second = UpdateMetadataCache.open(dataFolder, testLogger());
        UpdateMetadataCache.CacheEntry loaded = second.get("Plugin|modrinth|plugin|selector");
        assertNotNull(loaded);
        assertEquals("1.2.3", loaded.version);
        assertEquals("abc123", loaded.getHashes().get("sha1"));
    }

    @Test
    public void skipRequiresMatchingMetadataAndAnExistingTarget() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("skip").toPath();
        Path target = dataFolder.resolve("Plugin.jar");
        Files.write(target, new byte[]{1, 2, 3});
        UpdateMetadataCache cache = UpdateMetadataCache.open(dataFolder, testLogger());
        cache.put("key", sampleEntry(target));

        assertTrue(cache.shouldSkip("key", "metadata:1", target, false));
        assertFalse(cache.shouldSkip("key", "metadata:2", target, false));
        assertFalse(cache.shouldSkip("key", "metadata:1", target, true));
        assertFalse(cache.shouldSkip("key", "metadata:1", target, false, true));

        Files.delete(target);
        assertFalse(cache.shouldSkip("key", "metadata:1", target, false));
    }

    @Test
    public void externalTargetReplacementInvalidatesMatchingMetadata() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("replacement").toPath();
        Path target = dataFolder.resolve("Plugin.jar");
        Files.write(target, "original".getBytes(StandardCharsets.UTF_8));
        UpdateMetadataCache cache = UpdateMetadataCache.open(dataFolder, testLogger());
        UpdateMetadataCache.CacheEntry entry = sampleEntry(target);
        entry.setHash("sha256", sha256(target));
        cache.put("key", entry);

        assertTrue(cache.shouldSkip("key", "metadata:1", target, false));
        Files.write(target, "replaced".getBytes(StandardCharsets.UTF_8));
        assertFalse(cache.shouldSkip("key", "metadata:1", target, false));
    }

    @Test
    public void positiveTtlRejectsOldOrMalformedTimestamps() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("ttl").toPath();
        UpdateMetadataCache cache = UpdateMetadataCache.open(dataFolder, testLogger());
        UpdateMetadataCache.CacheEntry entry = sampleEntry(dataFolder.resolve("Plugin.jar"));

        entry.checkedAt = Instant.now().minusSeconds(3600).toString();
        assertFalse(cache.isFresh(entry, 10));
        entry.checkedAt = Instant.now().toString();
        assertTrue(cache.isFresh(entry, 10));
        entry.checkedAt = "not-an-instant";
        assertFalse(cache.isFresh(entry, 10));
        assertTrue(cache.isFresh(entry, 0));
    }

    @Test
    public void selectorOrderIsStableButSelectorChangesAlterTheKey() {
        Map<String, String> first = new LinkedHashMap<String, String>();
        first.put("loader", "paper");
        first.put("channel", "release");
        Map<String, String> reordered = new LinkedHashMap<String, String>();
        reordered.put("channel", "release");
        reordered.put("loader", "paper");
        Map<String, String> changed = new LinkedHashMap<String, String>(first);
        changed.put("channel", "beta");

        String firstKey = UpdateMetadataCache.buildKey("Plugin", "modrinth", "modrinth:plugin", first);
        String reorderedKey = UpdateMetadataCache.buildKey("Plugin", "modrinth", "modrinth:plugin", reordered);
        String changedKey = UpdateMetadataCache.buildKey("Plugin", "modrinth", "modrinth:plugin", changed);

        assertEquals(firstKey, reorderedKey);
        assertNotEquals(firstKey, changedKey);
    }

    @Test
    public void corruptJsonLogsWarningAndRecoversAsEmpty() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("corrupt").toPath();
        Files.write(dataFolder.resolve("metadata.json"),
                "{ definitely not json".getBytes(StandardCharsets.UTF_8));
        CapturingHandler handler = new CapturingHandler();
        Logger logger = testLogger();
        logger.addHandler(handler);

        UpdateMetadataCache cache = UpdateMetadataCache.open(dataFolder, logger);

        assertNull(cache.get("anything"));
        assertTrue(handler.warningSeen);

        cache.put("recovered", sampleEntry(dataFolder.resolve("Plugin.jar")));
        cache.save();
        assertEquals("metadata:1", new ObjectMapper().readTree(
                dataFolder.resolve("metadata.json").toFile()).path("entries")
                .path("recovered").path("metadataId").asText());
    }

    private static UpdateMetadataCache.CacheEntry sampleEntry(Path target) {
        UpdateMetadataCache.CacheEntry entry = new UpdateMetadataCache.CacheEntry(
                "Plugin", "modrinth", "modrinth:plugin", "metadata:1",
                "1.2.3", target.toString());
        entry.releaseType = "release";
        entry.fileName = "Plugin.jar";
        entry.minecraftVersion = "1.21.8";
        entry.loader = "paper";
        entry.installedAt = Instant.now().toString();
        entry.checkedAt = Instant.now().toString();
        return entry;
    }

    private static Logger testLogger() {
        Logger logger = Logger.getLogger("UpdateMetadataCacheTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        return logger;
    }

    private static String sha256(Path path) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(Files.readAllBytes(path));
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    private static final class CapturingHandler extends Handler {
        private boolean warningSeen;

        @Override
        public void publish(LogRecord record) {
            if (record != null && record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warningSeen = true;
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
