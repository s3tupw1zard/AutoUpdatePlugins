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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Resolves GitHub release JAR assets without downloading their payloads. */
public final class GitHubProvider {
    private static final String DEFAULT_API_BASE = "https://api.github.com";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final int MAX_RELEASE_NOTES = 128;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BRACKET_INDEX = Pattern.compile("\\[(\\d+)]");
    private static final Pattern REPOSITORY_COMPONENT = Pattern.compile("[A-Za-z0-9_.-]+");

    private final String apiBase;

    public GitHubProvider(Logger logger) {
        this(logger, DEFAULT_API_BASE);
    }

    /** API base is injectable so release resolution can be tested without GitHub network access. */
    public GitHubProvider(Logger logger, String apiBase) {
        this.apiBase = normalizeApiBase(apiBase);
    }

    public ResolvedUpdate resolve(String pluginName, String sourceUrl, String token) throws IOException {
        return resolve(pluginName, EntryOptions.parse(sourceUrl, null), token);
    }

    ResolvedUpdate resolve(String pluginName, EntryOptions options, String token) throws IOException {
        if (options == null) {
            throw new IOException("GitHub entry options are missing");
        }
        RepositoryReference repository = parseRepository(options.sourceWithoutQuery);
        Pattern assetPattern = compilePattern(options.first("get"));
        int requestedIndex = firstPositive(options, "index", "artifact", "asset", "file", "zip");
        if (requestedIndex < 1) {
            requestedIndex = repository.bracketIndex > 0 ? repository.bracketIndex : 1;
        }

        SelectionPolicy policy = SelectionPolicy.from(options);
        FetchedReleases fetched = fetchReleases(repository, token);
        List<JsonNode> releases = fetched.releases;
        Collections.sort(releases, newestReleaseFirst());
        List<JsonNode> orderedReleases = policy.order(releases);

        int eligibleIndex = 0;
        for (JsonNode release : orderedReleases) {
            JsonNode assets = release.path("assets");
            if (!assets.isArray()) {
                continue;
            }
            for (JsonNode asset : assets) {
                String fileName = text(asset, "name");
                String browserDownloadUrl = text(asset, "browser_download_url");
                String apiDownloadUrl = text(asset, "url");
                String downloadUrl = notBlank(token) && notBlank(apiDownloadUrl)
                        ? apiDownloadUrl : browserDownloadUrl;
                String assetId = nodeId(asset);
                if (!eligibleAsset(asset, fileName, browserDownloadUrl) || assetId == null) {
                    continue;
                }
                if (assetPattern != null && !assetPattern.matcher(fileName).find()) {
                    continue;
                }
                if (++eligibleIndex != requestedIndex) {
                    continue;
                }
                return resolved(pluginName, repository, release, asset,
                        fileName, downloadUrl, assetId,
                        releaseNotes(orderedReleases, assetPattern, release, fetched.complete));
            }
        }

        String selector = assetPattern == null
                ? " at index " + requestedIndex
                : " matching get=" + options.first("get");
        throw new IOException("No GitHub release JAR asset was found" + selector
                + " for " + repository.fullName);
    }

    static String extractRepository(String sourceUrl) throws IOException {
        return parseRepository(sourceUrl).fullName;
    }

    private ResolvedUpdate resolved(String pluginName,
                                    RepositoryReference repository,
                                    JsonNode release,
                                    JsonNode asset,
                                    String fileName,
                                    String downloadUrl,
                                    String assetId,
                                    ReleaseNoteHistory releaseNotes) throws IOException {
        String releaseId = nodeId(release);
        if (releaseId == null) {
            throw new IOException("Selected GitHub release has no ID for " + repository.fullName);
        }
        ReleaseKind kind = releaseKind(release);
        String tag = firstNonBlank(text(release, "tag_name"), text(release, "name"));
        long published = timestamp(release, "published_at");
        if (published == 0L) {
            published = timestamp(release, "created_at");
        }
        long size = asset.path("size").isNumber() ? asset.path("size").asLong(-1L) : -1L;
        Digest digest = Digest.parse(text(asset, "digest"));
        String metadataId = "github:" + repository.fullName + ":release:"
                + releaseId + ":asset:" + assetId;

        return ResolvedUpdate.builder(pluginName, "github", downloadUrl)
                .normalizedSource("github:" + repository.fullName)
                .projectId(repository.fullName)
                .versionId(releaseId)
                .versionLabel(tag)
                .releaseType(kind.label)
                .publishedAtMillis(published)
                .fileName(fileName)
                .sha1(digest.sha1)
                .sha256(digest.sha256)
                .sha512(digest.sha512)
                .md5(digest.md5)
                .size(size)
                .changelog(text(release, "body"))
                .releaseNotes(releaseNotes.notes)
                .releaseNotesComplete(releaseNotes.complete)
                .metadataId(metadataId)
                .build();
    }

