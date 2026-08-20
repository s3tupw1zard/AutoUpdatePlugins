package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Metadata and content hashes read from an installed or downloaded plugin jar. */
public final class JarMetadata {
    private static final ObjectMapper JSON = new ObjectMapper();

    public final String name;
    public final String id;
    public final String version;
    public final String sha1;
    public final String sha256;
    public final String md5;
    public final long size;

    public JarMetadata(String name, String id, String version, String sha1, String sha256, String md5, long size) {
        this.name = blankToNull(name);
        this.id = blankToNull(id);
        this.version = blankToNull(version);
        this.sha1 = blankToNull(sha1);
        this.sha256 = blankToNull(sha256);
        this.md5 = blankToNull(md5);
        this.size = size;
    }

    public static JarMetadata read(Path jar) throws IOException {
        if (jar == null || !Files.isRegularFile(jar)) return null;
        String name = null;
        String id = null;
        String version = null;
        try (JarFile file = new JarFile(jar.toFile())) {
            Metadata values = readYaml(file, "plugin.yml");
            if (values == null) values = readYaml(file, "bungee.yml");
            if (values != null) {
                name = values.name;
                version = values.version;
            }
            JarEntry velocity = file.getJarEntry("velocity-plugin.json");
            if (velocity != null) {
                try (InputStream in = file.getInputStream(velocity)) {
                    JsonNode node = JSON.readTree(in);
                    if (node != null) {
                        id = text(node, "id");
                        if (name == null) name = text(node, "name");
                        if (version == null) version = text(node, "version");
                    }
                }
            }
        }
        return new JarMetadata(name, id, version, sha1(jar), digest(jar, "SHA-256"), md5(jar), Files.size(jar));
    }

    public static String sha1(Path file) throws IOException {
        return digest(file, "SHA-1");
    }

    public static String md5(Path file) throws IOException {
        return digest(file, "MD5");
    }

    private static Metadata readYaml(JarFile jar, String entryName) {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) return null;
        try (InputStream in = jar.getInputStream(entry)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) return null;
            Map<?, ?> map = (Map<?, ?>) loaded;
            return new Metadata(string(map.get("name")), string(map.get("version")));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String digest(Path file, String algorithm) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("Missing digest algorithm " + algorithm, impossible);
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 65536);
             DigestInputStream stream = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[65536];
            while (stream.read(buffer) != -1) {
                // DigestInputStream updates the digest.
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : blankToNull(value.asText());
    }

    private static String string(Object value) {
        return value == null ? null : blankToNull(String.valueOf(value));
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class Metadata {
        private final String name;
        private final String version;

        private Metadata(String name, String version) {
            this.name = name;
            this.version = version;
        }
    }
}
