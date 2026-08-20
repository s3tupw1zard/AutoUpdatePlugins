package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves official voxel.shop metadata and entitled resource downloads without scraping pages. */
final class VoxelShopProvider {
    private static final String DEFAULT_API_BASE = "https://api.voxel.shop/v1";
    private static final String CANONICAL_PAGE_BASE = "https://voxel.shop/product/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final Pattern RESOURCE_SLUG = Pattern.compile("^/resource/[^/]+\\.(\\d+)(?:/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOURCE_ID = Pattern.compile("^/resource/(\\d+)(?:/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCT_ID = Pattern.compile("^/product/(\\d+)(?:/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_ID = Pattern.compile("^/r/(\\d+)(?:/.*)?$", Pattern.CASE_INSENSITIVE);

    private final String apiBase;

    VoxelShopProvider() {
        this(DEFAULT_API_BASE);
    }

    /** Package-visible API base injection for deterministic mock-server tests. */
    VoxelShopProvider(String apiBase) {
        if (apiBase == null || apiBase.trim().isEmpty()) {
            throw new IllegalArgumentException("VoxelShop API base must not be blank");
        }
        this.apiBase = trimSlashes(apiBase.trim());
    }

    Resolution resolve(String pluginName, EntryOptions options, String userToken) throws IOException {
        if (options == null) {
            throw new IOException("VoxelShop entry options are missing");
        }
        String resourceId = extractResourceId(options.sourceWithoutQuery);
        Map<String, String> resourceForm = form("resource_id", resourceId, "stringify", "0");
        JsonNode infoResponse = postJson(endpoint("getResourceInfo"), resourceForm);
        JsonNode resource = successfulPayload(infoResponse, "resource", "VoxelShop resource metadata");
        String returnedId = scalar(resource.get("id"));
        if (returnedId == null || !resourceId.equals(returnedId)) {
            throw new IOException("VoxelShop resource metadata returned an unexpected resource ID");
        }
        BigDecimal price = nonNegativeDecimal(resource.get("price"), "VoxelShop resource price");
        List<String> gameVersions = stringList(resource.get("supportedMinecraftVersions"));

        JsonNode updatesResponse = postJson(endpoint("getResourceUpdates"),
                form("resource_id", resourceId, "start", "0", "limit", "50", "stringify", "0"));
        JsonNode response = successfulResponse(updatesResponse, "VoxelShop update history");
        JsonNode updatesNode = response.get("updates");
        if (updatesNode == null || !updatesNode.isArray()) {
            throw new IOException("VoxelShop update history returned no updates array");
        }
        Update selected = selectUpdate(updatesNode, options);
        if (selected == null) {
            throw new IOException("No matching download-ready VoxelShop update was found for resource " + resourceId);
        }

        String actionUrl = CANONICAL_PAGE_BASE + resourceId;
        String token = clean(userToken);
        if (price.signum() > 0 && token == null) {
            return manual(pluginName, resourceId, selected, gameVersions, actionUrl,
                    "An authorized VoxelShop user token is required for this paid resource");
        }

        Map<String, String> downloadForm = form("resource_id", resourceId, "allow_redirects", "0", "stringify", "0");
        if (price.signum() > 0 && token != null) {
            downloadForm.put("token", token);
        }
        JsonNode downloadResponse = postJson(endpoint("getDownloadURL"), downloadForm);
        JsonNode downloadEnvelope = responseObject(downloadResponse, "VoxelShop download response");
        if (!booleanValue(downloadEnvelope.get("success"), false)) {
            return manual(pluginName, resourceId, selected, gameVersions, actionUrl,
                    "VoxelShop did not authorize an automatic download for this resource");
        }
        JsonNode download = downloadEnvelope.get("result");
        if (download == null || !download.isObject()) {
            throw new IOException("VoxelShop download response returned no result object");
        }
        String downloadUrl = scalar(download.get("url"));
        String downloadVersion = scalar(download.get("version"));
        long expires = nonNegativeLong(download.get("expires"), "VoxelShop download expiry");
        validateDownloadUrl(downloadUrl);
        if (expires <= System.currentTimeMillis() / 1000L) {
            throw new IOException("VoxelShop returned an expired download URL");
        }
        if (downloadVersion == null || !selected.version.equalsIgnoreCase(downloadVersion)) {
            return manual(pluginName, resourceId, selected, gameVersions, actionUrl,
                    "The downloadable VoxelShop version does not match the selected release channel");
        }

        ResolvedUpdate update = resolved(pluginName, resourceId, selected, gameVersions, downloadUrl);
        return Resolution.downloadable(update, actionUrl, expires);
    }

    static boolean recognizes(String sourceUrl) {
        try {
            extractResourceId(sourceUrl);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    static String extractResourceId(String sourceUrl) throws IOException {
        String value = clean(sourceUrl);
        if (value == null) {
            throw new IOException("VoxelShop resource URL is blank");
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid VoxelShop resource URL", failure);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getPort() != -1 || uri.getFragment() != null || !officialHost(uri.getHost())) {
            throw new IOException("Invalid VoxelShop resource URL");
        }
        String path = uri.getPath();
        if (path == null) {
            throw new IOException("VoxelShop resource URL has no resource path");
        }
        String normalizedPath = path.length() > 1 ? path.replaceAll("/+$", "") : path;
        Matcher matcher = firstMatch(normalizedPath, RESOURCE_SLUG, PRODUCT_ID, RESOURCE_ID, SHORT_ID);
        if (matcher == null) {
            throw new IOException("VoxelShop resource URL does not contain a numeric resource ID");
        }
        String id = matcher.group(1);
        try {
            if (Long.parseLong(id) <= 0L) {
                throw new NumberFormatException("non-positive");
            }
        } catch (NumberFormatException failure) {
            throw new IOException("VoxelShop resource ID is invalid");
        }
        return id;
    }

    private Resolution manual(String pluginName,
                              String resourceId,
                              Update selected,
                              List<String> gameVersions,
                              String actionUrl,
                              String reason) {
        return Resolution.manual(resolved(pluginName, resourceId, selected, gameVersions, null), actionUrl, reason);
    }

    private ResolvedUpdate resolved(String pluginName,
                                    String resourceId,
                                    Update selected,
                                    List<String> gameVersions,
                                    String downloadUrl) {
        return ResolvedUpdate.builder(pluginName, "voxelshop", downloadUrl)
                .normalizedSource("voxelshop:" + resourceId)
                .projectId(resourceId)
                .versionId(selected.id)
                .versionLabel(selected.version)
                .releaseType(selected.releaseType())
                .publishedAtMillis(selected.timeSeconds > 0L ? selected.timeSeconds * 1000L : 0L)
                .gameVersions(gameVersions)
                .changelog(selected.changelog)
                .metadataId("voxelshop:" + resourceId + ':' + selected.id)
                .build();
    }

    private static Update selectUpdate(JsonNode updates, EntryOptions options) throws IOException {
        Selection selection = Selection.from(options);
        List<Update> candidates = new ArrayList<Update>();
        for (JsonNode node : updates) {
            if (node == null || !node.isObject() || !booleanValue(node.get("downloadReady"), false)) {
                continue;
            }
            String id = scalar(node.get("id"));
            String version = scalar(node.get("version"));
            if (id == null || version == null) {
                continue;
            }
            boolean beta = booleanValue(node.get("beta"), false);
            boolean snapshot = booleanValue(node.get("snapshot"), false);
            if (!selection.allows(beta, snapshot)) {
                continue;
            }
            long time = lenientNonNegativeLong(node.get("time"));
            String title = plainText(scalar(node.get("title")));
            String description = plainText(scalar(node.get("description")));
            candidates.add(new Update(id, version, time, beta, snapshot, changelog(title, description)));
        }
        Collections.sort(candidates, new Comparator<Update>() {
            @Override
            public int compare(Update left, Update right) {
                int byTime = Long.compare(right.timeSeconds, left.timeSeconds);
                if (byTime != 0) return byTime;
                return Long.compare(numericId(right.id), numericId(left.id));
            }
        });
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private JsonNode postJson(String firstUrl, Map<String, String> form) throws IOException {
        byte[] body = encodeForm(form).getBytes(StandardCharsets.UTF_8);
        URI current;
        try {
            current = new URI(firstUrl);
        } catch (URISyntaxException impossible) {
            throw new IOException("Invalid VoxelShop API endpoint", impossible);
        }
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current.toASCIIString()).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.max(1000, UpdateOptions.connectTimeoutMs));
            connection.setReadTimeout(Math.max(1000, UpdateOptions.readTimeoutMs));
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", PluginDownloader.getEffectiveUserAgent());
            try {
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 307 || status == 308) {
                    if (redirects == MAX_REDIRECTS) {
                        throw new IOException("VoxelShop API exceeded its redirect limit");
                    }
                    String location = connection.getHeaderField("Location");
                    URI redirected = resolveRedirect(current, location);
                    if (!sameOrigin(current, redirected)) {
                        throw new IOException("VoxelShop API refused a cross-origin redirect");
                    }
                    current = redirected;
                    closeResponse(connection);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    closeQuietly(connection.getErrorStream());
                    throw new IOException("VoxelShop API returned HTTP " + status);
                }
                try (InputStream input = connection.getInputStream()) {
                    byte[] responseBody = readLimited(input);
                    final JsonNode parsed;
                    try {
                        parsed = JSON.readTree(responseBody);
                    } catch (IOException malformed) {
                        throw new IOException("VoxelShop API returned malformed JSON");
                    }
                    if (parsed == null || !parsed.isObject()) {
                        throw new IOException("VoxelShop API returned malformed JSON");
                    }
                    return parsed;
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("VoxelShop API exceeded its redirect limit");
    }

    private static URI resolveRedirect(URI current, String location) throws IOException {
        if (location == null || location.trim().isEmpty()) {
            throw new IOException("VoxelShop API redirect had no location");
        }
        final URI redirected;
        try {
            redirected = current.resolve(location.trim());
        } catch (IllegalArgumentException failure) {
            throw new IOException("VoxelShop API redirect was invalid");
        }
        String scheme = redirected.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || redirected.getHost() == null || redirected.getUserInfo() != null
                || ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(scheme))) {
            throw new IOException("VoxelShop API redirect was unsafe");
        }
        return redirected;
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_JSON_BYTES) {
                throw new IOException("VoxelShop API response exceeded the size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static JsonNode successfulPayload(JsonNode root, String field, String description) throws IOException {
        JsonNode response = successfulResponse(root, description);
        JsonNode payload = response.get(field);
        if (payload == null || !payload.isObject()) {
            throw new IOException(description + " returned no " + field + " object");
        }
        return payload;
    }

    private static JsonNode successfulResponse(JsonNode root, String description) throws IOException {
        JsonNode response = responseObject(root, description);
        if (!booleanValue(response.get("success"), false)) {
            throw new IOException(description + " request was unsuccessful");
        }
        return response;
    }

    private static JsonNode responseObject(JsonNode root, String description) throws IOException {
        JsonNode response = root == null ? null : root.get("response");
        if (response == null || !response.isObject()) {
            throw new IOException(description + " returned no response object");
        }
        return response;
    }

    private static BigDecimal nonNegativeDecimal(JsonNode node, String description) throws IOException {
        String value = scalar(node);
        if (value == null) throw new IOException(description + " is missing");
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.signum() < 0) throw new NumberFormatException("negative");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IOException(description + " is invalid");
        }
    }

    private static long nonNegativeLong(JsonNode node, String description) throws IOException {
        String value = scalar(node);
        if (value == null) throw new IOException(description + " is missing");
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0L) throw new NumberFormatException("negative");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IOException(description + " is invalid");
        }
    }