    private static ReleaseNoteHistory releaseNotes(List<JsonNode> releases,
                                                   Pattern assetPattern,
                                                   JsonNode selectedRelease,
                                                   boolean fetchedComplete) {
        List<ResolvedUpdate.ReleaseNote> notes = new ArrayList<ResolvedUpdate.ReleaseNote>();
        boolean selectedReached = false;
        boolean complete = fetchedComplete;
        String selectedId = nodeId(selectedRelease);
        for (JsonNode release : releases) {
            if (!selectedReached) {
                selectedReached = release == selectedRelease
                        || (selectedId != null && selectedId.equals(nodeId(release)));
                if (!selectedReached) continue;
            }
            if (!hasEligibleAsset(release.path("assets"), assetPattern)) continue;
            String version = firstNonBlank(text(release, "tag_name"), text(release, "name"));
            String body = text(release, "body");
            if (!notBlank(version) || !notBlank(body)) continue;
            ResolvedUpdate.ReleaseNote note = new ResolvedUpdate.ReleaseNote(
                    nodeId(release),
                    version,
                    releaseKind(release).label,
                    releaseTimestamp(release),
                    body);
            if (note.changelogTruncated) complete = false;
            if (notes.size() < MAX_RELEASE_NOTES) notes.add(note);
            else complete = false;
        }
        if (!selectedReached) complete = false;
        return new ReleaseNoteHistory(notes, complete);
    }

    private static boolean hasEligibleAsset(JsonNode assets, Pattern assetPattern) {
        if (assets == null || !assets.isArray()) return false;
        for (JsonNode asset : assets) {
            String name = text(asset, "name");
            if (!eligibleAsset(asset, name, text(asset, "browser_download_url"))
                    || nodeId(asset) == null) {
                continue;
            }
            if (assetPattern == null || assetPattern.matcher(name).find()) return true;
        }
        return false;
    }

