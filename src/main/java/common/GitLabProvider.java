package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Resolves GitLab Generic and Maven package-registry entries without downloading their payloads. */
public final class GitLabProvider {
    private static final String DEFAULT_API_BASE = "https://gitlab.com/api/v4";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BRACKET_INDEX = Pattern.compile("\\[(\\d+)]");

    private final Logger logger;
    private final String apiBase;

    public GitLabProvider(Logger logger) {
        this(logger, DEFAULT_API_BASE);
    }

    /** Package-visible for deterministic mock-server tests. */
    GitLabProvider(Logger logger, String apiBase) {
        this.logger = logger;
        if (apiBase == null || apiBase.trim().isEmpty()) {
            throw new IllegalArgumentException("GitLab API base must not be blank");
        }
        String normalized = apiBase.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.apiBase = normalized;
    }

    public ResolvedUpdate resolve(String pluginName, String sourceUrl, String privateToken) throws IOException {
        return resolve(pluginName, EntryOptions.parse(sourceUrl, logger), privateToken);
    }

    ResolvedUpdate resolve(String pluginName, EntryOptions options, String privateToken) throws IOException {
        if (options == null) {
            throw new IOException("GitLab entry options are missing");
        }
        ProjectReference project = parseProject(options.sourceWithoutQuery);
        String encodedProject = encodeComponent(project.projectPath);

        Pattern filePattern = compilePattern(options.first("get"));
        int requestedIndex = firstPositive(options, "index", "artifact", "file");
        if (requestedIndex < 1) {
            requestedIndex = project.bracketIndex > 0 ? project.bracketIndex : 1;
        }
        String requestedType = normalizePackageType(options.first("packageType", "package_type", "registry", "type"));
        String requestedPackage = trimToNull(options.first("packageName", "package"));

        String packagesUrl = apiBase + "/projects/" + encodedProject
                + "/packages?order_by=created_at&sort=desc&per_page=" + PAGE_SIZE + "&page=1";
        List<JsonNode> packages = fetchPages(packagesUrl, privateToken);
        Collections.sort(packages, newestFirst());

        int eligibleIndex = 0;
        for (JsonNode packageNode : packages) {
            String packageType = lowerText(packageNode, "package_type");
            if (!"generic".equals(packageType) && !"maven".equals(packageType)) {
                continue;
            }
            if (requestedType != null && !requestedType.equals(packageType)) {
                continue;
            }
            String status = lowerText(packageNode, "status");
            if (status != null && !"default".equals(status) && !"deprecated".equals(status)) {
                continue;
            }
            String packageName = text(packageNode, "name");
            if (packageName == null || (requestedPackage != null && !requestedPackage.equalsIgnoreCase(packageName))) {
                continue;
            }
            String packageVersion = text(packageNode, "version");
            String packageId = nodeId(packageNode);
            if (packageVersion == null || packageId == null) {
                continue;
            }

            String filesUrl = apiBase + "/projects/" + encodedProject + "/packages/"
                    + encodeComponent(packageId)
                    + "/package_files?order_by=created_at&sort=desc&per_page=" + PAGE_SIZE + "&page=1";
            List<JsonNode> files = fetchPages(filesUrl, privateToken);
            Collections.sort(files, newestFirst());

            for (JsonNode file : files) {
                String fileName = text(file, "file_name");
                if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                if (filePattern != null && !filePattern.matcher(fileName).find()) {
                    continue;
                }
                eligibleIndex++;
                if (eligibleIndex != requestedIndex) {
                    continue;
                }
                return resolved(pluginName, project.projectPath, packageNode, file, packageType,
                        packageName, packageVersion, packageId, fileName);
            }
        }

        String selector = filePattern != null ? " matching get=" + options.first("get") : " at index " + requestedIndex;
        throw new IOException("No GitLab Generic or Maven package JAR was found" + selector
                + " for project " + project.projectPath);
    }

