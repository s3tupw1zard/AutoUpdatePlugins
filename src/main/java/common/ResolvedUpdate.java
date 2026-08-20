package common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Lightweight provider metadata resolved before a potentially large payload download. */
public final class ResolvedUpdate {
    private static final int MAX_RELEASE_NOTE_CHARACTERS = 8000;
    private static final List<String> SELECTOR_KEYS = Arrays.asList(
            "get", "artifact", "asset", "file", "index", "zip", "loader", "platform", "channel",
            "alpha", "beta", "latest", "prerelease", "pre-release", "branch",
            "versioncheck", "mcversion", "gameversion", "minecraftversion");

    public final String pluginName;
    public final String provider;
    public final String normalizedSource;
    public final String downloadUrl;
    public final String projectId;
    public final String versionId;
    public final String versionLabel;
    public final String releaseType;
    public final long publishedAtMillis;
    public final String fileName;
    public final String sha1;
    public final String sha256;
    public final String sha512;
    public final String md5;
    public final long size;
    public final List<String> gameVersions;
    public final List<String> loaders;
    public final String platform;
    public final String changelog;
    public final List<ReleaseNote> releaseNotes;
    public final boolean releaseNotesComplete;
    private final String explicitMetadataId;

    private ResolvedUpdate(Builder builder) {
        pluginName = clean(builder.pluginName);
        provider = clean(builder.provider);
        normalizedSource = clean(builder.normalizedSource);
        downloadUrl = clean(builder.downloadUrl);
        projectId = clean(builder.projectId);
        versionId = clean(builder.versionId);
        versionLabel = clean(builder.versionLabel);
        releaseType = clean(builder.releaseType);
        publishedAtMillis = builder.publishedAtMillis;
        fileName = clean(builder.fileName);
        sha1 = clean(builder.sha1);
        sha256 = clean(builder.sha256);
        sha512 = clean(builder.sha512);
        md5 = clean(builder.md5);
        size = builder.size;
        gameVersions = immutable(builder.gameVersions);
        loaders = immutable(builder.loaders);
        platform = clean(builder.platform);
        changelog = clean(builder.changelog);
        releaseNotes = immutableReleaseNotes(builder.releaseNotes);
        boolean notesComplete = builder.releaseNotesComplete;
        for (ReleaseNote note : releaseNotes) {
            if (note.changelogTruncated) {
                notesComplete = false;
                break;
            }
        }
        releaseNotesComplete = notesComplete;
        explicitMetadataId = clean(builder.metadataId);
    }

    public static Builder builder(String pluginName, String provider, String downloadUrl) {
        return new Builder(pluginName, provider, downloadUrl);
    }

    public String metadataId() {
        if (explicitMetadataId != null) return explicitMetadataId;
        if (versionId != null) {
            return join(projectId, versionId, firstNonBlank(sha1, sha256, sha512, md5, fileName));
        }
        if (versionLabel != null && projectId != null) {
            return join(projectId, versionLabel, platform, firstNonBlank(sha1, sha256, sha512, md5, fileName));
        }
        return null;
    }

    String cacheKey(String entryName, EntryOptions options) {
        StringBuilder selectors = new StringBuilder();
        if (options != null) {
            for (String key : SELECTOR_KEYS) {
                List<String> values = options.queryParams.get(key);
                if (values == null) continue;
                for (String value : values) selectors.append(key).append('=').append(value).append('&');
            }
            selectors.append("path=").append(safe(options.pathTail));
        }
        String material = safe(entryName) + '|' + safe(provider).toLowerCase(Locale.ROOT) + '|'
                + safe(normalizedSource) + '|' + selectors;
        return material + "|selector=" + sha256(selectors.toString()).substring(0, 16);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            if (result.length() > 0) result.append(':');
            result.append(value);
        }
        return result.length() == 0 ? null : result.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (clean(value) != null) return clean(value);
        return null;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<ReleaseNote> immutableReleaseNotes(List<ReleaseNote> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<ReleaseNote> copy = new ArrayList<ReleaseNote>();
        for (ReleaseNote value : values) {
            if (value != null) copy.add(value);
        }
        return copy.isEmpty() ? Collections.<ReleaseNote>emptyList()
                : Collections.unmodifiableList(copy);
    }

    /** Immutable release metadata used to construct a bounded multi-version changelog. */
    public static final class ReleaseNote {
        public final String versionId;
        public final String versionLabel;
        public final String releaseType;
        public final long publishedAtMillis;
        public final String changelog;
        public final boolean changelogTruncated;

        public ReleaseNote(String versionId,
                           String versionLabel,
                           String releaseType,
                           long publishedAtMillis,
                           String changelog) {
            this.versionId = clean(versionId);
            this.versionLabel = clean(versionLabel);
            this.releaseType = clean(releaseType);
            this.publishedAtMillis = Math.max(0L, publishedAtMillis);
            String cleaned = clean(changelog);
            this.changelogTruncated = cleaned != null
                    && cleaned.length() > MAX_RELEASE_NOTE_CHARACTERS;
            this.changelog = cleaned == null || cleaned.length() <= MAX_RELEASE_NOTE_CHARACTERS
                    ? cleaned : cleaned.substring(0, MAX_RELEASE_NOTE_CHARACTERS - 3) + "...";
        }
    }

    public static final class Builder {
        private final String pluginName;
        private final String provider;
        private final String downloadUrl;
        private String normalizedSource;
        private String projectId;
        private String versionId;
        private String versionLabel;
        private String releaseType;
        private long publishedAtMillis;
        private String fileName;
        private String sha1;
        private String sha256;
        private String sha512;
        private String md5;
        private long size = -1L;
        private List<String> gameVersions;
        private List<String> loaders;
        private String platform;
        private String changelog;
        private List<ReleaseNote> releaseNotes;
        private boolean releaseNotesComplete = true;
        private String metadataId;

        private Builder(String pluginName, String provider, String downloadUrl) {
            this.pluginName = pluginName;
            this.provider = provider;
            this.downloadUrl = downloadUrl;
        }

        public Builder normalizedSource(String value) { normalizedSource = value; return this; }
        public Builder projectId(String value) { projectId = value; return this; }
        public Builder versionId(String value) { versionId = value; return this; }
        public Builder versionLabel(String value) { versionLabel = value; return this; }
        public Builder releaseType(String value) { releaseType = value; return this; }
        public Builder publishedAtMillis(long value) { publishedAtMillis = value; return this; }
        public Builder fileName(String value) { fileName = value; return this; }
        public Builder sha1(String value) { sha1 = value; return this; }
        public Builder sha256(String value) { sha256 = value; return this; }
        public Builder sha512(String value) { sha512 = value; return this; }
        public Builder md5(String value) { md5 = value; return this; }
        public Builder size(long value) { size = value; return this; }
        public Builder gameVersions(List<String> value) { gameVersions = value; return this; }
        public Builder loaders(List<String> value) { loaders = value; return this; }
        public Builder platform(String value) { platform = value; return this; }
        public Builder changelog(String value) { changelog = value; return this; }
        public Builder releaseNotes(List<ReleaseNote> value) { releaseNotes = value; return this; }
        public Builder releaseNotesComplete(boolean value) { releaseNotesComplete = value; return this; }
        public Builder metadataId(String value) { metadataId = value; return this; }
        public ResolvedUpdate build() { return new ResolvedUpdate(this); }
    }
}
