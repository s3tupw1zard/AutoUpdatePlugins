package common;

import java.time.Instant;

/**
 * Immutable description of a decision made while checking or applying an
 * update.  The event deliberately contains only strings so it can represent
 * metadata from every provider without coupling the history log to a provider
 * implementation.
 */
public final class UpdateEvent {
    public enum Type {
        APPLIED,
        AVAILABLE,
        SKIPPED,
        BLOCKED,
        FAILED
    }

    public final Instant time;
    public final Type type;
    public final String pluginName;
    public final String provider;
    public final String oldVersion;
    public final String newVersion;
    public final String oldHash;
    public final String newHash;
    public final String targetPath;
    public final String metadataId;
    public final String reason;

    public UpdateEvent(Instant time,
                       Type type,
                       String pluginName,
                       String provider,
                       String oldVersion,
                       String newVersion,
                       String oldHash,
                       String newHash,
                       String targetPath,
                       String metadataId,
                       String reason) {
        this.time = time == null ? Instant.now() : time;
        this.type = type == null ? Type.FAILED : type;
        this.pluginName = pluginName;
        this.provider = provider;
        this.oldVersion = oldVersion;
        this.newVersion = newVersion;
        this.oldHash = oldHash;
        this.newHash = newHash;
        this.targetPath = targetPath;
        this.metadataId = metadataId;
        this.reason = reason;
    }

    public UpdateEvent(Type type,
                       String pluginName,
                       String provider,
                       String oldVersion,
                       String newVersion,
                       String reason) {
        this(Instant.now(), type, pluginName, provider, oldVersion, newVersion,
                null, null, null, null, reason);
    }

    public static Builder builder(Type type, String pluginName) {
        return new Builder(type, pluginName);
    }

    public static final class Builder {
        private Instant time;
        private final Type type;
        private final String pluginName;
        private String provider;
        private String oldVersion;
        private String newVersion;
        private String oldHash;
        private String newHash;
        private String targetPath;
        private String metadataId;
        private String reason;

        private Builder(Type type, String pluginName) {
            this.type = type;
            this.pluginName = pluginName;
        }

        public Builder time(Instant value) {
            this.time = value;
            return this;
        }

        public Builder provider(String value) {
            this.provider = value;
            return this;
        }

        public Builder versions(String oldValue, String newValue) {
            this.oldVersion = oldValue;
            this.newVersion = newValue;
            return this;
        }

        public Builder hashes(String oldValue, String newValue) {
            this.oldHash = oldValue;
            this.newHash = newValue;
            return this;
        }

        public Builder targetPath(String value) {
            this.targetPath = value;
            return this;
        }

        public Builder metadataId(String value) {
            this.metadataId = value;
            return this;
        }

        public Builder reason(String value) {
            this.reason = value;
            return this;
        }

        public UpdateEvent build() {
            return new UpdateEvent(time, type, pluginName, provider,
                    oldVersion, newVersion, oldHash, newHash, targetPath,
                    metadataId, reason);
        }
    }
}
