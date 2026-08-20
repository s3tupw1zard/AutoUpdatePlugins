package common;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdateHistoryLoggerTest {
    private static final LocalDate DAY = LocalDate.of(2026, 6, 20);

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private boolean originalEnabled;
    private String originalPath;
    private String originalPattern;
    private int originalPageSize;
    private boolean originalIncludeUnchanged;
    private boolean originalIncludeChecks;

    @Before
    public void rememberConfiguration() {
        originalEnabled = UpdateOptions.updateLogEnabled;
        originalPath = UpdateOptions.updateLogPath;
        originalPattern = UpdateOptions.updateLogFilePattern;
        originalPageSize = UpdateOptions.updateLogCommandPageSize;
        originalIncludeUnchanged = UpdateOptions.updateLogIncludeUnchanged;
        originalIncludeChecks = UpdateOptions.updateLogIncludeChecks;

        UpdateOptions.updateLogEnabled = true;
        UpdateOptions.updateLogPath = "logs";
        UpdateOptions.updateLogFilePattern = "yyyy-MM-dd'.log'";
        UpdateOptions.updateLogCommandPageSize = 8;
        UpdateOptions.updateLogIncludeUnchanged = false;
        UpdateOptions.updateLogIncludeChecks = true;
    }

    @After
    public void restoreConfiguration() {
        UpdateOptions.updateLogEnabled = originalEnabled;
        UpdateOptions.updateLogPath = originalPath;
        UpdateOptions.updateLogFilePattern = originalPattern;
        UpdateOptions.updateLogCommandPageSize = originalPageSize;
        UpdateOptions.updateLogIncludeUnchanged = originalIncludeUnchanged;
        UpdateOptions.updateLogIncludeChecks = originalIncludeChecks;
    }

    @Test
    public void writesEventsToDailyFileAndPaginatesWithClamping() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("history").toPath();
        UpdateHistoryLogger history = UpdateHistoryLogger.open(dataFolder, testLogger());

        history.write(event("2026-06-20T12:34:56Z", UpdateEvent.Type.APPLIED,
                "PluginA", "1.0.0", "1.1.0", "installed"));
        history.write(event("2026-06-20T12:35:01Z", UpdateEvent.Type.SKIPPED,
                "PluginB", "2.0.0", "2.0.0", "metadata unchanged"));
        history.write(event("2026-06-20T12:35:10Z", UpdateEvent.Type.BLOCKED,
                "PluginC", "2.3.2", "2.4.0", "versionPolicy patch"));

        Path file = dataFolder.resolve("logs").resolve("2026-06-20.log");
        assertTrue(Files.isRegularFile(file));

        UpdateHistoryLogger.Page first = history.read(DAY, 1, 2);
        assertEquals(1, first.page);
        assertEquals(2, first.totalPages);
        assertEquals(2, first.lines.size());
        assertTrue(first.lines.get(0).contains("APPLIED PluginA 1.0.0 -> 1.1.0"));
        assertTrue(first.lines.get(1).contains("SKIPPED PluginB 2.0.0 -> 2.0.0"));

        UpdateHistoryLogger.Page clamped = history.read(DAY, 99, 2);
        assertEquals(2, clamped.page);
        assertEquals(1, clamped.lines.size());
        assertTrue(clamped.lines.get(0).contains("BLOCKED PluginC"));
    }

    @Test
    public void missingLogReturnsAnEmptyFirstPage() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("missing").toPath();
        UpdateHistoryLogger history = UpdateHistoryLogger.open(dataFolder, testLogger());

        UpdateHistoryLogger.Page page = history.read(DAY, 7, 3);

        assertEquals(DAY, page.day);
        assertEquals(1, page.page);
        assertEquals(1, page.totalPages);
        assertTrue(page.lines.isEmpty());
    }

    @Test
    public void malformedExistingLinesAreReturnedRaw() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("raw").toPath();
        Path directory = Files.createDirectories(dataFolder.resolve("logs"));
        Path file = directory.resolve("2026-06-20.log");
        List<String> raw = Arrays.asList("not an event", "[broken] ???", "");
        Files.write(file, raw, StandardCharsets.UTF_8);
        UpdateHistoryLogger history = UpdateHistoryLogger.open(dataFolder, testLogger());

        UpdateHistoryLogger.Page page = history.read(DAY, 1, 10);

        assertEquals(raw, page.lines);
    }

    @Test
    public void configuredPathPatternAndDefaultPageSizeAreApplied() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("configured").toPath();
        UpdateOptions.updateLogPath = "audit/update-history";
        UpdateOptions.updateLogFilePattern = "'updates-'yyyyMMdd'.txt'";
        UpdateOptions.updateLogCommandPageSize = 1;
        UpdateHistoryLogger history = UpdateHistoryLogger.open(dataFolder, testLogger());

        history.write(event("2026-06-20T01:00:00Z", UpdateEvent.Type.APPLIED,
                "First", "1", "2", null));
        history.write(event("2026-06-20T02:00:00Z", UpdateEvent.Type.FAILED,
                "Second", null, null, "no download url"));

        assertTrue(Files.isRegularFile(dataFolder.resolve("audit/update-history/updates-20260620.txt")));
        UpdateHistoryLogger.Page page = history.read(DAY, 2);
        assertEquals(2, page.page);
        assertEquals(2, page.totalPages);
        assertTrue(page.lines.get(0).contains("FAILED Second"));
        assertEquals(Arrays.asList(DAY), history.recentDays(5));
    }

    @Test
    public void sameVersionHashChangeAppearsInTransition() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("hashes").toPath();
        UpdateHistoryLogger history = UpdateHistoryLogger.open(dataFolder, testLogger());
        UpdateEvent event = UpdateEvent.builder(UpdateEvent.Type.APPLIED, "SnapshotPlugin")
                .time(Instant.parse("2026-06-20T03:00:00Z"))
                .provider("github")
                .versions("2.3.2-SNAPSHOT", "2.3.2-SNAPSHOT")
                .hashes("ab12cd345678", "ef34gh987654")
                .metadataId("commit:new")
                .build();

        String line = history.format(event);

        assertTrue(line.contains("2.3.2-SNAPSHOT#ab12cd34 -> 2.3.2-SNAPSHOT#ef34gh98"));
        assertTrue(line.contains("oldSha1=ab12cd345678"));
        assertTrue(line.contains("newSha1=ef34gh987654"));
    }

    @Test
    public void unchangedDuplicatesAreOptionalButMetadataSkipsRemainVisible() throws Exception {
        Path dataFolder = temporaryFolder.newFolder("filters").toPath();
        UpdateHistoryLogger history = UpdateHistoryLogger.open(dataFolder, testLogger());

        history.write(event("2026-06-20T04:00:00Z", UpdateEvent.Type.SKIPPED,
                "Duplicate", "1", "1", "MD5 unchanged duplicate"));
        history.write(event("2026-06-20T04:01:00Z", UpdateEvent.Type.SKIPPED,
                "Cached", "1", "1", "metadata unchanged"));

        UpdateHistoryLogger.Page page = history.read(DAY, 1, 10);
        assertEquals(1, page.lines.size());
        assertFalse(page.lines.get(0).contains("Duplicate"));
        assertTrue(page.lines.get(0).contains("Cached"));

        UpdateOptions.updateLogIncludeUnchanged = true;
        history.write(event("2026-06-20T04:02:00Z", UpdateEvent.Type.SKIPPED,
                "DuplicateIncluded", "1", "1", "MD5 unchanged duplicate"));
        assertEquals(2, history.read(DAY, 1, 10).lines.size());
    }

    private static UpdateEvent event(String time,
                                     UpdateEvent.Type type,
                                     String plugin,
                                     String oldVersion,
                                     String newVersion,
                                     String reason) {
        return new UpdateEvent(Instant.parse(time), type, plugin, "modrinth",
                oldVersion, newVersion, null, null, "plugins/" + plugin + ".jar",
                "metadata:" + plugin, reason);
    }

    private static Logger testLogger() {
        Logger logger = Logger.getLogger("UpdateHistoryLoggerTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        return logger;
    }
}