    private FetchedReleases fetchReleases(RepositoryReference repository, String token) throws IOException {
        List<JsonNode> releases = new ArrayList<JsonNode>();
        boolean complete = false;
        String path = "/repos/" + encode(repository.owner) + '/' + encode(repository.repository) + "/releases";
        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = apiBase + path + "?per_page=" + PAGE_SIZE + "&page=" + page;
            ApiPage response = getReleasePage(url, repository.fullName, token);
            for (JsonNode release : response.body) {
                if (release != null && release.isObject() && !release.path("draft").asBoolean(false)) {
                    releases.add(release);
                }
            }
            if (!response.hasNext && response.body.size() < PAGE_SIZE) {
                complete = true;
                break;
            }
            if (response.body.size() == 0) {
                complete = true;
                break;
            }
        }
        return new FetchedReleases(releases, complete);
    }

    private ApiPage getReleasePage(String url, String repository, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        // API requests should never forward a bearer token to a redirect target.
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(Math.max(1000, UpdateOptions.connectTimeoutMs));
        connection.setReadTimeout(Math.max(1000, UpdateOptions.readTimeoutMs));
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (notBlank(token)) {
            connection.setRequestProperty("Authorization", "Bearer " + token.trim());
        }
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                closeQuietly(connection.getErrorStream());
                throw new IOException("GitHub API returned HTTP " + status + " for " + repository);
            }
            JsonNode body;
            try (InputStream input = connection.getInputStream()) {
                body = JSON.readTree(input);
            }
            if (body == null || !body.isArray()) {
                throw new IOException("GitHub API returned a non-array releases response for " + repository);
            }
            return new ApiPage(body, hasNextLink(connection.getHeaderField("Link")));
        } finally {
            connection.disconnect();
        }
    }

    private static RepositoryReference parseRepository(String sourceUrl) throws IOException {
        String value = trimToNull(sourceUrl);
        if (value == null) {
            throw new IOException("GitHub repository URL is blank");
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
            throw new IOException("Invalid GitHub repository URL", ex);
        }
        String host = uri.getHost();
        if (host == null || !("github.com".equalsIgnoreCase(host)
                || "www.github.com".equalsIgnoreCase(host))) {
            throw new IOException("Only github.com repository URLs are supported");
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null) {
            throw new IOException("GitHub URL has no repository path");
        }
        List<String> parts = new ArrayList<String>();
        for (String part : rawPath.split("/")) {
            if (!part.isEmpty()) {
                parts.add(decode(part));
            }
        }
        if (parts.size() < 2) {
            throw new IOException("GitHub URL must include an owner and repository");
        }
        String owner = parts.get(0);
        String repository = parts.get(1);
        if (repository.endsWith(".git")) {
            repository = repository.substring(0, repository.length() - 4);
        }
        if (!REPOSITORY_COMPONENT.matcher(owner).matches()
                || !REPOSITORY_COMPONENT.matcher(repository).matches()
                || ".".equals(owner) || "..".equals(owner)
                || ".".equals(repository) || "..".equals(repository)) {
            throw new IOException("Invalid GitHub owner or repository name");
        }
        return new RepositoryReference(owner, repository, selected);
    }

    private static Pattern compilePattern(String expression) throws IOException {
        if (!notBlank(expression)) {
            return null;
        }
        try {
            return Pattern.compile(expression);
        } catch (PatternSyntaxException ex) {
            throw new IOException("Invalid GitHub get-regex: " + ex.getDescription(), ex);
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

    private static boolean eligibleAsset(JsonNode asset, String name, String url) {
        if (!notBlank(name) || !name.toLowerCase(Locale.ROOT).endsWith(".jar") || !notBlank(url)) {
            return false;
        }
        String state = text(asset, "state");
        return state == null || "uploaded".equalsIgnoreCase(state);
    }

    private static Comparator<JsonNode> newestReleaseFirst() {
        return new Comparator<JsonNode>() {
            @Override
            public int compare(JsonNode left, JsonNode right) {
                int byPublished = Long.compare(releaseTimestamp(right), releaseTimestamp(left));
                if (byPublished != 0) {
                    return byPublished;
                }
                return Long.compare(numericId(right), numericId(left));
            }
        };
    }

    private static long releaseTimestamp(JsonNode release) {
        long published = timestamp(release, "published_at");
        return published == 0L ? timestamp(release, "created_at") : published;
    }

    private static long timestamp(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return 0L;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static long numericId(JsonNode node) {
        JsonNode id = node == null ? null : node.get("id");
        return id != null && id.canConvertToLong() ? id.asLong() : 0L;
    }

    private static ReleaseKind releaseKind(JsonNode release) {
        if (!release.path("prerelease").asBoolean(false)) {
            return ReleaseKind.RELEASE;
        }
        String label = lower(firstNonBlank(text(release, "tag_name"), text(release, "name")));
        if (label.contains("alpha") || label.contains("snapshot") || label.contains("nightly")
                || label.contains("-dev") || label.startsWith("dev")) {
            return ReleaseKind.ALPHA;
        }
        return ReleaseKind.BETA;
    }

    private static String nodeId(JsonNode node) {
        JsonNode id = node == null ? null : node.get("id");
        return id == null || id.isNull() ? null : trimToNull(id.asText());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : trimToNull(value.asText());
    }

    private static boolean hasNextLink(String link) {
        if (link == null) {
            return false;
        }
        String normalized = link.toLowerCase(Locale.ROOT);
        return normalized.contains("rel=\"next\"") || normalized.contains("rel=next");
    }

    private static String normalizeApiBase(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException("GitHub API base must not be blank");
        }
        final URI uri;
        try {
            uri = new URI(normalized);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid GitHub API base", ex);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("GitHub API base must be an HTTP(S) origin/path without credentials or a query");
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String encode(String value) throws IOException {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception ex) {
            throw new IOException("Unable to encode GitHub URL component", ex);
        }
    }

    private static String decode(String value) throws IOException {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ex) {
            throw new IOException("Unable to decode GitHub repository path", ex);
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

    private static String firstNonBlank(String first, String second) {
        return notBlank(first) ? first.trim() : trimToNull(second);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
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

    private enum ReleaseKind {
        RELEASE("release"),
        BETA("beta"),
        ALPHA("alpha");

        private final String label;

        ReleaseKind(String label) {
            this.label = label;
        }
    }

    private static final class SelectionPolicy {
        private final Set<ReleaseKind> allowed;
        private final List<ReleaseKind> priority;

        private SelectionPolicy(Set<ReleaseKind> allowed, List<ReleaseKind> priority) {
            this.allowed = allowed;
            this.priority = priority;
        }

        private static SelectionPolicy from(EntryOptions options) throws IOException {
            String channel = lower(options.first("channel"));
            if (!channel.isEmpty()) {
                if ("release".equals(channel) || "stable".equals(channel)) {
                    return only(ReleaseKind.RELEASE);
                }
                if ("beta".equals(channel) || "preview".equals(channel) || "rc".equals(channel)) {
                    return only(ReleaseKind.BETA);
                }
                if ("alpha".equals(channel) || "snapshot".equals(channel)
                        || "nightly".equals(channel) || "dev".equals(channel)) {
                    return only(ReleaseKind.ALPHA);
                }
                if ("prerelease".equals(channel) || "pre-release".equals(channel)) {
                    return new SelectionPolicy(EnumSet.of(ReleaseKind.BETA, ReleaseKind.ALPHA),
                            Collections.<ReleaseKind>emptyList());
                }
                if ("latest".equals(channel) || "all".equals(channel) || "any".equals(channel)) {
                    return allByDate();
                }
                throw new IOException("Unsupported GitHub release channel: " + channel);
            }

            if (options.bool("latest", false)) {
                return allByDate();
            }
            if ((options.has("prerelease") && !options.bool("prerelease", true))
                    || (options.has("pre-release") && !options.bool("pre-release", true))) {
                return only(ReleaseKind.RELEASE);
            }
            if ((options.has("prerelease") && options.bool("prerelease", false))
                    || (options.has("pre-release") && options.bool("pre-release", false))) {
                return allByDate();
            }

            boolean alpha = options.bool("alpha", false);
            boolean beta = options.bool("beta", false);
            if (alpha && beta) {
                return allByDate();
            }
            if (alpha) {
                return preferred(ReleaseKind.ALPHA);
            }
            if (beta) {
                return preferred(ReleaseKind.BETA);
            }
            return UpdateOptions.allowPreReleaseDefault ? allByDate() : only(ReleaseKind.RELEASE);
        }

        private List<JsonNode> order(List<JsonNode> releases) {
            List<JsonNode> eligible = new ArrayList<JsonNode>();
            for (JsonNode release : releases) {
                if (allowed.contains(releaseKind(release))) {
                    eligible.add(release);
                }
            }
            if (priority.isEmpty()) {
                return eligible;
            }
            List<JsonNode> ordered = new ArrayList<JsonNode>();
            for (ReleaseKind kind : priority) {
                for (JsonNode release : eligible) {
                    if (releaseKind(release) == kind) {
                        ordered.add(release);
                    }
                }
            }
            return ordered;
        }

        private static SelectionPolicy only(ReleaseKind kind) {
            return new SelectionPolicy(EnumSet.of(kind), Arrays.asList(kind));
        }

        private static SelectionPolicy preferred(ReleaseKind kind) {
            return new SelectionPolicy(EnumSet.of(kind, ReleaseKind.RELEASE),
                    Arrays.asList(kind, ReleaseKind.RELEASE));
        }

        private static SelectionPolicy allByDate() {
            return new SelectionPolicy(EnumSet.allOf(ReleaseKind.class),
                    Collections.<ReleaseKind>emptyList());
        }
    }

    private static final class RepositoryReference {
        private final String owner;
        private final String repository;
        private final String fullName;
        private final int bracketIndex;

        private RepositoryReference(String owner, String repository, int bracketIndex) {
            this.owner = owner;
            this.repository = repository;
            this.fullName = owner + '/' + repository;
            this.bracketIndex = bracketIndex;
        }
    }

    private static final class ApiPage {
        private final JsonNode body;
        private final boolean hasNext;

        private ApiPage(JsonNode body, boolean hasNext) {
            this.body = body;
            this.hasNext = hasNext;
        }
    }

    private static final class FetchedReleases {
        private final List<JsonNode> releases;
        private final boolean complete;

        private FetchedReleases(List<JsonNode> releases, boolean complete) {
            this.releases = releases;
            this.complete = complete;
        }
    }

    private static final class ReleaseNoteHistory {
        private final List<ResolvedUpdate.ReleaseNote> notes;
        private final boolean complete;

        private ReleaseNoteHistory(List<ResolvedUpdate.ReleaseNote> notes, boolean complete) {
            this.notes = notes;
            this.complete = complete;
        }
    }

    private static final class Digest {
        private String sha1;
        private String sha256;
        private String sha512;
        private String md5;

        private static Digest parse(String value) {
            Digest digest = new Digest();
            if (!notBlank(value)) {
                return digest;
            }
            int separator = value.indexOf(':');
            if (separator <= 0 || separator >= value.length() - 1) {
                return digest;
            }
            String algorithm = value.substring(0, separator).trim().toLowerCase(Locale.ROOT)
                    .replace("-", "");
            String hash = value.substring(separator + 1).trim();
            if ("sha1".equals(algorithm)) digest.sha1 = hash;
            else if ("sha256".equals(algorithm)) digest.sha256 = hash;
            else if ("sha512".equals(algorithm)) digest.sha512 = hash;
            else if ("md5".equals(algorithm)) digest.md5 = hash;
            return digest;
        }
    }
}
