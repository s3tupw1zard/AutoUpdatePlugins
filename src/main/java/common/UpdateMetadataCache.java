package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent provider metadata used to avoid downloading an unchanged
 * artifact merely to compare it with an installed jar.
 */
public final class UpdateMetadataCache {
    private static final int SCHEMA = 1;
    private static final String DEFAULT_FILE = "metadata.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path cachePath;
    private final Logger logger;
    private final Map<String, CacheEntry> entries = new LinkedHashMap<String, CacheEntry>();

    private UpdateMetadataCache(Path cachePath, Logger logger) {
        this.cachePath = cachePath;
        this.logger = logger;
    }

    public static UpdateMetadataCache open(Path dataFolder, Logger logger) {
        UpdateMetadataCache cache = new UpdateMetadataCache(resolvePath(dataFolder, logger), logger);
        cache.load();
        return cache;
    }

    public synchronized CacheEntry get(String key) {
        CacheEntry entry = entries.get(key);
        return entry == null ? null : entry.copy();
    }

    public synchronized void put(String key, CacheEntry entry) {
        if (isBlank(key)) {
            throw new IllegalArgumentException("Cache key must not be blank");
        }
        if (entry == null) {
            entries.remove(key);
        } else {
            entries.put(key, entry.copy());
        }
    }

    public synchronized void remove(String key) {
        if (key != null) {
            entries.remove(key);
        }
    }

