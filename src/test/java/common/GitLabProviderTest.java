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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GitLabProviderTest {
    private MockGitLab gitLab;
    private Logger logger;
    private RecordingLogHandler logs;

    @Before
    public void setUp() throws IOException {
        gitLab = new MockGitLab();
        logger = Logger.getLogger(getClass().getName() + '.' + System.nanoTime());
        logger.setUseParentHandlers(false);
        logs = new RecordingLogHandler();
        logger.addHandler(logs);
    }

    @After
    public void tearDown() {
        if (gitLab != null) {
            gitLab.close();
        }
    }

    @Test
    public void parsesNestedProjectUrlsAndPackagePages() throws Exception {
        assertEquals("eagles-creative/team/opencreative",
                GitLabProvider.extractProjectPath(
                        "https://gitlab.com/eagles-creative/team/opencreative/-/packages"));
        assertEquals("eagles-creative/team/opencreative",
                GitLabProvider.extractProjectPath(
                        "https://www.gitlab.com/eagles-creative/team/opencreative.git"));
    }

    @Test
    public void resolvesNewestMatchingGenericJarAndPreservesMetadata() throws Exception {
        gitLab.respond("/api/v4/projects/eagles-creative%2Fteam%2Fopencreative/packages", "["
                + "{\"id\":10,\"name\":\"releases\",\"version\":\"1.0.0\","
                + "\"package_type\":\"generic\",\"status\":\"default\","
                + "\"created_at\":\"2025-01-01T00:00:00Z\"},"
                + "{\"id\":20,\"name\":\"releases\",\"version\":\"2.0.0\","
                + "\"package_type\":\"generic\",\"status\":\"default\","
                + "\"created_at\":\"2026-01-01T00:00:00Z\"}"
                + "]");
        gitLab.respond("/api/v4/projects/eagles-creative%2Fteam%2Fopencreative/packages/20/package_files", "["
                + "{\"id\":202,\"file_name\":\"opencreative-universal.jar\","
                + "\"size\":400,\"created_at\":\"2026-01-03T00:00:00Z\"},"
                + "{\"id\":201,\"file_name\":\"opencreative-paper.jar\",\"size\":321,"
                + "\"file_sha256\":\"sha256-value\",\"file_sha1\":\"sha1-value\","
                + "\"file_md5\":\"md5-value\",\"created_at\":\"2026-01-02T00:00:00Z\"},"
                + "{\"id\":203,\"file_name\":\"opencreative.pom\","
                + "\"created_at\":\"2026-01-04T00:00:00Z\"}"
                + "]");

        GitLabProvider provider = new GitLabProvider(logger, gitLab.apiBase());
        ResolvedUpdate update = provider.resolve("OpenCreative",
                "https://gitlab.com/eagles-creative/team/opencreative/-/packages?get=paper.*\\.jar",
                null);

        assertEquals("gitlab", update.provider);
        assertEquals("gitlab:eagles-creative/team/opencreative", update.normalizedSource);
        assertEquals("eagles-creative/team/opencreative", update.projectId);
        assertEquals("20:201", update.versionId);
        assertEquals("2.0.0", update.versionLabel);
        assertEquals("generic", update.releaseType);
        assertEquals("opencreative-paper.jar", update.fileName);
        assertEquals(321L, update.size);
        assertEquals("sha256-value", update.sha256);
        assertEquals("sha1-value", update.sha1);
        assertEquals("md5-value", update.md5);
        assertEquals(gitLab.apiBase()
                        + "/projects/eagles-creative%2Fteam%2Fopencreative/packages/generic/"
                        + "releases/2.0.0/opencreative-paper.jar",
                update.downloadUrl);
        assertEquals("gitlab:eagles-creative/team/opencreative:20:201:sha256-value",
                update.metadataId());
        assertEquals(2, gitLab.requests().size());
    }

    @Test
    public void resolvesMavenPackageByOneBasedIndexAcrossPaginatedFiles() throws Exception {
        gitLab.respond("/api/v4/projects/group%2Fproject/packages", "["
                + "{\"id\":30,\"name\":\"com/eagles/opencreative\",\"version\":\"3.0.0\","
                + "\"package_type\":\"maven\",\"status\":\"default\","
                + "\"created_at\":\"2026-02-01T00:00:00Z\"}"
                + "]");
        gitLab.respondPage("/api/v4/projects/group%2Fproject/packages/30/package_files", "1", "["
                + "{\"id\":302,\"file_name\":\"opencreative-all.jar\","
                + "\"created_at\":\"2026-02-03T00:00:00Z\"}"
                + "]", "2");
        gitLab.respondPage("/api/v4/projects/group%2Fproject/packages/30/package_files", "2", "["
                + "{\"id\":301,\"file_name\":\"opencreative-paper.jar\",\"size\":900,"
                + "\"file_sha256\":\"paper-sha256\","
                + "\"created_at\":\"2026-02-02T00:00:00Z\"}"
                + "]", null);

        GitLabProvider provider = new GitLabProvider(logger, gitLab.apiBase());
        ResolvedUpdate update = provider.resolve("OpenCreative",
                "https://gitlab.com/group/project/-/packages?packageType=maven&index=2",
                null);

        assertEquals("maven", update.releaseType);
        assertEquals("opencreative-paper.jar", update.fileName);
        assertEquals("3.0.0", update.versionLabel);
        assertEquals(gitLab.apiBase()
                        + "/projects/group%2Fproject/packages/maven/com/eagles/opencreative/3.0.0/"
                        + "opencreative-paper.jar",
                update.downloadUrl);
        assertEquals(3, gitLab.requests().size());
        assertTrue(gitLab.requests().get(2).query.contains("page=2"));
    }

    @Test
    public void sendsPrivateTokenOnlyAsHeaderAndNeverLogsIt() throws Exception {
        String secret = "glpat-do-not-log-this";
        gitLab.requireToken(secret);
        gitLab.respond("/api/v4/projects/group%2Fprivate-project/packages", "["
                + "{\"id\":40,\"name\":\"private-release\",\"version\":\"4.0\","
                + "\"package_type\":\"generic\",\"status\":\"default\","
                + "\"created_at\":\"2026-03-01T00:00:00Z\"}"
                + "]");
        gitLab.respond("/api/v4/projects/group%2Fprivate-project/packages/40/package_files", "["
                + "{\"id\":401,\"file_name\":\"private.jar\","
                + "\"created_at\":\"2026-03-02T00:00:00Z\"}"
                + "]");

        GitLabProvider provider = new GitLabProvider(logger, gitLab.apiBase());
        ResolvedUpdate update = provider.resolve("Private",
                "https://gitlab.com/group/private-project/-/packages", secret);

        assertEquals("private.jar", update.fileName);
        assertEquals(2, gitLab.requests().size());
        for (RecordedRequest request : gitLab.requests()) {
            assertEquals(secret, request.privateToken);
            assertFalse(request.rawPath.contains(secret));
            assertFalse(request.query.contains(secret));
        }
        assertFalse(logs.joined().contains(secret));
    }

    private static final class MockGitLab implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, Response> responses =
                Collections.synchronizedMap(new LinkedHashMap<String, Response>());
        private final List<RecordedRequest> requests =
                Collections.synchronizedList(new ArrayList<RecordedRequest>());
        private volatile String requiredToken;

        private MockGitLab() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    String rawPath = exchange.getRequestURI().getRawPath();
                    String query = exchange.getRequestURI().getRawQuery();
                    String token = exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN");
                    requests.add(new RecordedRequest(rawPath, query == null ? "" : query, token));
                    if (requiredToken != null && !requiredToken.equals(token)) {
                        write(exchange, 401, "{\"message\":\"unauthorized\"}", null);
                        return;
                    }
                    Response response = responses.get(key(rawPath, query));
                    if (response == null) {
                        response = responses.get(key(rawPath, null));
                    }
                    if (response == null) {
                        write(exchange, 404, "{\"message\":\"not found\"}", null);
                        return;
                    }
                    write(exchange, 200, response.body, response.nextPage);
                }
            });
            server.start();
        }

        private String apiBase() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4";
        }

        private void respond(String rawPath, String body) {
            responses.put(key(rawPath, null), new Response(body, null));
        }

        private void respondPage(String rawPath, String page, String body, String nextPage) {
            responses.put(key(rawPath, "page=" + page), new Response(body, nextPage));
        }

        private void requireToken(String value) {
            requiredToken = value;
        }

        private List<RecordedRequest> requests() {
            synchronized (requests) {
                return new ArrayList<RecordedRequest>(requests);
            }
        }

        private static String key(String rawPath, String query) {
            if (query == null) {
                return rawPath;
            }
            for (String parameter : query.split("&")) {
                if (parameter.startsWith("page=")) {
                    return rawPath + '?' + parameter;
                }
            }
            return rawPath;
        }

        private static void write(HttpExchange exchange, int status, String body, String nextPage)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (nextPage != null) {
                exchange.getResponseHeaders().set("X-Next-Page", nextPage);
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
        private final String nextPage;

        private Response(String body, String nextPage) {
            this.body = body;
            this.nextPage = nextPage;
        }
    }

    private static final class RecordedRequest {
        private final String rawPath;
        private final String query;
        private final String privateToken;

        private RecordedRequest(String rawPath, String query, String privateToken) {
            this.rawPath = rawPath;
            this.query = query;
            this.privateToken = privateToken;
        }
    }

    private static final class RecordingLogHandler extends Handler {
        private final StringBuilder messages = new StringBuilder();

        @Override
        public void publish(LogRecord record) {
            if (record != null && record.getLevel().intValue() >= Level.ALL.intValue()) {
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
