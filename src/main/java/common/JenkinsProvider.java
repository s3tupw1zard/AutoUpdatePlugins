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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Resolves a Jenkins job's latest successful JAR artifact without downloading its payload. */
public final class JenkinsProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BRACKET_INDEX = Pattern.compile("\\[(\\d+)](?=/?(?:[?#]|$))");
    private static final int MAX_CHANGE_MESSAGES = 25;
    private static final int MAX_CHANGELOG_CHARS = 4000;

    /** Kept for a consistent provider construction API; this provider deliberately does not log secrets. */
    public JenkinsProvider(Logger logger) {
    }

    public ResolvedUpdate resolve(String pluginName, String sourceUrl) throws IOException {
        return resolve(pluginName, sourceUrl, null, null);
    }

    public ResolvedUpdate resolve(String pluginName,
                                  String sourceUrl,
                                  String username,
                                  String apiToken) throws IOException {
        return resolve(pluginName, EntryOptions.parse(sourceUrl, null), username, apiToken);
    }

    ResolvedUpdate resolve(String pluginName,
                           EntryOptions options,
                           String username,
                           String apiToken) throws IOException {
        if (options == null) {
            throw new IOException("Jenkins entry options are missing");
        }
        if (options.has("token") || options.has("password") || options.has("apikey")
                || options.has("api_key") || options.has("authorization")) {
            throw new IOException("Jenkins credentials must be configured separately, not embedded in the URL");
        }
        JobReference job = parseJob(options.sourceWithoutQuery);
        Pattern filePattern = compilePattern(options.first("get"));
        int explicitIndex = firstPositive(options, "index", "artifact", "file");
        int requestedIndex = explicitIndex;
        if (requestedIndex < 1) {
            requestedIndex = job.bracketIndex > 0 ? job.bracketIndex : 1;
        }
        boolean implicitArtifactPath = filePattern == null && explicitIndex < 1
                && job.bracketIndex < 1 && job.artifactPath != null;
        String authorization = basicAuthorization(username, apiToken);

        String tree = "number,id,timestamp,displayName,fullDisplayName,description,"
                + "artifacts[fileName,relativePath,size,length],changeSet[items[msg]]";
        String apiUrl = job.jobRoot + "lastSuccessfulBuild/api/json?tree=" + encodeQuery(tree);
        JsonNode build = getJson(apiUrl, job.jobRoot, authorization);
        JsonNode artifacts = build.path("artifacts");
        if (!artifacts.isArray()) {
            throw new IOException("Jenkins returned no artifact list for " + job.jobRoot);
        }

        List<Artifact> eligible = new ArrayList<Artifact>();
        for (JsonNode artifactNode : artifacts) {
            Artifact artifact = Artifact.from(artifactNode);
            if (artifact == null || !artifact.isJar()) {
                continue;
            }
            if (filePattern != null && !filePattern.matcher(artifact.fileName).find()
                    && !filePattern.matcher(artifact.relativePath).find()) {
                continue;
            }
            if (implicitArtifactPath
                    && !job.artifactPath.equals(artifact.relativePath)) {
                continue;
            }
            eligible.add(artifact);
        }

        if (eligible.size() < requestedIndex) {
            String selector;
            if (filePattern != null) {
                selector = " matching get=" + options.first("get");
            } else if (implicitArtifactPath) {
                selector = " matching artifact path " + job.artifactPath;
            } else {
                selector = " at index " + requestedIndex;
            }
            throw new IOException("No Jenkins JAR artifact was found" + selector
                    + " for " + job.jobRoot);
        }

        Artifact selected = eligible.get(requestedIndex - 1);
        String buildNumber = buildIdentifier(build);
        if (buildNumber == null) {
            throw new IOException("Jenkins latest successful build has no build number for " + job.jobRoot);
        }
        String downloadUrl = job.jobRoot + encodePathSegment(buildNumber)
                + "/artifact/" + encodeRelativePath(selected.relativePath);
        long timestamp = timestamp(build.get("timestamp"));
        String changelog = changelog(build);
        String metadataId = "jenkins:" + job.jobRoot + ':' + buildNumber + ':' + selected.relativePath;

        return ResolvedUpdate.builder(pluginName, "jenkins", downloadUrl)
                .normalizedSource(job.jobRoot)
                .projectId(job.jobRoot)
                .versionId(buildNumber)
                .versionLabel(buildNumber)
                .releaseType("build")
                .publishedAtMillis(timestamp)
                .fileName(selected.fileName)
                .size(selected.size)
                .changelog(changelog)
                .metadataId(metadataId)
                .build();
    }

    /** Returns the canonical job root for job, build, lastSuccessfulBuild, and artifact URLs. */
    public static String canonicalJobRoot(String sourceUrl) throws IOException {
        return parseJob(sourceUrl).jobRoot;
    }

    private static JsonNode getJson(String apiUrl,
                                    String safeJobRoot,
                                    String authorization) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
        // Never forward Basic credentials to a redirect target. Canonical job roots include a trailing slash.
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(Math.max(1000, UpdateOptions.connectTimeoutMs));
        connection.setReadTimeout(Math.max(1000, UpdateOptions.readTimeoutMs));
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                closeQuietly(connection.getErrorStream());
                throw new IOException("Jenkins API returned HTTP " + status + " for " + safeJobRoot);
            }
            JsonNode body;
            try (InputStream input = connection.getInputStream()) {
                body = JSON.readTree(input);
            }
            if (body == null || !body.isObject()) {
                throw new IOException("Jenkins API returned a non-object build response for " + safeJobRoot);
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static JobReference parseJob(String sourceUrl) throws IOException {
        String value = trimToNull(sourceUrl);
        if (value == null) {
            throw new IOException("Jenkins job URL is blank");
        }
        Matcher bracket = BRACKET_INDEX.matcher(value);
        int bracketIndex = -1;
        StringBuffer withoutBracket = new StringBuffer();
        while (bracket.find()) {
            try {
                bracketIndex = Integer.parseInt(bracket.group(1));
            } catch (NumberFormatException ignored) {
                bracketIndex = -1;
            }
            bracket.appendReplacement(withoutBracket, "");
        }
        bracket.appendTail(withoutBracket);

        final URI uri;
        try {
            uri = new URI(withoutBracket.toString());
        } catch (URISyntaxException ex) {
            throw new IOException("Invalid Jenkins job URL", ex);
        }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null) {
            throw new IOException("Jenkins job URL must use HTTP(S)");
        }
        if (uri.getUserInfo() != null) {
            throw new IOException("Jenkins credentials must be configured separately, not embedded in the URL");
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null) {
            throw new IOException("Jenkins job URL has no path");
        }
        List<String> rawSegments = splitPath(rawPath);
        int firstJob = -1;
        int jobEnd = -1;
        for (int i = 0; i < rawSegments.size(); i++) {
            if (!"job".equals(rawSegments.get(i))) {
                continue;
            }
            if (i + 1 >= rawSegments.size() || rawSegments.get(i + 1).isEmpty()) {
                throw new IOException("Jenkins job URL is missing a job name");
            }
            if (firstJob < 0) {
                firstJob = i;
            }
            jobEnd = i + 1;
            i++;
            if (i + 1 >= rawSegments.size() || !"job".equals(rawSegments.get(i + 1))) {
                break;
            }
        }
        if (firstJob < 0 || jobEnd < 0) {
            throw new IOException("Jenkins URL must contain /job/<name>");
        }

        String artifactPath = null;
        for (int i = jobEnd + 1; i < rawSegments.size(); i++) {
            if (!"artifact".equals(rawSegments.get(i)) || i + 1 >= rawSegments.size()) {
                continue;
            }
            artifactPath = decodeRelativePath(rawSegments, i + 1);
            break;
        }

        StringBuilder rootPath = new StringBuilder();
        for (int i = 0; i <= jobEnd; i++) {
            rootPath.append('/').append(rawSegments.get(i));
        }
        String root = scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority()
                + rootPath + '/';
        return new JobReference(root, bracketIndex, artifactPath);
    }

    private static List<String> splitPath(String rawPath) {
        List<String> parts = new ArrayList<String>();
        for (String part : rawPath.split("/")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String decodeRelativePath(List<String> rawSegments, int start) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < rawSegments.size(); i++) {
            String segment = decodePathSegment(rawSegments.get(i));
            validateArtifactSegment(segment);
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(segment);
        }
        return trimToNull(result.toString());
    }

    private static String encodeRelativePath(String relativePath) throws IOException {
        StringBuilder result = new StringBuilder();
        for (String segment : relativePath.split("/", -1)) {
            validateArtifactSegment(segment);
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(encodePathSegment(segment));
        }
        return result.toString();
    }

    private static void validateArtifactSegment(String segment) throws IOException {
        if (segment == null || segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0 || containsControl(segment)) {
            throw new IOException("Jenkins returned an invalid artifact path");
        }
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String basicAuthorization(String username, String apiToken) throws IOException {
        boolean hasUsername = notBlank(username);
        boolean hasToken = notBlank(apiToken);
        if (!hasUsername && !hasToken) {
            return null;
        }
        if (!hasUsername || !hasToken) {
            throw new IOException("Jenkins Basic authentication requires both a username and API token");
        }
        String credentials = username.trim() + ':' + apiToken.trim();
        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static Pattern compilePattern(String expression) throws IOException {
        if (!notBlank(expression)) {
            return null;
        }
        try {
            return Pattern.compile(expression);
        } catch (PatternSyntaxException ex) {
            throw new IOException("Invalid Jenkins get-regex: " + ex.getDescription(), ex);
        }
    }

    private static int firstPositive(EntryOptions options, String... keys) {
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

    private static String buildIdentifier(JsonNode build) {
        JsonNode number = build.get("number");
        if (number != null && !number.isNull()) {
            return safeIdentifier(number.asText());
        }
        return safeIdentifier(text(build, "id"));
    }

    private static String safeIdentifier(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null || trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0
                || containsControl(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static long timestamp(JsonNode value) {
        if (value == null || value.isNull()) {
            return 0L;
        }
        if (value.isNumber()) {
            return value.asLong(0L);
        }
        String text = trimToNull(value.asText());
        if (text == null) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            try {
                return Instant.parse(text).toEpochMilli();
            } catch (RuntimeException ignoredAgain) {
                return 0L;
            }
        }
    }

    private static String changelog(JsonNode build) {
        String description = text(build, "description");
        StringBuilder result = new StringBuilder();
        if (description != null) {
            appendBounded(result, description);
        }
        JsonNode items = build.path("changeSet").path("items");
        int count = 0;
        if (items.isArray()) {
            for (JsonNode item : items) {
                if (count >= MAX_CHANGE_MESSAGES || result.length() >= MAX_CHANGELOG_CHARS) {
                    break;
                }
                String message = text(item, "msg");
                if (message == null) {
                    continue;
                }
                if (result.length() > 0) {
                    appendBounded(result, "\n");
                }
                appendBounded(result, message);
                count++;
            }
        }
        return trimToNull(result.toString());
    }

    private static void appendBounded(StringBuilder target, String value) {
        int remaining = MAX_CHANGELOG_CHARS - target.length();
        if (remaining <= 0 || value == null) {
            return;
        }
        target.append(value, 0, Math.min(value.length(), remaining));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : trimToNull(value.asText());
    }

    private static String encodeQuery(String value) throws IOException {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception ex) {
            throw new IOException("Unable to encode Jenkins API query", ex);
        }
    }

    private static String encodePathSegment(String value) throws IOException {
        try {
            return URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("%7E", "~");
        } catch (Exception ex) {
            throw new IOException("Unable to encode Jenkins artifact path", ex);
        }
    }

    private static String decodePathSegment(String value) throws IOException {
        try {
            // URLDecoder treats '+' as a space, but plus is a literal character in URI paths.
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (Exception ex) {
            throw new IOException("Unable to decode Jenkins artifact path", ex);
        }
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class JobReference {
        private final String jobRoot;
        private final int bracketIndex;
        private final String artifactPath;

        private JobReference(String jobRoot, int bracketIndex, String artifactPath) {
            this.jobRoot = jobRoot;
            this.bracketIndex = bracketIndex;
            this.artifactPath = artifactPath;
        }
    }

    private static final class Artifact {
        private final String fileName;
        private final String relativePath;
        private final long size;

        private Artifact(String fileName, String relativePath, long size) {
            this.fileName = fileName;
            this.relativePath = relativePath;
            this.size = size;
        }

        private static Artifact from(JsonNode node) throws IOException {
            if (node == null || !node.isObject()) {
                return null;
            }
            String relativePath = text(node, "relativePath");
            String fileName = text(node, "fileName");
            if (relativePath == null) {
                relativePath = fileName;
            }
            if (relativePath == null) {
                return null;
            }
            // Validate now so a malicious API response cannot create an unsafe download URL.
            encodeRelativePath(relativePath);
            if (fileName == null) {
                int slash = relativePath.lastIndexOf('/');
                fileName = slash < 0 ? relativePath : relativePath.substring(slash + 1);
            }
            validateArtifactSegment(fileName);
            long size = numeric(node.get("size"));
            if (size < 0L) {
                size = numeric(node.get("length"));
            }
            return new Artifact(fileName, relativePath, size);
        }

        private boolean isJar() {
            return fileName.toLowerCase(Locale.ROOT).endsWith(".jar")
                    && relativePath.toLowerCase(Locale.ROOT).endsWith(".jar");
        }

        private static long numeric(JsonNode value) {
            return value != null && value.isNumber() ? value.asLong(-1L) : -1L;
        }
    }
}
