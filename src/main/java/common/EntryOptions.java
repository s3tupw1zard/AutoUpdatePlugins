package common;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Parsed, immutable options for one {@code list.yml} entry. */
final class EntryOptions {
    final String rawValue;
    final String sourceValue;
    final String sourceWithoutQuery;
    final String query;
    final String pathTail;
    final EntryPathOptions pathOptions;
    final Map<String, List<String>> queryParams;

    private EntryOptions(String rawValue,
                         String sourceValue,
                         String sourceWithoutQuery,
                         String query,
                         String pathTail,
                         EntryPathOptions pathOptions,
                         Map<String, List<String>> queryParams) {
        this.rawValue = rawValue;
        this.sourceValue = sourceValue;
        this.sourceWithoutQuery = sourceWithoutQuery;
        this.query = query;
        this.pathTail = pathTail;
        this.pathOptions = pathOptions;
        this.queryParams = queryParams;
    }

    static EntryOptions parse(String value, Logger logger) {
        String raw = value == null ? "" : ListEntryLoader.normalizeValue(value).trim();
        int pipe = pathDelimiter(raw);
        String source = pipe < 0 ? raw : raw.substring(0, pipe).trim();
        String tail = pipe < 0 ? null : raw.substring(pipe + 1).trim();

        int question = source.indexOf('?');
        int legacyQuery = question < 0 ? legacyQueryDelimiter(source) : -1;
        int queryDelimiter = question >= 0 ? question : legacyQuery;
        String withoutQuery = queryDelimiter < 0 ? source : source.substring(0, queryDelimiter);
        String query = queryDelimiter < 0 || queryDelimiter == source.length() - 1
                ? ""
                : source.substring(queryDelimiter + 1);
        if (legacyQuery >= 0) {
            source = withoutQuery + "?" + query;
        }

        Map<String, List<String>> params = new LinkedHashMap<>();
        if (!query.isEmpty()) {
            for (String component : query.split("&")) {
                if (component == null || component.isEmpty()) continue;
                int equals = component.indexOf('=');
                String rawKey = equals < 0 ? component : component.substring(0, equals);
                String rawParam = equals < 0 ? "" : component.substring(equals + 1);
                String key = decode(rawKey).trim().toLowerCase(Locale.ROOT);
                if (key.isEmpty()) continue;
                List<String> values = params.get(key);
                if (values == null) {
                    values = new ArrayList<>();
                    params.put(key, values);
                }
                values.add(decode(rawParam));
            }
        }

        Map<String, List<String>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return new EntryOptions(
                raw,
                source,
                withoutQuery,
                query,
                tail,
                EntryPathOptions.parse(tail, logger),
                Collections.unmodifiableMap(immutable));
    }

    String first(String... keys) {
        if (keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            List<String> values = queryParams.get(key.toLowerCase(Locale.ROOT));
            if (values == null || values.isEmpty()) continue;
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) return value.trim();
            }
            return values.get(0);
        }
        return null;
    }

    boolean bool(String key, boolean fallback) {
        String value = first(key);
        if (value == null) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "yes".equals(normalized)
                || "on".equals(normalized) || "1".equals(normalized)) return true;
        if ("false".equals(normalized) || "no".equals(normalized)
                || "off".equals(normalized) || "0".equals(normalized)) return false;
        return fallback;
    }

    boolean has(String key) {
        return key != null && queryParams.containsKey(key.toLowerCase(Locale.ROOT));
    }

    int positiveInt(String key, int fallback) {
        String value = first(key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    String customPath() {
        return pathTail == null || pathTail.trim().isEmpty() ? null : pathTail;
    }

    private static int pathDelimiter(String raw) {
        if (raw == null) return -1;
        int question = raw.indexOf('?');
        int from = 0;
        while (true) {
            int pipe = raw.indexOf('|', from);
            if (pipe < 0) return -1;
            String tail = raw.substring(pipe + 1).trim();
            String first = tail;
            int next = first.indexOf('|');
            if (next >= 0) first = first.substring(0, next).trim();
            int equals = first.indexOf('=');
            if (equals > 0 && isPathKey(first.substring(0, equals))) return pipe;
            // Preserve the original bare-path syntax when the pipe is not part of a get regex.
            String prefix = raw.substring(0, pipe).toLowerCase(Locale.ROOT);
            if ((question < 0 || pipe < question || !prefix.contains("get=")) && looksLikeBarePath(first)) return pipe;
            from = pipe + 1;
        }
    }

    private static boolean isPathKey(String value) {
        String key = value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return "filepath".equals(key) || "path".equals(key) || "targetpath".equals(key)
                || "installpath".equals(key) || "dir".equals(key) || "directory".equals(key)
                || "updatepath".equals(key) || "updatefolder".equals(key) || "updatedir".equals(key)
                || "updatedirectory".equals(key) || "useupdatefolder".equals(key)
                || "useupdate".equals(key) || "stageinupdate".equals(key) || "stagetoupdate".equals(key);
    }

    private static boolean looksLikeBarePath(String value) {
        if (value == null || value.isEmpty()) return false;
        return value.startsWith("/") || value.startsWith("\\") || value.startsWith(".")
                || value.startsWith("~") || value.matches("^[A-Za-z]:[\\\\/].*")
                || value.toLowerCase(Locale.ROOT).startsWith("plugins/")
                || value.toLowerCase(Locale.ROOT).startsWith("plugins\\");
    }

    private static int legacyQueryDelimiter(String source) {
        if (source == null) return -1;
        String lower = source.toLowerCase(Locale.ROOT);
        String[] keys = {"autobuild", "auto", "buildlib", "library", "get", "artifact", "asset", "index", "file", "branch", "loader", "platform", "channel",
                "account", "author", "versioncheck", "mcversion", "gameversion", "minecraftversion",
                "versionpolicy", "updatepolicy", "onlyminor", "onlypatch", "samemajor", "allowmajor",
                "cache", "metadatacache", "force", "refreshcache"};
        int best = -1;
        for (String key : keys) {
            int found = lower.indexOf("&" + key + "=");
            if (found >= 0 && (best < 0 || found < best)) best = found;
        }
        return best;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value == null ? "" : value, "UTF-8");
        } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {
            return value == null ? "" : value;
        }
    }
}