    public synchronized void save() {
        if (cachePath == null) {
            return;
        }

        Path parent = cachePath.getParent();
        Path temporary = cachePath.resolveSibling(cachePath.getFileName().toString() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }

            ObjectNode root = JSON.createObjectNode();
            root.put("schema", SCHEMA);
            root.put("writtenAt", Instant.now().toString());
            ObjectNode serializedEntries = root.putObject("entries");
            for (Map.Entry<String, CacheEntry> item : entries.entrySet()) {
                serializedEntries.set(item.getKey(), item.getValue().toJson());
            }

            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            writeFully(temporary, bytes);
            moveIntoPlace(temporary, cachePath);
        } catch (IOException ex) {
            warn("Unable to save metadata cache at " + cachePath, ex);
        }
    }

    /**
     * A zero or negative TTL means entries do not expire.  Positive TTLs are
     * measured from checkedAt, falling back to installedAt for older caches.
     */
    public boolean isFresh(CacheEntry entry, int ttlMinutes) {
        if (entry == null) {
            return false;
        }
        if (ttlMinutes <= 0) {
            return true;
        }

        String timestamp = firstNonBlank(entry.checkedAt, entry.installedAt);
        if (timestamp == null) {
            return false;
        }
        try {
            Instant checked = Instant.parse(timestamp);
            Instant oldestAllowed = Instant.now().minus(ttlMinutes, ChronoUnit.MINUTES);
            return !checked.isBefore(oldestAllowed);
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    public synchronized boolean shouldSkip(String key,
                                           String metadataId,
                                           Path target,
                                           boolean force) {
        return shouldSkip(key, metadataId, target, force, false);
    }

    /**
     * Applies the complete cache-side portion of the payload skip rule.  A
     * caller can request a payload check when its version policy requires a
     * same-version hash comparison.
     */
    public synchronized boolean shouldSkip(String key,
                                           String metadataId,
                                           Path target,
                                           boolean force,
                                           boolean requirePayloadCheck) {
        return shouldSkip(key, metadataId, target, UpdateOptions.metadataCacheEnabled,
                force, requirePayloadCheck);
    }

    /** Variant used when an entry explicitly overrides the global cache enable flag. */
    public synchronized boolean shouldSkip(String key,
                                           String metadataId,
                                           Path target,
                                           boolean cacheEnabled,
                                           boolean force,
                                           boolean requirePayloadCheck) {
        if (!cacheEnabled
                || !UpdateOptions.skipDownloadWhenMetadataUnchanged
                || force
                || requirePayloadCheck
                || isBlank(key)
                || isBlank(metadataId)
                || target == null
                || !Files.isRegularFile(target)) {
            return false;
        }

        CacheEntry entry = entries.get(key);
        return entry != null
                && metadataId.equals(entry.metadataId)
                && isFresh(entry, UpdateOptions.metadataCacheTtlMinutes)
                && targetMatchesCachedPayload(entry, target);
    }

    /**
     * Do not trust a metadata hit after the managed file has been replaced or
     * edited outside AutoUpdatePlugins. Older cache records without a digest
     * remain usable for backwards compatibility and are refreshed on the next
     * normal payload comparison.
     */
    private static boolean targetMatchesCachedPayload(CacheEntry entry, Path target) {
        if (entry == null || entry.hashes == null || entry.hashes.isEmpty()) {
            return true;
        }
        String[][] candidates = {
                {"sha512", "SHA-512"},
                {"sha256", "SHA-256"},
                {"sha1", "SHA-1"},
                {"md5", "MD5"}
        };
        for (String[] candidate : candidates) {
            String expected = entry.hashes.get(candidate[0]);
            if (isBlank(expected)) continue;
            try {
                return expected.trim().equalsIgnoreCase(digest(target, candidate[1]));
            } catch (IOException ex) {
                return false;
            }
        }
        return true;
    }

    private static String digest(Path path, String algorithm) throws IOException {
        final MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("Missing digest algorithm " + algorithm, impossible);
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
            ByteBuffer buffer = ByteBuffer.allocate(65536);
            while (channel.read(buffer) != -1) {
                ((java.nio.Buffer) buffer).flip();
                messageDigest.update(buffer);
                ((java.nio.Buffer) buffer).clear();
            }
        } finally {
            if (channel != null) channel.close();
        }
        StringBuilder value = new StringBuilder();
        for (byte item : messageDigest.digest()) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    public Path getPath() {
        return cachePath;
    }

    /**
     * Builds a stable key whose selector portion is insensitive to map order.
     */
    public static String buildKey(String pluginName,
                                  String provider,
                                  String normalizedSource,
                                  Map<String, String> selectors) {
        TreeMap<String, String> ordered = new TreeMap<String, String>();
        if (selectors != null) {
            ordered.putAll(selectors);
        }
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> selector : ordered.entrySet()) {
            appendLengthPrefixed(canonical, selector.getKey());
            appendLengthPrefixed(canonical, selector.getValue());
        }
        return safePart(pluginName) + "|" + safePart(provider) + "|"
                + safePart(normalizedSource) + "|" + sha256(canonical.toString());
    }

    private synchronized void load() {
        entries.clear();
        if (cachePath == null || !Files.isRegularFile(cachePath)) {
            return;
        }

        try {
            JsonNode root = JSON.readTree(cachePath.toFile());
            if (root == null || !root.isObject()) {
                throw new IOException("root value is not a JSON object");
            }
            int schema = root.path("schema").asInt(SCHEMA);
            if (schema != SCHEMA) {
                throw new IOException("unsupported cache schema " + schema);
            }
            JsonNode serializedEntries = root.path("entries");
            if (serializedEntries.isMissingNode() || serializedEntries.isNull()) {
                return;
            }
            if (!serializedEntries.isObject()) {
                throw new IOException("entries value is not a JSON object");
            }

            Iterator<Map.Entry<String, JsonNode>> fields = serializedEntries.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!isBlank(field.getKey()) && field.getValue().isObject()) {
                    entries.put(field.getKey(), CacheEntry.fromJson(field.getValue()));
                }
            }
        } catch (Exception ex) {
            entries.clear();
            warn("Metadata cache at " + cachePath
                    + " is corrupt or unreadable; starting with an empty cache", ex);
        }
    }

    private static Path resolvePath(Path dataFolder, Logger logger) {
        if (dataFolder == null) {
            return null;
        }
        String configured = UpdateOptions.metadataCacheFile;
        if (isBlank(configured)) {
            configured = DEFAULT_FILE;
        }
        try {
            Path selected = Paths.get(configured.trim());
            return (selected.isAbsolute() ? selected : dataFolder.resolve(selected))
                    .toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "Invalid metadata cache path '" + configured
                        + "'; using " + DEFAULT_FILE, ex);
            }
            return dataFolder.resolve(DEFAULT_FILE).toAbsolutePath().normalize();
        }
    }

    private static void writeFully(Path path, byte[] bytes) throws IOException {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } finally {
            if (channel != null) {
                channel.close();
            }
        }
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(atomicFailure);
                throw fallbackFailure;
            }
        }
    }

    private void warn(String message, Exception ex) {
        if (logger != null) {
            logger.log(Level.WARNING, message, ex);
        }
    }

    private static void appendLengthPrefixed(StringBuilder output, String value) {
        String safe = value == null ? "" : value;
        output.append(safe.length()).append(':').append(safe).append(';');
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                output.append(String.format("%02x", item & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static String safePart(String value) {
        return value == null ? "" : value.trim().replace('|', '_');
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String firstNonBlank(String first, String second) {
        if (!isBlank(first)) {
            return first;
        }
        return isBlank(second) ? null : second;
    }

    public static final class CacheEntry {
        public String pluginName;
        public String provider;
        public String normalizedSource;
        public String metadataId;
        public String version;
        public String releaseType;
        public String fileName;
        public String targetPath;
        public String minecraftVersion;
        public String loader;
        public Map<String, String> hashes = new LinkedHashMap<String, String>();
        public String installedAt;
        public String checkedAt;

        public CacheEntry() {
        }

        public CacheEntry(String pluginName,
                          String provider,
                          String normalizedSource,
                          String metadataId,
                          String version,
                          String targetPath) {
            this.pluginName = pluginName;
            this.provider = provider;
            this.normalizedSource = normalizedSource;
            this.metadataId = metadataId;
            this.version = version;
            this.targetPath = targetPath;
        }

        public Map<String, String> getHashes() {
            return Collections.unmodifiableMap(hashes == null
                    ? Collections.<String, String>emptyMap() : hashes);
        }

        public void setHash(String algorithm, String value) {
            if (hashes == null) {
                hashes = new LinkedHashMap<String, String>();
            }
            if (!isBlank(algorithm) && value != null) {
                hashes.put(algorithm.toLowerCase(), value);
            }
        }

        private CacheEntry copy() {
            CacheEntry copy = new CacheEntry();
            copy.pluginName = pluginName;
            copy.provider = provider;
            copy.normalizedSource = normalizedSource;
            copy.metadataId = metadataId;
            copy.version = version;
            copy.releaseType = releaseType;
            copy.fileName = fileName;
            copy.targetPath = targetPath;
            copy.minecraftVersion = minecraftVersion;
            copy.loader = loader;
            copy.hashes = hashes == null
                    ? new LinkedHashMap<String, String>()
                    : new LinkedHashMap<String, String>(hashes);
            copy.installedAt = installedAt;
            copy.checkedAt = checkedAt;
            return copy;
        }

        private ObjectNode toJson() {
            ObjectNode node = JSON.createObjectNode();
            putText(node, "pluginName", pluginName);
            putText(node, "provider", provider);
            putText(node, "normalizedSource", normalizedSource);
            putText(node, "metadataId", metadataId);
            putText(node, "version", version);
            putText(node, "releaseType", releaseType);
            putText(node, "fileName", fileName);
            putText(node, "targetPath", targetPath);
            putText(node, "minecraftVersion", minecraftVersion);
            putText(node, "loader", loader);
            ObjectNode hashNode = node.putObject("hashes");
            if (hashes != null) {
                for (Map.Entry<String, String> hash : hashes.entrySet()) {
                    if (hash.getKey() != null && hash.getValue() != null) {
                        hashNode.put(hash.getKey(), hash.getValue());
                    }
                }
            }
            putText(node, "installedAt", installedAt);
            putText(node, "checkedAt", checkedAt);
            return node;
        }

        private static CacheEntry fromJson(JsonNode node) {
            CacheEntry entry = new CacheEntry();
            entry.pluginName = text(node, "pluginName");
            entry.provider = text(node, "provider");
            entry.normalizedSource = text(node, "normalizedSource");
            entry.metadataId = text(node, "metadataId");
            entry.version = text(node, "version");
            entry.releaseType = text(node, "releaseType");
            entry.fileName = text(node, "fileName");
            entry.targetPath = text(node, "targetPath");
            entry.minecraftVersion = text(node, "minecraftVersion");
            entry.loader = text(node, "loader");
            JsonNode hashes = node.path("hashes");
            if (hashes.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = hashes.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (field.getValue().isValueNode()) {
                        entry.hashes.put(field.getKey(), field.getValue().asText());
                    }
                }
            }
            entry.installedAt = text(node, "installedAt");
            entry.checkedAt = text(node, "checkedAt");
            return entry;
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() || !value.isValueNode()
                    ? null : value.asText();
        }

        private static void putText(ObjectNode node, String field, String value) {
            if (value != null) {
                node.put(field, value);
            }
        }
    }
}
