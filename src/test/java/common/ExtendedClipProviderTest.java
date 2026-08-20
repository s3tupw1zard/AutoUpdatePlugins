package common;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ExtendedClipProviderTest {
    private ProviderTestServer server;

    @Before
    public void setUp() throws Exception {
        server = new ProviderTestServer();
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void resolvesCurrentV3LatestVersionAndChangelog() throws Exception {
        String download = server.url("/downloads/LocalTime-Expansion-1.2.jar");
        server.json("{\"LocalTime\":{\"latest_version\":\"1.2\",\"last_update\":1768202798000,"
                + "\"versions\":["
                + "{\"version\":\"1.1\",\"url\":\"" + server.url("/downloads/old.jar")
                + "\",\"verified\":true,\"created_at\":1760000000000},"
                + "{\"version\":\"1.2\",\"url\":\"" + download
                + "\",\"verified\":true,\"created_at\":1768202798552,"
                + "\"release_notes\":\"Fixed server crashes\"}]}}" );

        ResolvedUpdate update = provider().resolve("LocalTimeExpansion", options("localtime"));

        assertEquals("1.2", update.versionId);
        assertEquals("1.2", update.versionLabel);
        assertEquals(download, update.downloadUrl);
        assertEquals("LocalTime-Expansion-1.2.jar", update.fileName);
        assertEquals("Fixed server crashes", update.changelog);
        assertEquals(1768202798552L, update.publishedAtMillis);
        assertTrue(update.metadataId().contains("extendedclip:localtime:1.2"));
        assertTrue(server.lastRequest.get().getRawQuery().contains("platform=bukkit"));
        assertTrue(server.lastRequest.get().getRawQuery().contains("name=localtime"));
        assertTrue(ExtendedClipProvider.recognizes("https://api.extendedclip.com/expansions/localtime/"));
        assertTrue(ExtendedClipProvider.recognizes("https://ecloud.placeholderapi.com/expansions/localtime/"));
    }

    @Test
    public void invalidSlugFailsWithoutCallingApi() throws Exception {
        try {
            provider().resolve("Invalid", EntryOptions.parse("https://api.extendedclip.com/expansions/", null));
            fail("Expected invalid expansion URL");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Invalid eCloud expansion URL"));
        }
        assertEquals(0, server.hits.get());
    }

    @Test
    public void missingExpansionFailsCleanly() throws Exception {
        server.json("{\"Other\":{\"latest_version\":\"1.0\",\"versions\":[]}}");
        try {
            provider().resolve("Missing", options("does-not-exist"));
            fail("Expected missing expansion failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("eCloud expansion not found: does-not-exist"));
        }
        assertEquals(1, server.hits.get());
    }

    @Test
    public void providerHttpFailurePropagatesAsIOException() throws Exception {
        server.failure(503, "{\"error\":\"unavailable\"}");
        try {
            provider().resolve("LocalTime", options("localtime"));
            fail("Expected provider HTTP failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("HTTP 503"));
        }
    }

    private ExtendedClipProvider provider() {
        return new ExtendedClipProvider(server.url("/api/v3/"));
    }

    private static EntryOptions options(String slug) {
        return EntryOptions.parse("https://api.extendedclip.com/expansions/" + slug + "/", null);
    }
}