    private static long lenientNonNegativeLong(JsonNode node) {
        try {
            return Math.max(0L, Long.parseLong(scalar(node)));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean booleanValue(JsonNode node, boolean fallback) {
        if (node == null || node.isNull()) return fallback;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.asInt() != 0;
        String value = node.asText("").trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) return true;
        if ("false".equals(value) || "0".equals(value) || "no".equals(value)) return false;
        return fallback;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return Collections.emptyList();
        List<String> values = new ArrayList<String>();
        for (JsonNode item : node) {
            String value = scalar(item);
            if (value != null) values.add(value);
        }
        return values;
    }

    private static String scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isContainerNode()) return null;
        return clean(node.asText());
    }

    private static String plainText(String value) {
        if (value == null) return null;
        String text = Jsoup.parse(value).text().trim();
        return text.isEmpty() ? null : text;
    }

    private static String changelog(String title, String description) {
        String value;
        if (title == null) value = description;
        else if (description == null || title.equals(description)) value = title;
        else value = title + ": " + description;
        if (value == null) return null;
        return value.length() <= 8000 ? value : value.substring(0, 7997) + "...";
    }

    private static long numericId(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static void validateDownloadUrl(String value) throws IOException {
        if (value == null) throw new IOException("VoxelShop download URL is missing");
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IOException("VoxelShop download URL is unsafe");
            }
        } catch (URISyntaxException failure) {
            throw new IOException("VoxelShop download URL is invalid");
        }
    }

    private String endpoint(String action) {
        return apiBase + '/' + action + '/';
    }

    private static Map<String, String> form(String... keysAndValues) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            result.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return result;
    }

    private static String encodeForm(Map<String, String> values) throws IOException {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return result.toString();
    }

    private static String encode(String value) throws IOException {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception impossible) {
            throw new IOException("Unable to encode VoxelShop request", impossible);
        }
    }

    private static Matcher firstMatch(String value, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(value);
            if (matcher.matches()) return matcher;
        }
        return null;
    }

    private static boolean officialHost(String host) {
        if (host == null) return false;
        return "voxel.shop".equalsIgnoreCase(host) || "www.voxel.shop".equalsIgnoreCase(host)
                || "polymart.org".equalsIgnoreCase(host) || "www.polymart.org".equalsIgnoreCase(host);
    }

    private static String trimSlashes(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeResponse(HttpURLConnection connection) {
        InputStream input = connection.getErrorStream();
        if (input == null) {
            try {
                input = connection.getInputStream();
            } catch (IOException ignored) {
                return;
            }
        }
        closeQuietly(input);
    }

    static final class Resolution {
        enum Capability { DOWNLOADABLE, MANUAL_ACTION }

        final ResolvedUpdate update;
        final Capability capability;
        final String actionUrl;
        final String reason;
        final long downloadExpiresAtSeconds;

        private Resolution(ResolvedUpdate update, Capability capability, String actionUrl,
                           String reason, long downloadExpiresAtSeconds) {
            this.update = update;
            this.capability = capability;
            this.actionUrl = actionUrl;
            this.reason = reason;
            this.downloadExpiresAtSeconds = downloadExpiresAtSeconds;
        }

        static Resolution downloadable(ResolvedUpdate update, String actionUrl, long expires) {
            return new Resolution(update, Capability.DOWNLOADABLE, actionUrl, null, expires);
        }

        static Resolution manual(ResolvedUpdate update, String actionUrl, String reason) {
            return new Resolution(update, Capability.MANUAL_ACTION, actionUrl, reason, 0L);
        }

        boolean isDownloadable() {
            return capability == Capability.DOWNLOADABLE;
        }
    }

    private static final class Update {
        private final String id;
        private final String version;
        private final long timeSeconds;
        private final boolean beta;
        private final boolean snapshot;
        private final String changelog;

        private Update(String id, String version, long timeSeconds, boolean beta,
                       boolean snapshot, String changelog) {
            this.id = id;
            this.version = version;
            this.timeSeconds = timeSeconds;
            this.beta = beta;
            this.snapshot = snapshot;
            this.changelog = changelog;
        }

        private String releaseType() {
            if (snapshot) return "snapshot";
            if (beta) return "beta";
            return "release";
        }
    }

    private static final class Selection {
        private enum Mode { STABLE, BETA, SNAPSHOT, ALLOWED }

        private final Mode mode;
        private final boolean allowBeta;
        private final boolean allowSnapshot;

        private Selection(Mode mode, boolean allowBeta, boolean allowSnapshot) {
            this.mode = mode;
            this.allowBeta = allowBeta;
            this.allowSnapshot = allowSnapshot;
        }

        private static Selection from(EntryOptions options) throws IOException {
            String channel = clean(options.first("channel"));
            if (channel != null) {
                String normalized = channel.toLowerCase(Locale.ROOT);
                if ("stable".equals(normalized) || "release".equals(normalized)) {
                    return new Selection(Mode.STABLE, false, false);
                }
                if ("beta".equals(normalized) || "preview".equals(normalized)
                        || "prerelease".equals(normalized) || "pre-release".equals(normalized)) {
                    return new Selection(Mode.BETA, true, false);
                }
                if ("snapshot".equals(normalized) || "alpha".equals(normalized)
                        || "nightly".equals(normalized)) {
                    return new Selection(Mode.SNAPSHOT, false, true);
                }
                if ("latest".equals(normalized) || "all".equals(normalized)) {
                    return new Selection(Mode.ALLOWED, true, true);
                }
                throw new IOException("Unsupported VoxelShop release channel");
            }
            boolean all = options.bool("latest", false) || options.bool("prerelease", false)
                    || options.bool("pre-release", false);
            boolean beta = all || options.bool("beta", UpdateOptions.allowPreReleaseDefault);
            boolean snapshot = all || options.bool("snapshot", false)
                    || options.bool("alpha", UpdateOptions.allowPreReleaseDefault);
            return new Selection(Mode.ALLOWED, beta, snapshot);
        }

        private boolean allows(boolean beta, boolean snapshot) {
            if (mode == Mode.STABLE) return !beta && !snapshot;
            if (mode == Mode.BETA) return beta && !snapshot;
            if (mode == Mode.SNAPSHOT) return snapshot;
            if (snapshot) return allowSnapshot;
            if (beta) return allowBeta;
            return true;
        }
    }
}
