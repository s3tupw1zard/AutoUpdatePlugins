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
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JenkinsProviderTest {
    private MockJenkins jenkins;
    private Logger logger;
    private RecordingHandler logs;

    @Before
    public void setUp() throws IOException {
        jenkins = new MockJenkins();
        logger = Logger.getLogger(getClass().getName() + '.' + System.nanoTime());
        logger.setUseParentHandlers(false);
        logs = new RecordingHandler();
        logger.addHandler(logs);
    }

    @After
    public void tearDown() {
        if (jenkins != null) {
            jenkins.close();
        }
    }

    @Test
    public void canonicalizesNestedJobBuildAndArtifactUrls() throws Exception {
        String root = jenkins.url("/jenkins/job/Folder/job/My%20Plugin/");
        assertEquals(root, JenkinsProvider.canonicalJobRoot(root));
        assertEquals(root, JenkinsProvider.canonicalJobRoot(
                jenkins.url("/jenkins/job/Folder/job/My%20Plugin/417/")));
        assertEquals(root, JenkinsProvider.canonicalJobRoot(
                jenkins.url("/jenkins/job/Folder/job/My%20Plugin/lastSuccessfulBuild/artifact/libs/plugin.jar")));
        assertEquals(root, JenkinsProvider.canonicalJobRoot(
                jenkins.url("/jenkins/job/Folder/job/My%20Plugin/lastSuccessfulBuild/artifact/[2]")));
    }

    @Test
    public void resolvesBuildSpecificJarWithMetadataAndChanges() throws Exception {
        jenkins.response(buildWithChanges(417,
                "Nightly build",
                "Fix compatibility", "Harden downloads",
                artifact("plugin-api.jar", "api/plugin-api.jar", -1),
                artifact("plugin paper.jar", "build/libs/plugin paper.jar", 2048)));

        ResolvedUpdate update = provider().resolve("Plugin",
                jenkins.url("/jenkins/job/Folder/job/My%20Plugin/lastSuccessfulBuild/artifact/"
                        + "?get=paper.*%5C.jar$"));

        String root = jenkins.url("/jenkins/job/Folder/job/My%20Plugin/");
        assertEquals("jenkins", update.provider);
        assertEquals(root, update.normalizedSource);
        assertEquals(root, update.projectId);
        assertEquals("417", update.versionId);
        assertEquals("417", update.versionLabel);
        assertEquals("build", update.releaseType);
        assertEquals(Instant.parse("2026-08-19T12:00:00Z").toEpochMilli(), update.publishedAtMillis);
        assertEquals("plugin paper.jar", update.fileName);
        assertEquals(2048L, update.size);
        assertEquals("Nightly build\nFix compatibility\nHarden downloads", update.changelog);
        assertEquals(root + "417/artifact/build/libs/plugin%20paper.jar", update.downloadUrl);
        assertEquals("jenkins:" + root + ":417:build/libs/plugin paper.jar", update.metadataId());

        assertEquals(1, jenkins.requests().size());
        RecordedRequest request = jenkins.requests().get(0);
        assertEquals("/jenkins/job/Folder/job/My%20Plugin/lastSuccessfulBuild/api/json",
                request.uri.getRawPath());
        assertTrue(request.uri.getRawQuery().startsWith("tree="));
    }

    @Test
    public void combinesRegexWithOneBasedBracketAndExplicitIndexSelectors() throws Exception {
        jenkins.response(build(50, null,
                artifact("plugin-api.jar", "out/plugin-api.jar", -1),
                artifact("plugin-paper.jar", "out/plugin-paper.jar", -1),
                artifact("plugin-paper-all.jar", "out/plugin-paper-all.jar", -1)));

        assertEquals("plugin-paper-all.jar", provider().resolve("Plugin",
                jenkins.url("/job/Plugin/[2]?get=paper")).fileName);
        assertEquals("plugin-paper.jar", provider().resolve("Plugin",
                jenkins.url("/job/Plugin/[2]?get=paper&index=1")).fileName);
    }

    @Test
    public void artifactUrlImplicitlyKeepsTheSameArtifactPathOnLatestBuild() throws Exception {
        jenkins.response(build(51, null,
                artifact("first.jar", "out/first.jar", -1),
                artifact("chosen.jar", "dist/chosen.jar", -1)));

        ResolvedUpdate update = provider().resolve("Plugin",
                jenkins.url("/job/Plugin/12/artifact/dist/chosen.jar"));

        assertEquals("chosen.jar", update.fileName);
        assertEquals(jenkins.url("/job/Plugin/51/artifact/dist/chosen.jar"), update.downloadUrl);
    }

    @Test
    public void sendsBasicAuthenticationOnlyInHeaderAndNeverLogsIt() throws Exception {
        String username = "build-user";
        String token = "jenkins-token-do-not-log";
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (username + ':' + token).getBytes(StandardCharsets.UTF_8));
        jenkins.requireAuthorization(expected);
        jenkins.response(build(99, null, artifact("private.jar", "private.jar", -1)));

        ResolvedUpdate update = provider().resolve("Private",
                jenkins.url("/job/Private/"), username, token);

        assertEquals("private.jar", update.fileName);
        assertEquals(expected, jenkins.requests().get(0).authorization);
        assertFalse(jenkins.requests().get(0).uri.toString().contains(username));
        assertFalse(jenkins.requests().get(0).uri.toString().contains(token));
        assertFalse(update.downloadUrl.contains(username));
        assertFalse(update.downloadUrl.contains(token));
        assertFalse(logs.joined().contains(username));
        assertFalse(logs.joined().contains(token));

        try {
            provider().resolve("Private", "http://" + username + ':' + token
                    + "@127.0.0.1:" + jenkins.port() + "/job/Private/");
            fail("Expected credentials in the URL to be rejected");
        } catch (IOException expectedFailure) {
            assertTrue(expectedFailure.getMessage().contains("configured separately"));
            assertFalse(expectedFailure.getMessage().contains(token));
        }
    }

    @Test
    public void invalidRegexAndUnsafeArtifactPathsFailSafely() throws Exception {
        jenkins.response(build(100, null, artifact("unsafe.jar", "../unsafe.jar", -1)));

        try {
            provider().resolve("Plugin", jenkins.url("/job/Plugin/?get=["), "user", "secret");
            fail("Expected invalid regex to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("get-regex"));
            assertFalse(expected.getMessage().contains("secret"));
        }
        assertEquals(0, jenkins.requests().size());

        try {
            provider().resolve("Plugin", jenkins.url("/job/Plugin/"));
            fail("Expected an unsafe artifact path to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("invalid artifact path"));
        }
    }

    private JenkinsProvider provider() {
        return new JenkinsProvider(logger);
    }

    private static String buildWithChanges(long number,
                                           String description,
                                           String changeOne,
                                           String changeTwo,
                                           String... artifacts) {
        StringBuilder value = new StringBuilder("{\"number\":").append(number)
                .append(",\"timestamp\":\"2026-08-19T12:00:00Z\"");
        if (description != null) {
            value.append(",\"description\":\"").append(description).append('"');
        }
        value.append(",\"changeSet\":{\"items\":[{\"msg\":\"")
                .append(changeOne).append("\"},{\"msg\":\"").append(changeTwo)
                .append("\"}]},\"artifacts\":").append(array(artifacts)).append('}');
        return value.toString();
    }

    private static String build(long number, String description, String... artifacts) {
        StringBuilder value = new StringBuilder("{\"number\":").append(number)
                .append(",\"timestamp\":1724068800000");
        if (description != null) {
            value.append(",\"description\":\"").append(description).append('"');
        }
        return value.append(",\"artifacts\":").append(array(artifacts)).append('}').toString();
    }

    private static String artifact(String fileName, String relativePath, long size) {
        StringBuilder value = new StringBuilder("{\"fileName\":\"").append(fileName)
                .append("\",\"relativePath\":\"").append(relativePath).append('"');
        if (size >= 0L) {
            value.append(",\"size\":").append(size);
        }
        return value.append('}').toString();
    }

    private static String array(String... values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(values[i]);
        }
        return result.append(']').toString();
    }

    private static final class MockJenkins implements AutoCloseable {
        private final HttpServer server;
        private final List<RecordedRequest> requests =
                Collections.synchronizedList(new ArrayList<RecordedRequest>());
        private volatile String body = "{}";
        private volatile String requiredAuthorization;

        private MockJenkins() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    requests.add(new RecordedRequest(exchange.getRequestURI(), authorization));
                    if (requiredAuthorization != null && !requiredAuthorization.equals(authorization)) {
                        write(exchange, 401, "{\"message\":\"unauthorized\"}");
                        return;
                    }
                    if (!exchange.getRequestURI().getRawPath().endsWith(
                            "/lastSuccessfulBuild/api/json")) {
                        write(exchange, 404, "{\"message\":\"not found\"}");
                        return;
                    }
                    write(exchange, 200, body);
                }
            });
            server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private String url(String path) {
            return "http://127.0.0.1:" + port() + path;
        }

        private void response(String value) {
            body = value;
        }

        private void requireAuthorization(String value) {
            requiredAuthorization = value;
        }

        private List<RecordedRequest> requests() {
            synchronized (requests) {
                return new ArrayList<RecordedRequest>(requests);
            }
        }

        private static void write(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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

    private static final class RecordedRequest {
        private final URI uri;
        private final String authorization;

        private RecordedRequest(URI uri, String authorization) {
            this.uri = uri;
            this.authorization = authorization;
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
