package common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GitHubProviderTest {
    private MockGitHub github;
    private boolean originalAllowPreRelease;
    private Logger logger;
    private RecordingHandler logs;

    @Before
    public void setUp() throws IOException {
        github = new MockGitHub();
        originalAllowPreRelease = UpdateOptions.allowPreReleaseDefault;
        UpdateOptions.allowPreReleaseDefault = false;
        logger = Logger.getLogger(getClass().getName() + '.' + System.nanoTime());
        logger.setUseParentHandlers(false);
        logs = new RecordingHandler();
        logger.addHandler(logs);
    }

    @After
    public void tearDown() {
        UpdateOptions.allowPreReleaseDefault = originalAllowPreRelease;
        if (github != null) {
            github.close();
        }
    }

    @Test
    public void parsesRepositoryPagesGitSuffixAndBracketSelector() throws Exception {
        assertEquals("Owner/Project", GitHubProvider.extractRepository(
                "https://github.com/Owner/Project/releases/latest"));
        assertEquals("owner/repo", GitHubProvider.extractRepository(
                "https://www.github.com/owner/repo.git[2]"));

        try {
            GitHubProvider.extractRepository("https://gitlab.com/owner/repo");
            fail("Expected a non-GitHub host to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("github.com"));
        }
    }

    @Test
    public void stableReleaseIsDefaultAndMetadataIsComplete() throws Exception {
        String secret = "named-token-do-not-log";
        github.requireToken(secret);
        github.page(1, array(
                release(20, "v2.0.0-beta.1", true, false, "2026-08-20T00:00:00Z",
                        "Beta notes", asset(201, "plugin-beta.jar", 200, "sha256:beta", "uploaded")),
                release(10, "v1.5.0", false, false, "2026-08-19T00:00:00Z",
                        "Stable release notes", asset(101, "plugin-paper.jar", 1234,
                                "sha256:stable-digest", "uploaded"))));

        ResolvedUpdate update = provider().resolve("Plugin",
                "https://github.com/Owner/Project/releases", secret);

        assertEquals("github", update.provider);
        assertEquals("github:Owner/Project", update.normalizedSource);
        assertEquals("Owner/Project", update.projectId);
        assertEquals("10", update.versionId);
        assertEquals("v1.5.0", update.versionLabel);
        assertEquals("release", update.releaseType);
        assertEquals(Instant.parse("2026-08-19T00:00:00Z").toEpochMilli(), update.publishedAtMillis);
        assertEquals("plugin-paper.jar", update.fileName);
        assertEquals(1234L, update.size);
        assertEquals("stable-digest", update.sha256);
        assertEquals("Stable release notes", update.changelog);
        assertEquals(1, update.releaseNotes.size());
        assertEquals("v1.5.0", update.releaseNotes.get(0).versionLabel);
        assertEquals("Stable release notes", update.releaseNotes.get(0).changelog);
        assertTrue(update.releaseNotesComplete);
        assertEquals(github.url("/assets/101"), update.downloadUrl);
        assertEquals("github:Owner/Project:release:10:asset:101", update.metadataId());

        assertEquals(1, github.requests().size());
        RecordedRequest request = github.requests().get(0);
        assertEquals("Bearer " + secret, request.authorization);
        assertEquals("application/vnd.github+json", request.accept);
        assertFalse(request.uri.toString().contains(secret));
        assertFalse(logs.joined().contains(secret));
    }

    @Test
    public void exposesNotesForEverySelectableReleaseInTheConfiguredChannel() throws Exception {
        github.page(1, array(
                release(30, "v3.0.0", false, false, "2026-08-21T00:00:00Z",
                        "No matching artifact", asset(301, "velocity.jar", 30, null, "uploaded")),
                release(20, "v2.0.0", false, false, "2026-08-20T00:00:00Z",
                        "Second notes", asset(201, "paper.jar", 20, null, "uploaded")),
                release(10, "v1.0.0", false, false, "2026-08-19T00:00:00Z",
                        "First notes", asset(101, "paper.jar", 10, null, "uploaded"))));

        ResolvedUpdate update = provider().resolve("Plugin",
                "https://github.com/Owner/Project?get=paper\\.jar$", null);

        assertEquals("v2.0.0", update.versionLabel);
        assertEquals(2, update.releaseNotes.size());
        assertEquals("v2.0.0", update.releaseNotes.get(0).versionLabel);
        assertEquals("Second notes", update.releaseNotes.get(0).changelog);
        assertEquals("v1.0.0", update.releaseNotes.get(1).versionLabel);
        assertEquals("First notes", update.releaseNotes.get(1).changelog);
    }

    @Test
    public void boundsRetainedHistoryAndMarksTruncatedBodiesAsIncomplete() throws Exception {
        String longSelectedBody = repeat('x', 9000);
        String[] firstPage = new String[100];
        String[] secondPage = new String[30];
        for (int index = 0; index < 130; index++) {
            long id = 300L - index;
            String body = index == 0 ? longSelectedBody : "Notes " + id;
            String value = release(id, "v" + id + ".0.0", false, false,
                    "2026-08-19T00:00:00Z", body,
                    asset(1000L + id, "plugin.jar", id, null, "uploaded"));
            if (index < firstPage.length) firstPage[index] = value;
            else secondPage[index - firstPage.length] = value;
        }
        github.page(1, array(firstPage),
                "<" + github.apiBase() + "/repos/Owner/Project/releases?page=2>; rel=\"next\"");
        github.page(2, array(secondPage));

        ResolvedUpdate update = provider().resolve("Plugin",
                "https://github.com/Owner/Project", null);

        assertEquals("300", update.versionId);
        assertEquals(longSelectedBody, update.changelog);
        assertEquals(128, update.releaseNotes.size());
        assertEquals("300", update.releaseNotes.get(0).versionId);
        assertTrue(update.releaseNotes.get(0).changelogTruncated);
        assertEquals(8000, update.releaseNotes.get(0).changelog.length());
        assertFalse(update.releaseNotesComplete);
        assertEquals(2, github.requests().size());
    }

    @Test
    public void explicitChannelsAndPrereleaseSelectTheRequestedTier() throws Exception {
        github.page(1, array(
                release(30, "v3.0.0-alpha.1", true, false, "2026-08-21T00:00:00Z",
                        "Alpha", asset(301, "alpha.jar", 30, null, "uploaded")),
                release(20, "v2.0.0-rc.1", true, false, "2026-08-20T00:00:00Z",
                        "RC", asset(201, "beta.jar", 20, null, "uploaded")),
                release(10, "v1.0.0", false, false, "2026-08-19T00:00:00Z",
                        "Stable", asset(101, "stable.jar", 10, null, "uploaded"))));

        assertEquals("alpha.jar", provider().resolve("Plugin",
                "https://github.com/Owner/Project?channel=alpha", null).fileName);
        assertEquals("beta.jar", provider().resolve("Plugin",
                "https://github.com/Owner/Project?channel=beta", null).fileName);
        assertEquals("alpha.jar", provider().resolve("Plugin",
                "https://github.com/Owner/Project?prerelease=true", null).fileName);
        assertEquals("stable.jar", provider().resolve("Plugin",
                "https://github.com/Owner/Project?prerelease=false", null).fileName);
    }

    @Test
    public void getRegexBracketAndIndexSelectReleaseAssetsDeterministically() throws Exception {
        github.page(1, array(release(10, "v1.0.0", false, false,
                "2026-08-19T00:00:00Z", "Notes",
                asset(101, "plugin-api.jar", 1, null, "uploaded"),
                asset(102, "plugin-paper.jar", 2, null, "uploaded"),
                asset(103, "plugin-velocity.jar", 3, null, "uploaded"))));

        assertEquals("plugin-paper.jar", provider().resolve("Plugin",
                "https://github.com/Owner/Project?get=paper.*%5C.jar$", null).fileName);
        assertEquals("plugin-velocity.jar", provider().resolve("Plugin",
                "https://github.com/Owner/Project?get=paper%7Cvelocity&index=2", null).fileName);
        assertEquals("plugin-paper.jar", provider().resolve("Plugin",
                "https://github.com/Owner/Project[2]", null).fileName);
        assertEquals("plugin-velocity.jar", provider().resolve("Plugin",
                "https://github.com/Owner/Project[2]?index=3", null).fileName);
    }

    @Test
    public void followsPaginationWithoutFollowingAnUntrustedLinkTarget() throws Exception {
        github.page(1, array(release(20, "v2.0.0", false, false,
                "2026-08-20T00:00:00Z", "No jars",
                asset(201, "checksums.txt", 1, null, "uploaded"))),
                "<https://attacker.invalid/steal?page=2>; rel=\"next\"");
        github.page(2, array(release(10, "v1.0.0", false, false,
                "2026-08-19T00:00:00Z", "Page two",
                asset(101, "plugin.jar", 10, null, "uploaded"))));

        ResolvedUpdate update = provider().resolve("Plugin",
                "https://github.com/Owner/Project", "safe-token");

        assertEquals("plugin.jar", update.fileName);
        assertEquals(2, github.requests().size());
        assertEquals("1", queryValue(github.requests().get(0).uri, "page"));
        assertEquals("2", queryValue(github.requests().get(1).uri, "page"));
        for (RecordedRequest request : github.requests()) {
            assertEquals("127.0.0.1", request.uri.getHost());
            assertEquals("Bearer safe-token", request.authorization);
        }
    }

    @Test
    public void draftsIncompleteAssetsAndInvalidRegexFailSafely() throws Exception {
        github.page(1, array(
                release(30, "v3", false, true, "2026-08-21T00:00:00Z", "Draft",
                        asset(301, "draft.jar", 3, null, "uploaded")),
                release(20, "v2", false, false, "2026-08-20T00:00:00Z", "Bad state",
                        asset(201, "processing.jar", 2, null, "new"))));

        try {
            provider().resolve("Plugin", "https://github.com/Owner/Project?get=[", "secret");
            fail("Expected invalid regex to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("get-regex"));
            assertFalse(expected.getMessage().contains("secret"));
        }
        assertEquals(0, github.requests().size());

        try {
            provider().resolve("Plugin", "https://github.com/Owner/Project", "secret");
            fail("Expected missing eligible assets to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("No GitHub release JAR asset"));
            assertFalse(expected.getMessage().contains("secret"));
        }
        assertFalse(logs.joined().contains("secret"));
    }

    private GitHubProvider provider() {
        return new GitHubProvider(logger, github.apiBase());
    }

    private String release(long id,
                           String tag,
                           boolean prerelease,
                           boolean draft,
                           String published,
                           String body,
                           String... assets) {
        return "{\"id\":" + id + ",\"tag_name\":\"" + tag + "\","
                + "\"prerelease\":" + prerelease + ",\"draft\":" + draft + ','
                + "\"published_at\":\"" + published + "\",\"body\":\"" + body + "\","
                + "\"assets\":" + array(assets) + '}';
    }

    private String asset(long id, String name, long size, String digest, String state) {
        StringBuilder value = new StringBuilder("{\"id\":").append(id)
                .append(",\"name\":\"").append(name).append("\",\"size\":").append(size)
                .append(",\"state\":\"").append(state).append("\",\"url\":\"")
                .append(github.url("/assets/" + id)).append("\",\"browser_download_url\":\"")
                .append(github.url("/downloads/" + name)).append('"');
        if (digest != null) {
            value.append(",\"digest\":\"").append(digest).append('"');
        }
        return value.append('}').toString();
    }

    private static String array(String... values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) result.append(',');
            result.append(values[i]);
        }
        return result.append(']').toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static String queryValue(URI uri, String key) {
        String query = uri == null ? null : uri.getRawQuery();
        if (query == null) return null;
        for (String parameter : query.split("&")) {
            int equals = parameter.indexOf('=');
            if (equals > 0 && key.equals(parameter.substring(0, equals))) {
                return parameter.substring(equals + 1);
            }
        }
        return null;
    }

    private static final class MockGitHub implements AutoCloseable {
        private final HttpServer server;
        private final Map<Integer, Response> pages =
                Collections.synchronizedMap(new LinkedHashMap<Integer, Response>());
        private final List<RecordedRequest> requests =
                Collections.synchronizedList(new ArrayList<RecordedRequest>());
        private volatile String requiredToken;

        private MockGitHub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    URI uri = URI.create(apiBase() + exchange.getRequestURI().toString());
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    String accept = exchange.getRequestHeaders().getFirst("Accept");
                    requests.add(new RecordedRequest(uri, authorization, accept));
                    if (requiredToken != null && !("Bearer " + requiredToken).equals(authorization)) {
                        write(exchange, 401, "{\"message\":\"unauthorized\"}", null);
                        return;
                    }
                    if (!"/repos/Owner/Project/releases".equals(exchange.getRequestURI().getRawPath())) {
                        write(exchange, 404, "{\"message\":\"not found\"}", null);
                        return;
                    }
                    int page = 1;
                    String rawPage = queryValue(exchange.getRequestURI(), "page");
                    if (rawPage != null) {
                        try {
                            page = Integer.parseInt(rawPage);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    Response response = pages.get(page);
                    if (response == null) {
                        write(exchange, 200, "[]", null);
                    } else {
                        write(exchange, 200, response.body, response.link);
                    }
                }
            });
            server.start();
        }

        private String apiBase() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private String url(String path) {
            return apiBase() + path;
        }

        private void requireToken(String token) {
            requiredToken = token;
        }

        private void page(int number, String body) {
            page(number, body, null);
        }

        private void page(int number, String body, String link) {
            pages.put(number, new Response(body, link));
        }

        private List<RecordedRequest> requests() {
            synchronized (requests) {
                return new ArrayList<RecordedRequest>(requests);
            }
        }

        private static void write(HttpExchange exchange,
                                  int status,
                                  String body,
                                  String link) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (link != null) {
                exchange.getResponseHeaders().set("Link", link);
            }
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class Response {
        private final String body;
        private final String link;

        private Response(String body, String link) {
            this.body = body;
            this.link = link;
        }
    }

    private static final class RecordedRequest {
        private final URI uri;
        private final String authorization;
        private final String accept;

        private RecordedRequest(URI uri, String authorization, String accept) {
            this.uri = uri;
            this.authorization = authorization;
            this.accept = accept;
        }
    }

    private static final class RecordingHandler extends Handler {
        private final StringBuilder messages = new StringBuilder();

        @Override
        public void publish(LogRecord record) {
            if (record != null) {
                messages.append(record.getMessage()).append('\n');
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private String joined() {
            return messages.toString();
        }
    }
}
