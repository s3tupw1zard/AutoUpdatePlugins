package common;

import java.util.Locale;

/** Policy limiting which version transitions may be installed automatically. */
public enum VersionPolicy {
    ANY,
    PATCH,
    SAME_MAJOR,
    NONE;

    public static VersionPolicy parse(String value, VersionPolicy fallback) {
        if (value == null || value.trim().isEmpty()) return fallback == null ? ANY : fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if ("patch".equals(normalized) || "onlypatch".equals(normalized) || "onlyminor".equals(normalized)) return PATCH;
        if ("samemajor".equals(normalized) || "nomajor".equals(normalized)) return SAME_MAJOR;
        if ("none".equals(normalized) || "disabled".equals(normalized) || "off".equals(normalized)) return NONE;
        if ("any".equals(normalized) || "all".equals(normalized) || "allowmajor".equals(normalized)) return ANY;
        return fallback == null ? ANY : fallback;
    }

    static VersionPolicy from(EntryOptions options) {
        VersionPolicy fallback = parse(UpdateOptions.versionPolicyDefault, ANY);
        if (options == null) return fallback;
        String explicit = options.first("versionPolicy", "updatePolicy");
        if (explicit != null) return parse(explicit, fallback);
        if (options.bool("onlyPatch", false) || options.bool("onlyMinor", false)) return PATCH;
        if (options.bool("sameMajor", false)) return SAME_MAJOR;
        if (options.has("allowMajor") && !options.bool("allowMajor", true)) return SAME_MAJOR;
        return fallback;
    }

    public VersionDecision evaluate(String localRaw, String remoteRaw) {
        return evaluate(localRaw, remoteRaw, false);
    }

    public VersionDecision evaluate(String localRaw, String remoteRaw, boolean metadataChanged) {
        LooseVersion local = LooseVersion.parse(localRaw);
        LooseVersion remote = LooseVersion.parse(remoteRaw);
        if (this == NONE) return new VersionDecision(false, "version policy is none", local, remote);

        if (!local.known || !remote.known) {
            boolean blockUnknown = "block".equalsIgnoreCase(UpdateOptions.unknownVersionPolicy);
            if (this != ANY && blockUnknown) {
                return new VersionDecision(false, "unknown version blocked by policy", local, remote);
            }
            return new VersionDecision(true, "unknown version allowed", local, remote);
        }

        if (this == PATCH && (local.major != remote.major || local.minor != remote.minor)) {
            return new VersionDecision(false, "patch policy requires the same major and minor", local, remote);
        }
        if (this == SAME_MAJOR && local.major != remote.major) {
            return new VersionDecision(false, "same-major policy blocks a major update", local, remote);
        }

        int comparison = remote.compareTo(local);
        if (comparison > 0) return new VersionDecision(true, "newer version", local, remote);
        if (comparison < 0) return new VersionDecision(false, "remote version is older", local, remote);
        if (metadataChanged && local.isSnapshotLike() && UpdateOptions.allowSameVersionSnapshotUpdates) {
            return new VersionDecision(true, "same snapshot version has different metadata", local, remote);
        }
        if (metadataChanged && UpdateOptions.allowSameVersionReleaseHashUpdates) {
            return new VersionDecision(true, "same release version has different metadata", local, remote);
        }
        return new VersionDecision(false, "version is unchanged", local, remote);
    }
}