    private ResolvedUpdate resolved(String pluginName,
                                    String projectPath,
                                    JsonNode packageNode,
                                    JsonNode file,
                                    String packageType,
                                    String packageName,
                                    String packageVersion,
                                    String packageId,
                                    String fileName) throws IOException {
        String fileId = nodeId(file);
        if (fileId == null) {
            throw new IOException("Selected GitLab package file has no ID");
        }
        String sha256 = text(file, "file_sha256");
        String sha1 = text(file, "file_sha1");
        String md5 = text(file, "file_md5");
        String downloadUrl = buildDownloadUrl(projectPath, packageType, packageName, packageVersion, fileName);
        long published = Math.max(timestamp(packageNode), timestamp(file));
        long size = file.path("size").isNumber() ? file.path("size").asLong(-1L) : -1L;
        String strongestHash = firstNonBlank(sha256, sha1, md5, fileName);
        String metadataId = "gitlab:" + projectPath + ':' + packageId + ':' + fileId + ':' + strongestHash;

        return ResolvedUpdate.builder(pluginName, "gitlab", downloadUrl)
                .normalizedSource("gitlab:" + projectPath)
                .projectId(projectPath)
                .versionId(packageId + ":" + fileId)
                .versionLabel(packageVersion)
                .releaseType(packageType)
                .publishedAtMillis(published)
                .fileName(fileName)
                .sha256(sha256)
                .sha1(sha1)
                .md5(md5)
                .size(size)
                .metadataId(metadataId)
                .build();
    }

    private String buildDownloadUrl(String projectPath,
                                    String packageType,
                                    String packageName,
                                    String packageVersion,
                                    String fileName) throws IOException {
        String prefix = apiBase + "/projects/" + encodeComponent(projectPath) + "/packages/";
        if ("generic".equals(packageType)) {
            return prefix + "generic/" + encodeComponent(packageName) + '/'
                    + encodeComponent(packageVersion) + '/' + encodeComponent(fileName);
        }
        if ("maven".equals(packageType)) {
            return prefix + "maven/" + encodeMavenPath(packageName) + '/'
                    + encodeComponent(packageVersion) + '/' + encodeComponent(fileName);
        }
        throw new IOException("Unsupported GitLab package type: " + packageType);
    }

    private List<JsonNode> fetchPages(String firstUrl, String privateToken) throws IOException {
        List<JsonNode> result = new ArrayList<JsonNode>();
        String url = firstUrl;
        for (int page = 1; page <= MAX_PAGES && url != null; page++) {
            ApiPage response = getJsonArray(url, privateToken);
            for (JsonNode item : response.body) {
                result.add(item);
            }
            String next = trimToNull(response.nextPage);
            if (next == null) {
                break;
            }
            url = replacePage(url, next);
        }
        return result;
    }

