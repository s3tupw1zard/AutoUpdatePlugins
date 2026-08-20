package common;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChangelogRangeTest {
    @Test
    public void aggregatesInstalledToSelectedOldestFirstAndDeduplicatesVersions() {
        List<ResolvedUpdate.ReleaseNote> notes = new ArrayList<ResolvedUpdate.ReleaseNote>();
        notes.add(note("future", "2.1.0", 60L, "Future"));
        notes.add(note("selected", "v2.0.0", 50L, "Selected notes"));
        notes.add(note("middle", "1.2.0", 40L, "Replacement middle notes"));
        notes.add(note("middle", "v1.2.0", 30L, "Stale middle notes"));
        notes.add(note("first", "1.1.0", 20L, "First notes"));
        notes.add(note("old", "0.9.0", 10L, "Already installed long ago"));
        ResolvedUpdate update = ResolvedUpdate.builder("Plugin", "test", "https://example.test/plugin.jar")
                .versionLabel("2.0.0")
                .changelog("Selected fallback")
                .releaseNotes(notes)
                .build();

        assertEquals("[1.1.0] First notes\n"
                        + "[1.2.0] Replacement middle notes\n"
                        + "[v2.0.0] Selected notes",
                PluginUpdater.aggregateChangelogRange("1.0.0", update));
    }

    @Test
    public void fallsBackWhenVersionComparisonOrRangeNotesAreUnavailable() {
        ResolvedUpdate update = ResolvedUpdate.builder("Plugin", "test", "https://example.test/plugin.jar")
                .versionLabel("named-build")
                .changelog("Selected release only")
                .releaseNotes(java.util.Collections.singletonList(
                        note("selected", "named-build", 1L, "Selected release only")))
                .build();

        assertEquals("Selected release only",
                PluginUpdater.aggregateChangelogRange("older-named-build", update));
        assertEquals("Selected release only",
                PluginUpdater.aggregateChangelogRange(null, update));
    }

    @Test
    public void releaseNotesAreDefensivelyImmutableAndRangeIsBoundedToNewestEight() {
        List<ResolvedUpdate.ReleaseNote> notes = new ArrayList<ResolvedUpdate.ReleaseNote>();
        for (int minor = 1; minor <= 12; minor++) {
            notes.add(note("id-" + minor, "1." + minor + ".0", minor,
                    "Notes for release " + minor));
        }
        ResolvedUpdate update = ResolvedUpdate.builder("Plugin", "test", "https://example.test/plugin.jar")
                .versionLabel("1.12.0")
                .changelog("Latest")
                .releaseNotes(notes)
                .build();
        notes.clear();

        assertEquals(12, update.releaseNotes.size());
        try {
            update.releaseNotes.add(note("extra", "2.0.0", 20L, "Extra"));
            fail("Release-note list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }

        String aggregated = PluginUpdater.aggregateChangelogRange("1.0.0", update);
        assertTrue(aggregated.startsWith("(4 earlier release notes omitted)\n[1.5.0]"));
        assertTrue(aggregated.contains("[1.12.0] Notes for release 12"));
        assertFalse(aggregated.contains("[1.4.0]"));
        assertTrue(aggregated.length() <= 2400);
    }

    @Test
    public void selectedProviderIdWinsRepublishedVersionCollisionAndIdsDedupeFirst() {
        List<ResolvedUpdate.ReleaseNote> notes = new ArrayList<ResolvedUpdate.ReleaseNote>();
        notes.add(note("duplicate", "1.5.0", 10L, "First API record"));
        notes.add(note("duplicate", "v1.5.0", 99L, "Duplicate page record"));
        notes.add(note("republished", "2.0.0", 200L, "Wrong newer republish"));
        notes.add(note("selected-id", "v2.0.0", 100L, "Selected artifact notes"));
        ResolvedUpdate update = ResolvedUpdate.builder("Plugin", "test", "https://example.test/plugin.jar")
                .versionId("selected-id")
                .versionLabel("2.0.0")
                .changelog("Selected fallback")
                .releaseNotes(notes)
                .build();

        String aggregated = PluginUpdater.aggregateChangelogRange("1.0.0", update);

        assertTrue(aggregated.contains("[1.5.0] First API record"));
        assertFalse(aggregated.contains("Duplicate page record"));
        assertTrue(aggregated.contains("[v2.0.0] Selected artifact notes"));
        assertFalse(aggregated.contains("Wrong newer republish"));
    }

    @Test
    public void filtersStrictRangeAndOrdersQualifiersThroughSelectedRelease() {
        List<ResolvedUpdate.ReleaseNote> notes = new ArrayList<ResolvedUpdate.ReleaseNote>();
        notes.add(note("future", "2.1.0", 6L, "Future"));
        notes.add(note("selected", "2.0.0", 5L, "Release"));
        notes.add(note("rc", "2.0.0-rc1", 4L, "RC"));
        notes.add(note("beta", "2.0.0-beta1", 3L, "Beta"));
        notes.add(note("installed", "2.0.0-alpha1", 2L, "Installed"));
        notes.add(note("malformed", "nightly-current", 1L, "Malformed"));
        ResolvedUpdate update = ResolvedUpdate.builder("Plugin", "test", "https://example.test/plugin.jar")
                .versionId("selected")
                .versionLabel("2.0.0")
                .changelog("Release")
                .releaseNotes(notes)
                .build();

        assertEquals("[2.0.0-beta1] Beta\n[2.0.0-rc1] RC\n[2.0.0] Release",
                PluginUpdater.aggregateChangelogRange("2.0.0-alpha1", update));
    }

    @Test
    public void selectedNoteSurvivesCountAndCharacterBudgetsAndPreviewShowsIt() {
        List<ResolvedUpdate.ReleaseNote> notes = new ArrayList<ResolvedUpdate.ReleaseNote>();
        for (int minor = 1; minor <= 14; minor++) {
            String body = minor == 14 ? "SELECTED-LATEST " + repeat('z', 1000)
                    : "Older-" + minor + " " + repeat('x', 1000);
            notes.add(note("id-" + minor, "1." + minor + ".0", minor, body));
        }
        ResolvedUpdate update = ResolvedUpdate.builder("Plugin", "test", "https://example.test/plugin.jar")
                .versionId("id-14")
                .versionLabel("1.14.0")
                .changelog("SELECTED-LATEST fallback")
                .releaseNotes(notes)
                .build();

        String aggregated = PluginUpdater.aggregateChangelogRange("1.0.0", update);
        assertTrue(aggregated.contains("[1.14.0] SELECTED-LATEST"));
        assertTrue(aggregated.length() <= 2400);

        PluginUpdater.PendingUpdate pending = new PluginUpdater.PendingUpdate(
                "Plugin", "source", "target", 1L, "test", "1.14.0", aggregated);
        assertTrue(pending.changelogPreview().contains("[1.14.0] SELECTED-LATEST"));
        assertFalse(pending.changelogPreview().contains("Older-"));
    }

    @Test
    public void marksAggregatedHistoryWhenProviderNotesAreIncomplete() {
        ResolvedUpdate update = ResolvedUpdate.builder("Plugin", "test", "https://example.test/plugin.jar")
                .versionId("selected")
                .versionLabel("2.0.0")
                .changelog("Selected")
                .releaseNotes(java.util.Arrays.asList(
                        note("selected", "2.0.0", 2L, "Selected"),
                        note("middle", "1.5.0", 1L, "Middle")))
                .releaseNotesComplete(false)
                .build();

        String aggregated = PluginUpdater.aggregateChangelogRange("1.0.0", update);
        assertTrue(aggregated.startsWith("(older release history may be incomplete)\n"));
        assertTrue(aggregated.contains("[2.0.0] Selected"));
    }

    private static ResolvedUpdate.ReleaseNote note(String id, String version,
                                                   long published, String body) {
        return new ResolvedUpdate.ReleaseNote(id, version, "release", published, body);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
