package common;

import org.junit.Test;

import static org.junit.Assert.*;

public class EntryOptionsTest {

    @Test
    public void logSourceDropsUserInfoQueriesAndEncodedBuildLibrarySecrets() {
        EntryOptions options = EntryOptions.parse(
                "https://user:password@example.test/plugin.jar?buildLib=g:a:1%3Dhttps%3A%2F%2Fcdn.test%2Flib.jar%3Ftoken%3Dsigned-secret",
                null);

        String logged = PluginUpdater.redactSourceForLog(options.sourceWithoutQuery);
        assertEquals("https://example.test/plugin.jar", logged);
        assertFalse(logged.contains("password"));
        assertFalse(logged.contains("signed-secret"));
    }
    @Test
    public void parsesQueryAndPathOptions() {
        EntryOptions options = EntryOptions.parse(
                "https://example.test/plugin?get=.*paper.*%5C.jar&versionCheck=true&force=1|filePath=plugins/custom|useUpdateFolder=false",
                null);

        assertEquals("https://example.test/plugin", options.sourceWithoutQuery);
        assertEquals(".*paper.*\\.jar", options.first("get"));
        assertTrue(options.bool("VERSIONCHECK", false));
        assertTrue(options.bool("force", false));
        assertEquals("plugins\\custom", options.pathOptions.getFilePath().replace('/', '\\'));
        assertEquals(Boolean.FALSE, options.pathOptions.getUseUpdateFolder());
    }

    @Test
    public void supportsRepeatedAndCaseInsensitiveParameters() {
        EntryOptions options = EntryOptions.parse("https://x.test?a=1&A=2&onlyMinor=yes&count=3", null);
        assertEquals(2, options.queryParams.get("a").size());
        assertTrue(options.bool("ONLYMINOR", false));
        assertEquals(3, options.positiveInt("count", 1));
    }

    @Test
    public void malformedValuesUseFallbacks() {
        EntryOptions options = EntryOptions.parse("https://x.test?force=maybe&count=-1&bad=%ZZ", null);
        assertTrue(options.bool("force", true));
        assertEquals(9, options.positiveInt("count", 9));
        assertEquals("%ZZ", options.first("bad"));
    }

    @Test
    public void preservesRegexAlternationAndRepairsLegacyAmpersandQuery() {
        EntryOptions regex = EntryOptions.parse("https://x.test/plugin?get=paper|spigot", null);
        assertEquals("paper|spigot", regex.first("get"));
        assertNull(regex.customPath());

        EntryOptions legacy = EntryOptions.parse("https://github.com/Owner/Repo&autobuild=true", null);
        assertEquals("https://github.com/Owner/Repo", legacy.sourceWithoutQuery);
        assertEquals("https://github.com/Owner/Repo?autobuild=true", legacy.sourceValue);
        assertTrue(legacy.bool("autobuild", false));
    }

    @Test
    public void legacyBuildLibraryStartsAQueryWithoutAutobuild() {
        EntryOptions options = EntryOptions.parse(
                "https://github.com/example/project&buildLib=com.example:api:1.0=file%3A%2F%2FC%3A%2Flibs%2Fapi.jar",
                null);

        assertEquals("https://github.com/example/project", options.sourceWithoutQuery);
        assertEquals("com.example:api:1.0=file://C:/libs/api.jar", options.first("buildLib"));
    }
}
