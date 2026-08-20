package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class ProviderSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProviderSupport() {}

    static JsonNode getJson(String url) throws IOException {
        return getJson(url, Collections.<String, String>emptyMap());
    }

    static JsonNode getJson(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(Math.max(1000, UpdateOptions.connectTimeoutMs));
        connection.setReadTimeout(Math.max(1000, UpdateOptions.readTimeoutMs));
        // Provider API endpoints are canonical. Refuse redirects so global or
        // provider-specific headers cannot be replayed to another origin.
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", PluginDownloader.getEffectiveUserAgent());
        for (Map.Entry<String, String> header : PluginDownloader.getExtraHeaders().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getValue() != null && !header.getValue().trim().isEmpty()) {
                    connection.setRequestProperty(header.getKey(), header.getValue().trim());
                }
            }
        }
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("Provider returned HTTP " + status);
        }
        try (InputStream in = connection.getInputStream()) {
            return JSON.readTree(in);
        } finally {
            connection.disconnect();
        }
    }

    static String encode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
    }

    static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String result = value.asText(null);
        return result == null || result.trim().isEmpty() ? null : result.trim();
    }

    static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return Collections.emptyList();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item == null ? null : item.asText(null);
            if (value != null && !value.trim().isEmpty()) values.add(value.trim());
        }
        return values;
    }

    static long instantMillis(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            return Instant.parse(value.trim()).toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }
}
