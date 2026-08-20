package common;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HangarProviderTest {
    private ProviderTestServer server;
    private boolean originalCheck;
    private boolean originalStrict;
    private boolean originalPreRelease;

    @Before
    public void setUp() throws Exception {
        server = new ProviderTestServer();
        originalCheck = UpdateOptions.hangarMinecraftVersionCheck;
        originalStrict = UpdateOptions.strictMinecraftVersionMetadata;
        originalPreRelease = UpdateOptions.allowPreReleaseDefault;
        UpdateOptions.hangarMinecraftVersionCheck = true;
        UpdateOptions.strictMinecraftVersionMetadata = false;
        UpdateOptions.allowPreReleaseDefault = false;
    }

    @After
    public void tearDown() {
        UpdateOptions.hangarMinecraftVersionCheck = originalCheck;
        UpdateOptions.strictMinecraftVersionMetadata = originalStrict;
        UpdateOptions.allowPreReleaseDefault = originalPreRelease;
        server.close();
    }

    @Test
    public void choosesOlderMinecraftCompatibleVersion() throws Exception {
        server.pagedJson(page(
                version("20", "2.0.0", "PAPER", "1.21.9", true),
                version("19", "1.9.0", "PAPER", "1.21.8", true)), emptyPage());

        ResolvedUpdate update = provider().resolve("Example", options(""), new ServerEnvironment("paper", "1.21.8"));

        assertEquals("19", update.versionId);
        assertEquals("1.9.0", update.versionLabel);
        assertEquals(server.url("/files/19-paper.jar"), update.downloadUrl);
        assertEquals("Notes 19", update.changelog);
    }

    @Test
    public void versionCheckFalseBypassesMinecraftFilter() throws Exception {
        server.pagedJson(page(
                version("20", "2.0.0", "PAPER", "1.21.9", true),
                version("19", "1.9.0", "PAPER", "1.21.8", true)), emptyPage());

        ResolvedUpdate update = provider().resolve("Example", options("versionCheck=false"),
                new ServerEnvironment("paper", "1.21.8"));

        assertEquals("20", update.versionId);
    }

    @Test
    public void platformOverrideSelectsMatchingDownload() throws Exception {
        server.pagedJson(page(
                version("paper", "3.0.0", "PAPER", "1.21.8", true),
                version("velocity", "2.5.0", "VELOCITY", "3.4", true)), emptyPage());

        ResolvedUpdate update = provider().resolve("Example", options("platform=velocity&versionCheck=false"),
                new ServerEnvironment("paper", "1.21.8"));

        assertEquals("velocity", update.versionId);
        assertEquals("velocity", update.platform);
        assertEquals(server.url("/files/velocity-velocity.jar"), update.downloadUrl);
    }

    @Test
    public void strictMetadataSkipsVersionWithoutPlatformDependencies() throws Exception {
        server.pagedJson(page(
                version("unknown", "2.0.0", "PAPER", null, false),
                version("known", "1.9.0", "PAPER", "1.21.8", true)), emptyPage());

        UpdateOptions.strictMinecraftVersionMetadata = false;
        assertEquals("unknown", provider().resolve("Example", options(""),
                new ServerEnvironment("paper", "1.21.8")).versionId);

        UpdateOptions.strictMinecraftVersionMetadata = true;
        assertEquals("known", provider().resolve("Example", options(""),
                new ServerEnvironment("paper", "1.21.8")).versionId);
    }

    private HangarProvider provider() {
        return new HangarProvider(server.url("/api/v1"));
    }

    private EntryOptions options(String query) {
        return EntryOptions.parse("https://hangar.papermc.io/Author/Example" + (query.isEmpty() ? "" : "?" + query), null);
    }

    private String version(String id, String label, String platform, String dependency, boolean includeMetadata) {
        String lower = platform.toLowerCase();
        String metadata = includeMetadata
                ? ",\"platformDependencies\":{\"" + platform + "\":[\"" + dependency + "\"]}"
                : "";
        return "{\"id\":" + (id.matches("\\d+") ? id : "\"" + id + "\"")
                + ",\"name\":\"" + label + "\",\"createdAt\":\"2026-08-19T00:00:00Z\","
                + "\"description\":\"Notes " + id + "\",\"channel\":{\"name\":\"Release\"},"
                + "\"downloads\":{\"" + platform + "\":{\"downloadUrl\":\""
                + server.url("/files/" + id + "-" + lower + ".jar") + "\",\"fileInfo\":{"
                + "\"name\":\"" + id + "-" + lower + ".jar\",\"sizeBytes\":84,"
                + "\"sha256Hash\":\"sha256-" + id + "\"}}}" + metadata + "}";
    }

    private static String page(String... values) {
        return "{\"result\":[" + String.join(",", values) + "]}";
    }

    private static String emptyPage() {
        return "{\"result\":[]}";
    }
}
