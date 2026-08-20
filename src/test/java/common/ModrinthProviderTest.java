package common;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModrinthProviderTest {
    private ProviderTestServer server;
    private boolean originalCheck;
    private boolean originalStrict;
    private boolean originalPreRelease;

    @Before
    public void setUp() throws Exception {
        server = new ProviderTestServer();
        originalCheck = UpdateOptions.modrinthMinecraftVersionCheck;
        originalStrict = UpdateOptions.strictMinecraftVersionMetadata;
        originalPreRelease = UpdateOptions.allowPreReleaseDefault;
        UpdateOptions.modrinthMinecraftVersionCheck = true;
        UpdateOptions.strictMinecraftVersionMetadata = false;
        UpdateOptions.allowPreReleaseDefault = false;
    }

    @After
    public void tearDown() {
        UpdateOptions.modrinthMinecraftVersionCheck = originalCheck;
        UpdateOptions.strictMinecraftVersionMetadata = originalStrict;
        UpdateOptions.allowPreReleaseDefault = originalPreRelease;
        server.close();
    }

    @Test
    public void choosesOlderMinecraftCompatibleVersion() throws Exception {
        server.pagedJson(versions(
                version("new", "2.0.0", "paper", "1.21.9", "new.jar", true),
                version("compatible", "1.9.0", "paper", "1.21.8", "compatible.jar", true)), "[]");

        ResolvedUpdate update = provider().resolve("Example", options(""), new ServerEnvironment("paper", "1.21.8"));

        assertEquals("compatible", update.versionId);
        assertEquals("1.9.0", update.versionLabel);
        assertEquals(server.url("/files/compatible.jar"), update.downloadUrl);
        assertEquals("Compatibility notes", update.changelog);
    }

    @Test
    public void versionCheckFalseBypassesMinecraftFilter() throws Exception {
        server.pagedJson(versions(
                version("new", "2.0.0", "paper", "1.21.9", "new.jar", true),
                version("compatible", "1.9.0", "paper", "1.21.8", "compatible.jar", true)), "[]");

        ResolvedUpdate update = provider().resolve("Example", options("versionCheck=false"),
                new ServerEnvironment("paper", "1.21.8"));

        assertEquals("new", update.versionId);
    }

    @Test
    public void exposesNotesForAllCompatibleVersionsInTheSelectedResponse() throws Exception {
        server.pagedJson(versions(
                version("newest", "3.0.0", "paper", "1.21.8", "newest.jar", true),
                version("middle", "2.0.0", "paper", "1.21.8", "middle.jar", true),
                version("incompatible", "1.5.0", "paper", "1.21.9", "old.jar", true)), "[]");

        ResolvedUpdate update = provider().resolve("Example", options(""),
                new ServerEnvironment("paper", "1.21.8"));

        assertEquals("newest", update.versionId);
        assertEquals(2, update.releaseNotes.size());
        assertEquals("3.0.0", update.releaseNotes.get(0).versionLabel);
        assertEquals("2.0.0", update.releaseNotes.get(1).versionLabel);
        String query = server.lastRequest.get().getRawQuery();
        assertTrue(query.contains("include_changelog=true"));
        assertFalse(query.contains("offset="));
        assertFalse(query.contains("limit="));
        assertEquals(1, server.hits.get());
    }

    @Test
    public void sortsUnorderedVersionsAndDeduplicatesProviderIdsBeforeSelection() throws Exception {
        server.json(versions(
                versionDetails("duplicate", "1.5.0", "2026-08-18T00:00:00Z",
                        "Stale duplicate", "stale.jar"),
                versionDetails("latest", "2.0.0", "2026-08-20T00:00:00Z",
                        "Latest notes", "latest.jar"),
                versionDetails("duplicate", "1.5.0", "2026-08-19T00:00:00Z",
                        "New duplicate", "new-duplicate.jar")));

        ResolvedUpdate update = provider().resolve("Example", options(""),
                new ServerEnvironment("paper", "1.21.8"));

        assertEquals("latest", update.versionId);
        assertEquals("2.0.0", update.versionLabel);
        assertEquals(2, update.releaseNotes.size());
        assertEquals("latest", update.releaseNotes.get(0).versionId);
        assertEquals("duplicate", update.releaseNotes.get(1).versionId);
        assertEquals("New duplicate", update.releaseNotes.get(1).changelog);
        assertTrue(update.releaseNotesComplete);
        assertEquals(1, server.hits.get());
    }

    @Test
    public void loaderOverrideAndFileRegexSelectRequestedArtifact() throws Exception {
        String paper = version("paper", "3.0.0", "paper", "1.21.8", "paper.jar", true);
        String velocity = "{\"id\":\"velocity\",\"project_id\":\"project-id\","
                + "\"version_number\":\"2.5.0\",\"version_type\":\"release\","
                + "\"date_published\":\"2026-08-19T00:00:00Z\",\"loaders\":[\"velocity\"],"
                + "\"game_versions\":[\"1.21.8\"],\"changelog\":\"Velocity notes\",\"files\":["
                + file("example-api.jar", true) + "," + file("example-velocity.jar", false) + "]}";
        server.pagedJson(versions(paper, velocity), "[]");

        ResolvedUpdate update = provider().resolve("Example",
                options("loader=velocity&get=velocity\\.jar$"), new ServerEnvironment("paper", "1.21.8"));

        assertEquals("velocity", update.versionId);
        assertEquals("example-velocity.jar", update.fileName);
        assertEquals(server.url("/files/example-velocity.jar"), update.downloadUrl);
        assertEquals("velocity", update.platform);
    }

    @Test
    public void strictMetadataSkipsVersionWithoutGameVersions() throws Exception {
        String unknown = versionWithoutGameVersions("unknown", "2.0.0", "paper", "unknown.jar");
        String compatible = version("compatible", "1.9.0", "paper", "1.21.8", "compatible.jar", true);
        server.pagedJson(versions(unknown, compatible), "[]");

        UpdateOptions.strictMinecraftVersionMetadata = false;
        assertEquals("unknown", provider().resolve("Example", options(""),
                new ServerEnvironment("paper", "1.21.8")).versionId);

        UpdateOptions.strictMinecraftVersionMetadata = true;
        assertEquals("compatible", provider().resolve("Example", options(""),
                new ServerEnvironment("paper", "1.21.8")).versionId);
    }

    @Test(expected = IOException.class)
    public void invalidRegexFailsBeforeSelectingAFile() throws Exception {
        server.pagedJson(versions(version("new", "2.0.0", "paper", "1.21.8", "new.jar", true)), "[]");
        provider().resolve("Example", options("get=["), new ServerEnvironment("paper", "1.21.8"));
    }

    private ModrinthProvider provider() {
        return new ModrinthProvider(server.url("/v2"));
    }

    private EntryOptions options(String query) {
        return EntryOptions.parse("https://modrinth.com/plugin/example" + (query.isEmpty() ? "" : "?" + query), null);
    }

    private String version(String id, String label, String loader, String gameVersion, String fileName, boolean primary) {
        return "{\"id\":\"" + id + "\",\"project_id\":\"project-id\","
                + "\"version_number\":\"" + label + "\",\"version_type\":\"release\","
                + "\"date_published\":\"2026-08-19T00:00:00Z\",\"loaders\":[\"" + loader + "\"],"
                + "\"game_versions\":[\"" + gameVersion + "\"],"
                + "\"changelog\":\"" + ("compatible".equals(id) ? "Compatibility notes" : "Notes") + "\","
                + "\"files\":[" + file(fileName, primary) + "]}";
    }

    private String versionWithoutGameVersions(String id, String label, String loader, String fileName) {
        return "{\"id\":\"" + id + "\",\"project_id\":\"project-id\","
                + "\"version_number\":\"" + label + "\",\"version_type\":\"release\","
                + "\"date_published\":\"2026-08-19T00:00:00Z\",\"loaders\":[\"" + loader + "\"],"
                + "\"files\":[" + file(fileName, true) + "]}";
    }

    private String versionDetails(String id, String label, String published,
                                  String changelog, String fileName) {
        return "{\"id\":\"" + id + "\",\"project_id\":\"project-id\","
                + "\"version_number\":\"" + label + "\",\"version_type\":\"release\","
                + "\"date_published\":\"" + published + "\",\"loaders\":[\"paper\"],"
                + "\"game_versions\":[\"1.21.8\"],\"changelog\":\"" + changelog + "\","
                + "\"files\":[" + file(fileName, true) + "]}";
    }

    private String file(String name, boolean primary) {
        return "{\"url\":\"" + server.url("/files/" + name) + "\",\"filename\":\"" + name
                + "\",\"primary\":" + primary + ",\"size\":42,"
                + "\"hashes\":{\"sha1\":\"sha1-" + name + "\",\"sha512\":\"sha512-" + name + "\"}}";
    }

    private static String versions(String... values) {
        return "[" + String.join(",", values) + "]";
    }
}
