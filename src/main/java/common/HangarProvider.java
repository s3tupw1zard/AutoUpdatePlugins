package common;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class HangarProvider {
    private final String apiBase;

    HangarProvider() {
        this("https://hangar.papermc.io/api/v1");
    }

    HangarProvider(String apiBase) {
        this.apiBase = apiBase == null ? "" : apiBase.replaceAll("/+$", "");
    }

    ResolvedUpdate resolve(String pluginName, EntryOptions options, ServerEnvironment environment) throws IOException {
        String project = projectPath(options.sourceWithoutQuery);
        if (project == null) throw new IOException("Invalid Hangar project URL");
        String platform = normalizePlatform(first(options.first("loader", "platform"), environment == null ? null : environment.platform));
        String mcVersion = first(options.first("mcVersion", "gameVersion", "minecraftVersion"),
                environment == null ? null : environment.minecraftVersion);
        boolean versionCheck = options.has("versionCheck")
                ? options.bool("versionCheck", true)
                : UpdateOptions.hangarMinecraftVersionCheck;

        int offset = 0;
        while (offset < 500) {
            String endpoint = apiBase + "/projects/" + encodedProject(project) + "/versions?limit=25&offset=" + offset;
            JsonNode root = ProviderSupport.getJson(endpoint);
            JsonNode versions = root == null ? null : root.path("result");
            if (versions == null || !versions.isArray() || versions.size() == 0) break;
            for (JsonNode version : versions) {
                if (!releaseAllowed(version, options)) continue;
                JsonNode download = version.path("downloads").path(platform);
                String downloadUrl = first(ProviderSupport.text(download, "downloadUrl"), ProviderSupport.text(download, "externalUrl"));
                if (!notBlank(downloadUrl)) continue;
                JsonNode dependencies = version.path("platformDependencies").path(platform);
                if (versionCheck && notBlank(mcVersion) && !dependencyMatches(dependencies, mcVersion)) continue;

                JsonNode file = download.path("fileInfo");
                String versionId = version.path("id").isMissingNode() ? null : version.path("id").asText(null);
                String versionName = ProviderSupport.text(version, "name");
                String sha256 = ProviderSupport.text(file, "sha256Hash");
                return ResolvedUpdate.builder(pluginName, "hangar", downloadUrl)
                        .normalizedSource("hangar:" + project)
                        .projectId(project)
                        .versionId(versionId)
                        .versionLabel(versionName)
                        .releaseType(ProviderSupport.text(version.path("channel"), "name"))
                        .publishedAtMillis(ProviderSupport.instantMillis(ProviderSupport.text(version, "createdAt")))
                        .fileName(ProviderSupport.text(file, "name"))
                        .sha256(sha256)
                        .size(file.path("sizeBytes").asLong(-1L))
                        .gameVersions(ProviderSupport.strings(dependencies))
                        .loaders(Collections.singletonList(platform.toLowerCase(Locale.ROOT)))
                        .platform(platform.toLowerCase(Locale.ROOT))
                        .metadataId(project + ":" + first(versionId, versionName) + ":" + platform + ":" + first(sha256, ProviderSupport.text(file, "name")))
                        .changelog(ProviderSupport.text(version, "description"))
                        .build();
            }
            offset += versions.size();
        }
        throw new CompatibilityException("No compatible Hangar version found for " + project
                + " (platform=" + platform + (versionCheck && notBlank(mcVersion) ? ", minecraft=" + mcVersion : "") + ")");
    }

    static boolean dependencyMatches(JsonNode dependencies, String version) {
        if (dependencies == null || !dependencies.isArray() || dependencies.size() == 0) {
            return !UpdateOptions.strictMinecraftVersionMetadata;
        }
        for (JsonNode dependency : dependencies) {
            if (version.equalsIgnoreCase(dependency.asText(""))) return true;
        }
        return false;
    }

    private boolean releaseAllowed(JsonNode version, EntryOptions options) {
        String channel = lower(ProviderSupport.text(version.path("channel"), "name"));
        String wanted = lower(options.first("channel"));
        if (!wanted.isEmpty()) {
            if ("latest".equals(wanted)) return true;
            if ("stable".equals(wanted)) wanted = "release";
            if ("snapshot".equals(wanted) || "nightly".equals(wanted)) wanted = "alpha";
            if ("preview".equals(wanted) || "prerelease".equals(wanted)) wanted = "beta";
            return channel.contains(wanted);
        }
        boolean release = channel.contains("release") || channel.contains("stable") || channel.contains("default");
        if (release) return true;
        if (options.bool("latest", false) || options.bool("prerelease", false) || options.bool("pre-release", false)) return true;
        if ((channel.contains("beta") || channel.contains("rc") || channel.contains("pre")) && options.bool("beta", UpdateOptions.allowPreReleaseDefault)) return true;
        if ((channel.contains("alpha") || channel.contains("snapshot") || channel.contains("nightly")) && options.bool("alpha", UpdateOptions.allowPreReleaseDefault)) return true;
        return UpdateOptions.allowPreReleaseDefault;
    }

    private static String projectPath(String source) {
        if (!notBlank(source)) return null;
        String marker = "hangar.papermc.io/";
        int start = source.toLowerCase(Locale.ROOT).indexOf(marker);
        String path = start >= 0 ? source.substring(start + marker.length()) : source;
        path = path.replaceAll("^/+|/+$", "");
        String[] parts = path.split("/");
        return parts.length < 2 ? null : parts[0] + "/" + parts[1];
    }

    private static String encodedProject(String project) throws IOException {
        String[] parts = project.split("/", 2);
        return ProviderSupport.encode(parts[0]) + "/" + ProviderSupport.encode(parts[1]);
    }

    private static String normalizePlatform(String value) {
        String platform = lower(value);
        if (platform.contains("bungee") || platform.contains("waterfall")) return "WATERFALL";
        if (platform.contains("velocity")) return "VELOCITY";
        if (platform.contains("fabric")) return "FABRIC";
        if (platform.contains("sponge")) return "SPONGE";
        return "PAPER";
    }

    private static String lower(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static boolean notBlank(String value) { return value != null && !value.trim().isEmpty(); }
    private static String first(String first, String fallback) { return notBlank(first) ? first.trim() : fallback; }
}
