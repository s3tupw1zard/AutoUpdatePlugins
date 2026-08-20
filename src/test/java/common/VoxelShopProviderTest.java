package common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VoxelShopProviderTest {
    private static final String INFO_PATH = "/v1/getResourceInfo/";
    private static final String UPDATES_PATH = "/v1/getResourceUpdates/";
    private static final String DOWNLOAD_PATH = "/v1/getDownloadURL/";

    private MockVoxelShop voxel;
    private boolean originalAllowPreRelease;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setUp() throws IOException {
        originalAllowPreRelease = UpdateOptions.allowPreReleaseDefault;
        UpdateOptions.allowPreReleaseDefault = false;
        voxel = new MockVoxelShop();
    }

    @After
    public void tearDown() {
        UpdateOptions.allowPreReleaseDefault = originalAllowPreRelease;
        if (voxel != null) voxel.close();
    }

    @Test
    public void recognizesOnlyExactOfficialResourceUrls() throws Exception {
        assertEquals("4", VoxelShopProvider.extractResourceId(
                "https://voxel.shop/resource/item-bridge.4"));
        assertEquals("42", VoxelShopProvider.extractResourceId(
                "https://www.voxel.shop/product/42/item-bridge/"));
        assertEquals("77", VoxelShopProvider.extractResourceId(
                "https://polymart.org/resource/legacy-resource.77?ref=list"));
        assertEquals("9", VoxelShopProvider.extractResourceId(
                "https://www.polymart.org/r/9/legacy"));
        assertEquals("123", VoxelShopProvider.extractResourceId(
                "https://voxel.shop/resource/123"));

        assertFalse(VoxelShopProvider.recognizes("http://voxel.shop/resource/item.4"));
        assertFalse(VoxelShopProvider.recognizes("https://voxel.shop.evil.invalid/resource/item.4"));
        assertFalse(VoxelShopProvider.recognizes("https://user@voxel.shop/resource/item.4"));
        assertFalse(VoxelShopProvider.recognizes("https://voxel.shop:443/resource/item.4"));
        assertFalse(VoxelShopProvider.recognizes("https://voxel.shop/resource/item.4#fragment"));
        assertFalse(VoxelShopProvider.recognizes("https://voxel.shop/resource/no-numeric-id"));
        assertFalse(VoxelShopProvider.recognizes("https://voxel.shop/resource/.4"));
        assertFalse(VoxelShopProvider.recognizes("https://voxel.shop/resource/item.0"));
    }

    @Test
    public void resolvesNewestStableFreeUpdateFromStringifiedOfficialResponses() throws Exception {
        long expires = futureExpiry();
        enqueueResolution("0.00", allUpdates(), download("1.0.0", expires));

        VoxelShopProvider.Resolution resolution = provider().resolve(
                "ItemBridge", options("https://voxel.shop/resource/item-bridge.4"),
                "unneeded-free-resource-token");

        assertTrue(resolution.isDownloadable());
        assertEquals(VoxelShopProvider.Resolution.Capability.DOWNLOADABLE, resolution.capability);
        assertEquals("https://voxel.shop/product/4", resolution.actionUrl);
        assertNull(resolution.reason);
        assertEquals(expires, resolution.downloadExpiresAtSeconds);

        ResolvedUpdate update = resolution.update;
        assertEquals("ItemBridge", update.pluginName);
        assertEquals("voxelshop", update.provider);
        assertEquals("voxelshop:4", update.normalizedSource);
        assertEquals("https://cdn.example.test/stable.jar", update.downloadUrl);
        assertEquals("4", update.projectId);
        assertEquals("100", update.versionId);
        assertEquals("1.0.0", update.versionLabel);
        assertEquals("release", update.releaseType);
        assertEquals(1000_000L, update.publishedAtMillis);
        assertEquals(Arrays.asList("1.20.6", "1.21"), update.gameVersions);
        assertEquals("Stable: Fix & polish", update.changelog);
        assertEquals("voxelshop:4:100", update.metadataId());

        List<RecordedRequest> requests = voxel.requests();
        assertEquals(3, requests.size());
        assertPost(requests.get(0), INFO_PATH, "resource_id=4&stringify=0");
        assertPost(requests.get(1), UPDATES_PATH,
                "resource_id=4&start=0&limit=50&stringify=0");
        assertPost(requests.get(2), DOWNLOAD_PATH,
                "resource_id=4&allow_redirects=0&stringify=0");
    }

    @Test
    public void supportsExactBetaAndLatestChannelSelection() throws Exception {
        long expires = futureExpiry();
        enqueueResolution("0", allUpdates(), download("2.0-beta", expires));
        enqueueResolution("0", allUpdates(), download("3.0-SNAPSHOT", expires));

        VoxelShopProvider provider = provider();
        VoxelShopProvider.Resolution beta = provider.resolve("ItemBridge",
                options("https://voxel.shop/resource/item-bridge.4?channel=beta"), null);
        VoxelShopProvider.Resolution latest = provider.resolve("ItemBridge",
                options("https://polymart.org/resource/item-bridge.4?latest=true"), null);

        assertTrue(beta.isDownloadable());
        assertEquals("200", beta.update.versionId);
        assertEquals("2.0-beta", beta.update.versionLabel);
        assertEquals("beta", beta.update.releaseType);
        assertTrue(latest.isDownloadable());
        assertEquals("300", latest.update.versionId);
        assertEquals("3.0-SNAPSHOT", latest.update.versionLabel);
        assertEquals("snapshot", latest.update.releaseType);
        assertEquals(6, voxel.requests().size());
    }

    @Test
    public void returnsManualMetadataForPaidResourceWithoutToken() throws Exception {
        voxel.respond(INFO_PATH, 200, info("12.50"));
        voxel.respond(UPDATES_PATH, 200, stableUpdates());

        VoxelShopProvider.Resolution resolution = provider().resolve("PaidPlugin",
                options("https://voxel.shop/product/4/paid-plugin"), null);

        assertFalse(resolution.isDownloadable());
        assertEquals(VoxelShopProvider.Resolution.Capability.MANUAL_ACTION, resolution.capability);
        assertEquals("https://voxel.shop/product/4", resolution.actionUrl);
        assertTrue(resolution.reason.contains("authorized VoxelShop user token"));
        assertNull(resolution.update.downloadUrl);
        assertEquals("1.0.0", resolution.update.versionLabel);
        assertEquals("voxelshop:4:100", resolution.update.metadataId());
        assertEquals(2, voxel.requests().size());
    }

    @Test
    public void updaterRecordsPaidResourceAsManualPendingInsteadOfFailure() throws Exception {
        voxel.respond(INFO_PATH, 200, info("12.50"));
        voxel.respond(UPDATES_PATH, 200, stableUpdates());
        Path data = temporaryFolder.newFolder("voxel-data").toPath();
        String originalFilePath = UpdateOptions.filePath;
        String originalTempPath = UpdateOptions.tempPath;
        boolean originalLogEnabled = UpdateOptions.updateLogEnabled;
        try {
            UpdateOptions.filePath = temporaryFolder.newFolder("plugins").getAbsolutePath();
            UpdateOptions.tempPath = temporaryFolder.newFolder("downloads").getAbsolutePath();
            UpdateOptions.updateLogEnabled = false;
            UpdateOptions.voxelShopTokens.clear();
            PluginUpdater updater = new PluginUpdater(Logger.getAnonymousLogger(), data,
                    new ModrinthProvider(), new HangarProvider(), new ExtendedClipProvider(),
                    new GitLabProvider(Logger.getAnonymousLogger()), provider());
            Map<String, String> entries = new LinkedHashMap<String, String>();
            entries.put("PaidPlugin", "https://voxel.shop/product/4/paid-plugin");
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<PluginUpdater.RunSummary> result = new AtomicReference<PluginUpdater.RunSummary>();

            updater.checkEntries(entries, "paper", null, summary -> {
                result.set(summary);
                finished.countDown();
            });

            assertTrue(finished.await(10, TimeUnit.SECONDS));
            assertNotNull(result.get());
            assertEquals(1, result.get().available);
            assertEquals(0, result.get().failed);
            PluginUpdater.PendingUpdate pending = updater.getPendingUpdates().get("PaidPlugin");
            assertNotNull(pending);
            assertTrue(pending.requiresManualAction());
            assertEquals("https://voxel.shop/product/4", pending.actionUrl);
            assertTrue(pending.manualReason.contains("authorized VoxelShop user token"));
            assertEquals(2, voxel.requests().size());
        } finally {
            UpdateOptions.filePath = originalFilePath;
            UpdateOptions.tempPath = originalTempPath;
            UpdateOptions.updateLogEnabled = originalLogEnabled;
            UpdateOptions.voxelShopTokens.clear();
        }
    }

    @Test
    public void encodesPaidTokenOnlyInPostBodyAndRedactsFailures() throws Exception {
        String secret = "voxel secret+&=%";
        long expires = futureExpiry();
        enqueueResolution("9.99", stableUpdates(), download("1.0.0", expires));

        VoxelShopProvider provider = provider();
        VoxelShopProvider.Resolution resolution = provider.resolve("PaidPlugin",
                options("https://voxel.shop/product/4/paid-plugin"), secret);

        assertTrue(resolution.isDownloadable());
        assertFalse(resolution.update.downloadUrl.contains(secret));
        assertFalse(resolution.actionUrl.contains(secret));
        assertPost(voxel.requests().get(2), DOWNLOAD_PATH,
                "resource_id=4&allow_redirects=0&stringify=0"
                        + "&token=voxel+secret%2B%26%3D%25");

        voxel.respond(INFO_PATH, 200, info("9.99"));
        voxel.respond(UPDATES_PATH, 200, stableUpdates());
        voxel.respond(DOWNLOAD_PATH, 403, "{\"error\":\"" + secret + "\"}");
        try {
            provider.resolve("PaidPlugin",
                    options("https://voxel.shop/product/4/paid-plugin"), secret);
            fail("Expected an authorization failure");
        } catch (IOException expected) {
            assertEquals("VoxelShop API returned HTTP 403", expected.getMessage());
            assertFalse(expected.toString().contains(secret));
        }
    }

    @Test
    public void preservesPostBodyAcrossSafeSameOriginRedirect() throws Exception {
        long expires = futureExpiry();
        voxel.redirect(INFO_PATH, 307, "/v1/redirected-resource-info");
        voxel.respond("/v1/redirected-resource-info", 200, info("0"));
        voxel.respond(UPDATES_PATH, 200, stableUpdates());
        voxel.respond(DOWNLOAD_PATH, 200, download("1.0.0", expires));

        VoxelShopProvider.Resolution resolution = provider().resolve("Redirected",
                options("https://voxel.shop/resource/redirected.4"), null);

        assertTrue(resolution.isDownloadable());
        List<RecordedRequest> requests = voxel.requests();
        assertEquals(4, requests.size());
        assertPost(requests.get(0), INFO_PATH, "resource_id=4&stringify=0");
        assertPost(requests.get(1), "/v1/redirected-resource-info",
                "resource_id=4&stringify=0");
    }

    @Test
    public void refusesCrossOriginRedirectWithoutForwardingRequest() throws Exception {
        voxel.redirect(INFO_PATH, 302,
                "http://localhost:" + voxel.port() + "/v1/cross-origin");

        try {
            provider().resolve("Redirected",
                    options("https://voxel.shop/resource/redirected.4"), null);
            fail("Expected a cross-origin redirect failure");
        } catch (IOException expected) {
            assertEquals("VoxelShop API refused a cross-origin redirect", expected.getMessage());
        }
        assertEquals(1, voxel.requests().size());
    }

    @Test
    public void rejectsMalformedResponsesExpiredUrlsAndChannelMismatches() throws Exception {
        String echoedSecret = "do-not-echo-this-secret";
        voxel.respond(INFO_PATH, 200, "{\"response\":{\"secret\":\"" + echoedSecret + "\"");
        try {
            provider().resolve("Malformed",
                    options("https://voxel.shop/resource/malformed.4"), null);
            fail("Expected malformed JSON to fail");
        } catch (IOException expected) {
            assertEquals("VoxelShop API returned malformed JSON", expected.getMessage());
            assertFalse(expected.toString().contains(echoedSecret));
        }

        voxel.respond(INFO_PATH, 200, info("0"));
        voxel.respond(UPDATES_PATH, 200,
                "{\"response\":{\"success\":\"true\",\"updates\":\"invalid\"}}");
        try {
            provider().resolve("Malformed",
                    options("https://voxel.shop/resource/malformed.4"), null);
            fail("Expected a missing updates array to fail");
        } catch (IOException expected) {
            assertEquals("VoxelShop update history returned no updates array", expected.getMessage());
        }

        enqueueResolution("0", stableUpdates(), download("1.0.0", pastExpiry()));
        try {
            provider().resolve("Expired",
                    options("https://voxel.shop/resource/expired.4"), null);
            fail("Expected an expired URL to fail");
        } catch (IOException expected) {
            assertEquals("VoxelShop returned an expired download URL", expected.getMessage());
        }

        enqueueResolution("0", stableUpdates(), download("9.0-wrong-channel", futureExpiry()));
        VoxelShopProvider.Resolution mismatch = provider().resolve("Mismatch",
                options("https://voxel.shop/resource/mismatch.4?channel=stable"), null);
        assertFalse(mismatch.isDownloadable());
        assertNull(mismatch.update.downloadUrl);
        assertTrue(mismatch.reason.contains("does not match the selected release channel"));
    }

    private VoxelShopProvider provider() {
        return new VoxelShopProvider(voxel.apiBase());
    }

    private static EntryOptions options(String source) {
        return EntryOptions.parse(source, Logger.getAnonymousLogger());
    }

    private void enqueueResolution(String price, String updates, String download) {
        voxel.respond(INFO_PATH, 200, info(price));
        voxel.respond(UPDATES_PATH, 200, updates);
        voxel.respond(DOWNLOAD_PATH, 200, download);
    }

    private static void assertPost(RecordedRequest request, String path, String body) {
        assertEquals("POST", request.method);
        assertEquals(path, request.rawPath);
        assertEquals("", request.rawQuery);
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", request.contentType);
        assertEquals(body, request.body);
    }

    private static String info(String price) {
        return "{\"response\":{\"success\":\"true\",\"resource\":{"
                + "\"id\":\"4\",\"price\":\"" + price + "\","
                + "\"supportedMinecraftVersions\":[\"1.20.6\",\"1.21\"]}}}";
    }

    private static String stableUpdates() {
        return "{\"response\":{\"success\":\"true\",\"updates\":["
                + "{\"id\":\"100\",\"version\":\"1.0.0\",\"time\":\"1000\","
                + "\"beta\":\"false\",\"snapshot\":\"0\",\"downloadReady\":\"true\","
                + "\"title\":\"<b>Stable</b>\","
                + "\"description\":\"<p>Fix &amp; polish</p>\"}"
                + "]}}";
    }

    private static String allUpdates() {
        return "{\"response\":{\"success\":\"true\",\"updates\":["
                + "{\"id\":\"400\",\"version\":\"4.0-unready\",\"time\":\"4000\","
                + "\"beta\":\"false\",\"snapshot\":\"false\",\"downloadReady\":\"false\"},"
                + "{\"id\":\"300\",\"version\":\"3.0-SNAPSHOT\",\"time\":\"3000\","
                + "\"beta\":\"false\",\"snapshot\":\"1\",\"downloadReady\":\"true\"},"
                + "{\"id\":\"200\",\"version\":\"2.0-beta\",\"time\":\"2000\","
                + "\"beta\":\"yes\",\"snapshot\":\"false\",\"downloadReady\":\"true\"},"
                + "{\"id\":\"100\",\"version\":\"1.0.0\",\"time\":\"1000\","
                + "\"beta\":\"false\",\"snapshot\":\"0\",\"downloadReady\":\"true\","
                + "\"title\":\"<b>Stable</b>\","
                + "\"description\":\"<p>Fix &amp; polish</p>\"}"
                + "]}}";
    }

    private static String download(String version, long expires) {
        String file = "1.0.0".equals(version) ? "stable" : "selected";
        return "{\"response\":{\"success\":\"true\",\"result\":{"
                + "\"url\":\"https://cdn.example.test/" + file + ".jar\","
                + "\"version\":\"" + version + "\",\"expires\":\"" + expires + "\"}}}";
    }

    private static long futureExpiry() {
        return System.currentTimeMillis() / 1000L + 3600L;
    }

    private static long pastExpiry() {
        return System.currentTimeMillis() / 1000L - 60L;
    }

    private static final class MockVoxelShop implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, Deque<Response>> responses =
                new LinkedHashMap<String, Deque<Response>>();
        private final List<RecordedRequest> requests = new ArrayList<RecordedRequest>();

        private MockVoxelShop() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
                    synchronized (MockVoxelShop.this) {
                        requests.add(new RecordedRequest(
                                exchange.getRequestMethod(),
                                exchange.getRequestURI().getRawPath(),
                                nullToEmpty(exchange.getRequestURI().getRawQuery()),
                                nullToEmpty(exchange.getRequestHeaders().getFirst("Content-Type")),
                                body));
                    }
                    Response response = take(exchange.getRequestURI().getRawPath());
                    if (response == null) {
                        write(exchange, new Response(404, "{\"error\":\"not found\"}", null));
                    } else {
                        write(exchange, response);
                    }
                }
            });
            server.start();
        }

        private synchronized void respond(String path, int status, String body) {
            enqueue(path, new Response(status, body, null));
        }

        private synchronized void redirect(String path, int status, String location) {
            enqueue(path, new Response(status, "", location));
        }

        private synchronized void enqueue(String path, Response response) {
            Deque<Response> queue = responses.get(path);
            if (queue == null) {
                queue = new ArrayDeque<Response>();
                responses.put(path, queue);
            }
            queue.addLast(response);
        }

        private synchronized Response take(String path) {
            Deque<Response> queue = responses.get(path);
            return queue == null || queue.isEmpty() ? null : queue.removeFirst();
        }

        private synchronized List<RecordedRequest> requests() {
            return new ArrayList<RecordedRequest>(requests);
        }

        private String apiBase() {
            return "http://127.0.0.1:" + port() + "/v1";
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private static void write(HttpExchange exchange, Response response) throws IOException {
            byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (response.location != null) {
                exchange.getResponseHeaders().set("Location", response.location);
            }
            exchange.sendResponseHeaders(response.status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class Response {
        private final int status;
        private final String body;
        private final String location;

        private Response(int status, String body, String location) {
            this.status = status;
            this.body = body;
            this.location = location;
        }
    }

    private static final class RecordedRequest {
        private final String method;
        private final String rawPath;
        private final String rawQuery;
        private final String contentType;
        private final String body;

        private RecordedRequest(String method, String rawPath, String rawQuery,
                                String contentType, String body) {
            this.method = method;
            this.rawPath = rawPath;
            this.rawQuery = rawQuery;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
