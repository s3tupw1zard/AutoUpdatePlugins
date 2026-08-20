package common;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

final class ExtendedClipProvider {
    private final String apiEndpoint;

    ExtendedClipProvider() {
        this("https://ecloud.placeholderapi.com/api/v3/");
    }

    ExtendedClipProvider(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint == null ? "" : apiEndpoint;
    }

    ResolvedUpdate resolve(String pluginName, EntryOptions options) throws IOException {
        String slug = slug(options.sourceWithoutQuery);
        if (slug == null) throw new IOException("Invalid eCloud expansion URL");
        String separator = apiEndpoint.contains("?") ? "&" : "?";
        JsonNode root = ProviderSupport.getJson(apiEndpoint + separator + "platform=bukkit&name=" + ProviderSupport.encode(slug));
        JsonNode expansion = findExpansion(root, slug);
        if (expansion == null || expansion.isMissingNode()) throw new IOException("eCloud expansion not found: " + slug);

        String latest = ProviderSupport.text(expansion, "latest_version");
        JsonNode selected = null;
        JsonNode versions = expansion.path("versions");
        if (versions.isArray()) {
            for (JsonNode version : versions) {
                if (version.has("verified") && !version.path("verified").asBoolean(false)) continue;
                if (latest != null && latest.equals(ProviderSupport.text(version, "version"))) {
                    selected = version;
                    break;
                }
                if (selected == null) selected = version;
            }
        }
        if (selected == null) throw new IOException("No verified eCloud release found for " + slug);
        String download = ProviderSupport.text(selected, "url");
        if (download == null) throw new IOException("eCloud release has no download URL for " + slug);
        String version = first(ProviderSupport.text(selected, "version"), latest);
        long updated = selected.path("created_at").asLong(expansion.path("last_update").asLong(0L));
        String remoteName = fileName(download, slug);

        return ResolvedUpdate.builder(pluginName, "extendedclip", download)
                .normalizedSource("extendedclip:" + slug.toLowerCase(Locale.ROOT))
                .projectId(slug.toLowerCase(Locale.ROOT))
                .versionId(version)
                .versionLabel(version)
                .publishedAtMillis(updated)
                .fileName(remoteName)
                .platform("bukkit")
                .metadataId("extendedclip:" + slug.toLowerCase(Locale.ROOT) + ":" + first(version, String.valueOf(updated)) + ":" + updated)
                .changelog(ProviderSupport.text(selected, "release_notes"))
                .build();
    }

    static boolean recognizes(String value) {
        String source = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return source.contains("api.extendedclip.com/expansions/")
                || source.contains("placeholderapi.com/ecloud/expansions/")
                || source.contains("ecloud.placeholderapi.com/expansions/");
    }

    private JsonNode findExpansion(JsonNode root, String slug) {
        if (root == null || !root.isObject()) return null;
        JsonNode exact = root.get(slug);
        if (exact != null) return exact;
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase(slug)) return field.getValue();
        }
        return null;
    }

    private static String slug(String source) {
        if (source == null) return null;
        String value = source.replaceAll("/+$", "");
        int slash = value.lastIndexOf('/');
        String slug = slash < 0 ? value : value.substring(slash + 1);
        if (slug.isEmpty() || "expansions".equalsIgnoreCase(slug) || "all".equalsIgnoreCase(slug)
                || "usage".equalsIgnoreCase(slug)) return null;
        return slug;
    }

    private static String fileName(String url, String slug) {
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int query = name.indexOf('?');
        if (query >= 0) name = name.substring(0, query);
        return name.toLowerCase(Locale.ROOT).endsWith(".jar") ? name : slug + ".jar";
    }

    private static String first(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
