package common;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/** Resolves stable, lightweight metadata for direct HTTP downloads using HEAD. */
final class DirectUrlProvider {
    private static final int MAX_REDIRECTS = 5;

    ResolvedUpdate resolve(String pluginName, String source) throws IOException {
        final URI original = parseHttpUri(source);
        URI current = original;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if ("https".equalsIgnoreCase(original.getScheme())
                    && "http".equalsIgnoreCase(current.getScheme())) {
                throw new IOException("Refusing insecure direct metadata redirect");
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(current.toASCIIString()).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(Math.max(1000, UpdateOptions.connectTimeoutMs));
            connection.setReadTimeout(Math.max(1000, UpdateOptions.readTimeoutMs));
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("User-Agent", PluginDownloader.getEffectiveUserAgent());
            if (sameOrigin(original, current)) {
                for (Map.Entry<String, String> header : PluginDownloader.getExtraHeaders().entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }

            try {
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == MAX_REDIRECTS) return null;
                    URI next;
                    try {
                        next = current.resolve(location);
                    } catch (RuntimeException invalid) {
                        return null;
                    }
                    if (!isHttp(next)) return null;
                    current = next;
                    continue;
                }
                if (status < 200 || status >= 300) return null;

                String etag = strongEtag(connection.getHeaderField("ETag"));
                String lastModified = clean(connection.getHeaderField("Last-Modified"));
                if (etag == null && lastModified == null) return null;
                long length = contentLength(connection);
                String metadata = fingerprint(etag, lastModified, length);
                String fileName = contentDispositionFileName(connection.getHeaderField("Content-Disposition"));
                if (fileName == null) fileName = pathFileName(current.getPath());

                return ResolvedUpdate.builder(pluginName, "direct", current.toASCIIString())
                        .normalizedSource(redactSource(original))
                        .projectId(redactSource(original))
                        .metadataId(metadata)
                        .fileName(fileName)
                        .publishedAtMillis(Math.max(0L, connection.getLastModified()))
                        .size(length)
                        .sha256(clean(firstHeader(connection, "X-Checksum-SHA256", "X-Checksum-Sha256")))
                        .sha1(clean(firstHeader(connection, "X-Checksum-SHA1", "X-Checksum-Sha1")))
                        .md5(clean(firstHeader(connection, "X-Checksum-MD5")))
                        .gameVersions(Collections.<String>emptyList())
                        .loaders(Collections.<String>emptyList())
                        .build();
            } finally {
                connection.disconnect();
            }
        }
        return null;
    }

    private static URI parseHttpUri(String source) throws IOException {
        try {
            URI uri = new URI(source == null ? "" : source.trim());
            if (!isHttp(uri) || uri.getHost() == null) throw new IOException("Direct URL must use HTTP or HTTPS");
            return uri;
        } catch (URISyntaxException invalid) {
            throw new IOException("Invalid direct URL", invalid);
        }
    }

    private static boolean isHttp(URI uri) {
        return uri != null && ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307 || status == 308;
    }

    private static boolean sameOrigin(URI first, URI second) {
        if (first == null || second == null) return false;
        return equal(first.getScheme(), second.getScheme())
                && equal(first.getHost(), second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static boolean equal(String first, String second) {
        return first == null ? second == null : second != null && first.equalsIgnoreCase(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String redactSource(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toASCIIString();
        } catch (URISyntaxException impossible) {
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPath() == null ? "" : uri.getPath());
        }
    }

    private static long contentLength(HttpURLConnection connection) {
        String raw = clean(connection.getHeaderField("Content-Length"));
        if (raw == null) return -1L;
        try {
            long parsed = Long.parseLong(raw);
            return parsed >= 0L ? parsed : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String fingerprint(String etag, String lastModified, long length) {
        String material = "etag=" + value(etag) + "\nlastModified=" + value(lastModified)
                + "\nlength=" + length;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder result = new StringBuilder("head:");
            for (byte item : digest.digest(material.getBytes(StandardCharsets.UTF_8))) {
                result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String firstHeader(HttpURLConnection connection, String... names) {
        for (String name : names) {
            String value = connection.getHeaderField(name);
            if (clean(value) != null) return value;
        }
        return null;
    }

    private static String contentDispositionFileName(String header) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            int equals = trimmed.indexOf('=');
            if (equals <= 0 || !"filename".equalsIgnoreCase(trimmed.substring(0, equals).trim())) continue;
            String value = trimmed.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            return safeFileName(value);
        }
        return null;
    }

    private static String pathFileName(String path) {
        if (path == null || path.isEmpty() || path.endsWith("/")) return null;
        int slash = path.lastIndexOf('/');
        return safeFileName(slash < 0 ? path : path.substring(slash + 1));
    }

    private static String safeFileName(String value) {
        String cleaned = clean(value);
        if (cleaned == null || cleaned.contains("/") || cleaned.contains("\\")) return null;
        return cleaned;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned.replace("\"", "");
    }

    private static String strongEtag(String value) {
        String cleaned = clean(value);
        return cleaned != null && cleaned.regionMatches(true, 0, "W/", 0, 2)
                ? null : cleaned;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
