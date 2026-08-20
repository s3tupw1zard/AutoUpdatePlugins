package spigot;

import common.ListEntryLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AupListToggleTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void togglesFlatEntriesWithoutChangingOrderOrComments() throws Exception {
        File list = temporaryFolder.newFile("list.yml");
        Files.write(list.toPath(), Arrays.asList(
                "# explanatory comment",
                "Alpha: https://example.test/alpha.jar",
                "# Beta: https://example.test/beta.jar"
        ), StandardCharsets.UTF_8);

        assertTrue(AupListToggle.setEnabled(list, Collections.singleton("Beta"), true).changed());
        assertTrue(AupListToggle.setEnabled(list, Collections.singleton("alpha"), false).changed());

        List<String> lines = Files.readAllLines(list.toPath(), StandardCharsets.UTF_8);
        assertEquals("# explanatory comment", lines.get(0));
        assertEquals("# Alpha: https://example.test/alpha.jar", lines.get(1));
        assertEquals("Beta: https://example.test/beta.jar", lines.get(2));
        assertFalse(ListEntryLoader.loadList(list).entries.get("Alpha").enabled);
        assertTrue(ListEntryLoader.loadList(list).entries.get("Beta").enabled);
    }

    @Test
    public void enablingMemberOfDisabledGroupEnablesGroupAndMember() throws Exception {
        File list = temporaryFolder.newFile("list.yml");
        Files.write(list.toPath(), Arrays.asList(
                "# Utilities:",
                "  First: https://example.test/first.jar",
                "  # Second: https://example.test/second.jar"
        ), StandardCharsets.UTF_8);

        AupListToggle.Result result = AupListToggle.setEnabled(list,
                Collections.singleton("Second"), true);

        ListEntryLoader.LoadedList loaded = ListEntryLoader.loadList(list);
        assertTrue(result.changed());
        assertTrue(result.enabledGroups.contains("Utilities"));
        assertTrue(loaded.groups.get("Utilities").enabled);
        assertTrue(loaded.entries.get("First").enabled);
        assertTrue(loaded.entries.get("Second").enabled);
    }

    @Test
    public void noOpDoesNotRewriteAnAlreadyMatchingEntry() throws Exception {
        File list = temporaryFolder.newFile("list.yml");
        Files.write(list.toPath(), Collections.singletonList(
                "Alpha: https://example.test/alpha.jar"), StandardCharsets.UTF_8);

        AupListToggle.Result result = AupListToggle.setEnabled(list,
                Collections.singleton("Alpha"), true);

        assertFalse(result.changed());
        assertEquals(Collections.singletonList("Alpha: https://example.test/alpha.jar"),
                Files.readAllLines(list.toPath(), StandardCharsets.UTF_8));
    }
}