    private ApiPage getJsonArray(String url, String privateToken) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        // A PRIVATE-TOKEN must never be replayed to a redirect target.
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(Math.max(1000, UpdateOptions.connectTimeoutMs));
        connection.setReadTimeout(Math.max(1000, UpdateOptions.readTimeoutMs));
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");
        if (privateToken != null && !privateToken.trim().isEmpty()) {
            connection.setRequestProperty("PRIVATE-TOKEN", privateToken.trim());
        }
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                closeQuietly(connection.getErrorStream());
                throw new IOException("GitLab API returned HTTP " + status + " for " + url);
            }
            JsonNode body;
            try (InputStream input = connection.getInputStream()) {
                body = JSON.readTree(input);
            }
            if (body == null || !body.isArray()) {
                throw new IOException("GitLab API returned a non-array response for " + url);
            }
            return new ApiPage(body, connection.getHeaderField("X-Next-Page"));
        } finally {
            connection.disconnect();
        }
    }

    static String extractProjectPath(String sourceUrl) throws IOException {
        return parseProject(sourceUrl).projectPath;
    }

    private static ProjectReference parseProject(String sourceUrl) throws IOException {
        String value = trimToNull(sourceUrl);
        if (value == null) {
            throw new IOException("GitLab project URL is blank");
        }
        Matcher bracket = BRACKET_INDEX.matcher(value);
        int selected = -1;
        StringBuffer withoutBracket = new StringBuffer();
        while (bracket.find()) {
            try {
                selected = Integer.parseInt(bracket.group(1));
            } catch (NumberFormatException ignored) {
                selected = -1;
            }
            bracket.appendReplacement(withoutBracket, "");
        }
        bracket.appendTail(withoutBracket);

        final URI uri;
        try {
            uri = new URI(withoutBracket.toString());
        } catch (URISyntaxException ex) {
            throw new IOException("Invalid GitLab project URL", ex);
        }
        String host = uri.getHost();
        if (host == null || !("gitlab.com".equalsIgnoreCase(host) || "www.gitlab.com".equalsIgnoreCase(host))) {
            throw new IOException("Only gitlab.com project URLs are supported");
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null) {
            throw new IOException("GitLab project URL has no project path");
        }
        List<String> parts = new ArrayList<String>();
        for (String rawPart : rawPath.split("/")) {
            if (rawPart == null || rawPart.isEmpty()) {
                continue;
            }
            String part = decodeComponent(rawPart);
            if ("-".equals(part)) {
                break;
            }
            parts.add(part);
        }
        if (parts.size() < 2) {
            throw new IOException("GitLab project URL must include a namespace and project");
        }
        int last = parts.size() - 1;
        String project = parts.get(last);
        if (project.endsWith(".git")) {
            project = project.substring(0, project.length() - 4);
            parts.set(last, project);
        }
        if (project.isEmpty()) {
            throw new IOException("GitLab project slug is blank");
        }
        return new ProjectReference(join(parts, "/"), selected);
    }

    private static Comparator<JsonNode> newestFirst() {
        return new Comparator<JsonNode>() {
            @Override
            public int compare(JsonNode left, JsonNode right) {
                int byTime = Long.compare(timestamp(right), timestamp(left));
                if (byTime != 0) {
                    return byTime;
                }
                return Long.compare(numericId(right), numericId(left));
            }
        };
    }

    private static long timestamp(JsonNode node) {
        String value = text(node, "created_at");
        if (value == null) {
            return 0L;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long numericId(JsonNode node) {
        JsonNode id = node == null ? null : node.get("id");
        return id != null && id.canConvertToLong() ? id.asLong() : 0L;
    }

    private static String nodeId(JsonNode node) {
        JsonNode id = node == null ? null : node.get("id");
        if (id == null || id.isNull()) {
            return null;
        }
        return trimToNull(id.asText());
    }

    private static String lowerText(JsonNode node, String key) {
        String value = text(node, key);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? null : trimToNull(value.asText());
    }

    private static Pattern compilePattern(String expression) throws IOException {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(expression);
        } catch (PatternSyntaxException ex) {
            throw new IOException("Invalid GitLab get-regex: " + ex.getDescription(), ex);
        }
    }

    private static int firstPositive(EntryOptions options, String... keys) {
        if (options == null || keys == null) {
            return -1;
        }
        for (String key : keys) {
            String value = options.first(key);
            if (value == null) {
                continue;
            }
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static String normalizePackageType(String value) throws IOException {
        String normalized = trimToNull(value);
        if (normalized == null || "all".equalsIgnoreCase(normalized)) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!"generic".equals(normalized) && !"maven".equals(normalized)) {
            throw new IOException("GitLab packageType must be generic or maven");
        }
        return normalized;
    }

    private static String encodeMavenPath(String packageName) throws IOException {
        List<String> parts = new ArrayList<String>();
        for (String part : packageName.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IOException("Invalid GitLab Maven package name: " + packageName);
            }
            parts.add(encodeComponent(part));
        }
        if (parts.isEmpty()) {
            throw new IOException("Invalid GitLab Maven package name: " + packageName);
        }
        return join(parts, "/");
    }

    private static String encodeComponent(String value) throws IOException {
        try {
            return URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("%7E", "~");
        } catch (Exception ex) {
            throw new IOException("Unable to encode GitLab URL component", ex);
        }
    }

    private static String decodeComponent(String value) throws IOException {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ex) {
            throw new IOException("Unable to decode GitLab project path", ex);
        }
    }

    private static String replacePage(String url, String page) {
        return url.replaceFirst("([?&]page=)[^&]*", "$1" + page);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(separator);
            }
            result.append(value);
        }
        return result.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                String trimmed = trimToNull(value);
                if (trimmed != null) {
                    return trimmed;
                }
            }
        }
        return "unknown";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            while (input.read() != -1) {
                // Drain a small error response so the connection can be reused.
            }
        } catch (IOException ignored) {
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class ProjectReference {
        private final String projectPath;
        private final int bracketIndex;

        private ProjectReference(String projectPath, int bracketIndex) {
            this.projectPath = projectPath;
            this.bracketIndex = bracketIndex;
        }
    }

    private static final class ApiPage {
        private final JsonNode body;
        private final String nextPage;

        private ApiPage(JsonNode body, String nextPage) {
            this.body = body;
            this.nextPage = nextPage;
        }
    }
}
