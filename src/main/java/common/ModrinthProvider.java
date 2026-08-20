package common;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class ModrinthProvider {
    private static final int MAX_RELEASE_NOTES = 128;
    private final String apiBase;

    ModrinthProvider() {
        this("https://api.modrinth.com/v2");
    }

    ModrinthProvider(String apiBase) {
        this.apiBase = trimSlash(apiBase);
    }

    ResolvedUpdate resolve(String pluginName, EntryOptions options, ServerEnvironment environment) throws IOException {
        String project = projectSlug(options.sourceWithoutQuery);
        if (project == null) throw new IOException("Invalid Modrinth project URL");

        String loader = first(options.first("loader", "platform"), environment == null ? null : environment.platform);
        String minecraftVersion = first(options.first("mcVersion", "gameVersion", "minecraftVersion"),
                environment == null ? null : environment.minecraftVersion);
        boolean versionCheck = options.has("versionCheck")
                ? options.bool("versionCheck", true)
                : UpdateOptions.modrinthMinecraftVersionCheck;
        Pattern get = pattern(options.first("get"));

        String endpoint = apiBase + "/project/" + ProviderSupport.encode(project)
                + "/version?include_changelog=true";
        JsonNode versions = ProviderSupport.getJson(endpoint);
        if (versions != null && versions.isArray()) {
            JsonNode selectedVersion = null;
            JsonNode selectedFile = null;
            List<ResolvedUpdate.ReleaseNote> releaseNotes = new ArrayList<ResolvedUpdate.ReleaseNote>();
            boolean releaseNotesComplete = true;
            for (JsonNode version : orderedVersions(versions)) {
                if (!releaseAllowed(version, options)) continue;
                if (!loaderMatches(version.path("loaders"), loader)) continue;
                if (versionCheck && notBlank(minecraftVersion)
                        && !gameVersionMatches(version.path("game_versions"), minecraftVersion)) continue;
                JsonNode file = selectFile(version.path("files"), get, loader);
                if (file == null) continue;

                if (selectedVersion == null) {
                    selectedVersion = version;
                    selectedFile = file;
                }
                String versionLabel = ProviderSupport.text(version, "version_number");
                String changelog = ProviderSupport.text(version, "changelog");
                if (notBlank(versionLabel) && notBlank(changelog)) {
                    ResolvedUpdate.ReleaseNote note = new ResolvedUpdate.ReleaseNote(
                            ProviderSupport.text(version, "id"),
                            versionLabel,
                            ProviderSupport.text(version, "version_type"),
                            ProviderSupport.instantMillis(ProviderSupport.text(version, "date_published")),
                            changelog);
                    if (note.changelogTruncated) releaseNotesComplete = false;
                    if (releaseNotes.size() < MAX_RELEASE_NOTES) releaseNotes.add(note);
                    else releaseNotesComplete = false;
                }
            }
            if (selectedVersion != null) {
                return resolved(pluginName, project, loader, selectedVersion, selectedFile,
                        releaseNotes, releaseNotesComplete);
            }
        }
        throw new CompatibilityException("No compatible Modrinth version found for " + project
                + compatibilitySuffix(loader, minecraftVersion, versionCheck));
    }

    private static ResolvedUpdate resolved(String pluginName,
                                           String project,
                                           String loader,
                                           JsonNode version,
                                           JsonNode file,
                                           List<ResolvedUpdate.ReleaseNote> releaseNotes,
                                           boolean releaseNotesComplete) {
        List<String> loaders = ProviderSupport.strings(version.path("loaders"));
        List<String> gameVersions = ProviderSupport.strings(version.path("game_versions"));
        JsonNode hashes = file.path("hashes");
        return ResolvedUpdate.builder(pluginName, "modrinth", ProviderSupport.text(file, "url"))
                .normalizedSource("modrinth:" + project)
                .projectId(first(ProviderSupport.text(version, "project_id"), project))
                .versionId(ProviderSupport.text(version, "id"))
                .versionLabel(ProviderSupport.text(version, "version_number"))
                .releaseType(ProviderSupport.text(version, "version_type"))
                .publishedAtMillis(ProviderSupport.instantMillis(ProviderSupport.text(version, "date_published")))
                .fileName(ProviderSupport.text(file, "filename"))
                .sha1(ProviderSupport.text(hashes, "sha1"))
                .sha512(ProviderSupport.text(hashes, "sha512"))
                .size(file.path("size").asLong(-1L))
                .gameVersions(gameVersions)
                .loaders(loaders)
                .platform(loader)
                .changelog(ProviderSupport.text(version, "changelog"))
                .releaseNotes(releaseNotes)
                .releaseNotesComplete(releaseNotesComplete)
                .build();
    }

    private static List<JsonNode> orderedVersions(JsonNode versions) {
        List<JsonNode> ordered = new ArrayList<JsonNode>();
        for (JsonNode version : versions) {
            if (version != null && version.isObject()) ordered.add(version);
        }
        Collections.sort(ordered, new Comparator<JsonNode>() {
            @Override
            public int compare(JsonNode left, JsonNode right) {
                long leftTime = ProviderSupport.instantMillis(
                        ProviderSupport.text(left, "date_published"));
                long rightTime = ProviderSupport.instantMillis(
                        ProviderSupport.text(right, "date_published"));
                int byTime = Long.compare(rightTime, leftTime);
                if (byTime != 0) return byTime;
                int byId = safe(ProviderSupport.text(right, "id"))
                        .compareTo(safe(ProviderSupport.text(left, "id")));
                if (byId != 0) return byId;
                int byVersion = safe(ProviderSupport.text(right, "version_number"))
                        .compareTo(safe(ProviderSupport.text(left, "version_number")));
                if (byVersion != 0) return byVersion;
                return safe(ProviderSupport.text(right, "changelog"))
                        .compareTo(safe(ProviderSupport.text(left, "changelog")));
            }
        });
        List<JsonNode> deduplicated = new ArrayList<JsonNode>();
        Set<String> seenIds = new HashSet<String>();
        for (JsonNode version : ordered) {
            String id = ProviderSupport.text(version, "id");
            if (id != null && !seenIds.add(id)) continue;
            deduplicated.add(version);
        }
        return deduplicated;
    }

    private boolean releaseAllowed(JsonNode version, EntryOptions options) {
        String type = lower(ProviderSupport.text(version, "version_type"));
        if (type.isEmpty()) type = "release";
        String channel = lower(options.first("channel"));
        if (!channel.isEmpty()) {
            if ("latest".equals(channel)) return true;
            if ("stable".equals(channel)) channel = "release";
            if ("prerelease".equals(channel) || "pre-release".equals(channel) || "preview".equals(channel)) channel = "beta";
            if ("snapshot".equals(channel) || "nightly".equals(channel)) channel = "alpha";
            return channel.equals(type);
        }
        if ("release".equals(type)) return true;
        if (options.bool("latest", false) || options.bool("prerelease", false) || options.bool("pre-release", false)) return true;
        if ("beta".equals(type) && options.bool("beta", UpdateOptions.allowPreReleaseDefault)) return true;
        if ("alpha".equals(type) && options.bool("alpha", UpdateOptions.allowPreReleaseDefault)) return true;
        return UpdateOptions.allowPreReleaseDefault && !"release".equals(type);
    }

    static boolean loaderMatches(JsonNode loaders, String platform) {
        if (!notBlank(platform) || loaders == null || !loaders.isArray() || loaders.size() == 0) return true;
        List<String> accepted = acceptedLoaders(platform);
        for (JsonNode item : loaders) {
            String loader = lower(item.asText(""));
            if (accepted.contains(loader)) return true;
        }
        return false;
    }

    static boolean gameVersionMatches(JsonNode versions, String minecraftVersion) {
        if (versions == null || !versions.isArray() || versions.size() == 0) {
            return !UpdateOptions.strictMinecraftVersionMetadata;
        }
        for (JsonNode version : versions) {
            if (minecraftVersion.equalsIgnoreCase(version.asText(""))) return true;
        }
        return false;
    }

    private JsonNode selectFile(JsonNode files, Pattern get, String platform) {
        if (files == null || !files.isArray()) return null;
        JsonNode best = null;
        int bestScore = Integer.MIN_VALUE;
        for (JsonNode file : files) {
            String name = first(ProviderSupport.text(file, "filename"), "");
            if (ProviderSupport.text(file, "url") == null) continue;
            if (get != null) {
                if (get.matcher(name).find()) return file;
                continue;
            }
            int score = file.path("primary").asBoolean(false) ? 1000 : 0;
            String lower = name.toLowerCase(Locale.ROOT);
            for (String accepted : acceptedLoaders(platform)) if (lower.contains(accepted)) score += 25;
            if (lower.contains("sources") || lower.contains("javadoc") || lower.contains("api")) score -= 500;
            if (score > bestScore) {
                bestScore = score;
                best = file;
            }
        }
        return best;
    }

    private static List<String> acceptedLoaders(String platform) {
        String value = lower(platform);
        List<String> accepted = new ArrayList<>();
        if (value.contains("purpur")) add(accepted, "purpur", "paper", "spigot", "bukkit");
        else if (value.contains("paper")) add(accepted, "paper", "spigot", "bukkit");
        else if (value.contains("folia")) add(accepted, "folia", "paper");
        else if (value.contains("spigot")) add(accepted, "spigot", "bukkit");
        else if (value.contains("bukkit")) add(accepted, "bukkit", "spigot");
        else if (value.contains("waterfall") || value.contains("bungee")) add(accepted, "waterfall", "bungeecord", "bungee");
        else add(accepted, value);
        return accepted;
    }

    private static void add(List<String> list, String... values) {
        for (String value : values) list.add(value);
    }

    private static Pattern pattern(String value) throws IOException {
        if (!notBlank(value)) return null;
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new IOException("Invalid get regex: " + e.getMessage(), e);
        }
    }

    private static String projectSlug(String source) {
        if (!notBlank(source)) return null;
        String value = source.replaceAll("/+$", "");
        int slash = value.lastIndexOf('/');
        String slug = slash >= 0 ? value.substring(slash + 1) : value;
        return slug.trim().isEmpty() ? null : slug.trim();
    }

    private static String compatibilitySuffix(String loader, String mc, boolean check) {
        return " (loader=" + first(loader, "any") + (check && notBlank(mc) ? ", minecraft=" + mc : "") + ")";
    }

    private static String trimSlash(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
    private static String lower(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static boolean notBlank(String value) { return value != null && !value.trim().isEmpty(); }
    private static String first(String first, String fallback) { return notBlank(first) ? first.trim() : fallback; }
}
