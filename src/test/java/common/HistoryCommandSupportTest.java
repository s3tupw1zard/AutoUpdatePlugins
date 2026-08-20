package common;

import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HistoryCommandSupportTest {
    private static final Clock NOW = Clock.fixed(
            Instant.parse("2026-06-20T23:59:59Z"), ZoneOffset.UTC);

    @Test
    public void parsesDefaultsAndSinglePage() {
        HistoryCommandSupport.Request defaults = HistoryCommandSupport.parse(new String[0], NOW);
        HistoryCommandSupport.Request page = HistoryCommandSupport.parse(new String[]{"3"}, NOW);

        assertTrue(defaults.valid);
        assertEquals(LocalDate.of(2026, 6, 20), defaults.day);
        assertEquals(1, defaults.page);
        assertTrue(page.valid);
        assertEquals(defaults.day, page.day);
        assertEquals(3, page.page);
    }

    @Test
    public void parsesNamedAndIsoDatesWithOptionalPage() {
        HistoryCommandSupport.Request today = HistoryCommandSupport.parse(new String[]{"TODAY"}, NOW);
        HistoryCommandSupport.Request yesterday = HistoryCommandSupport.parse(new String[]{"yesterday", "2"}, NOW);
        HistoryCommandSupport.Request date = HistoryCommandSupport.parse(new String[]{"2026-05-01", "4"}, NOW);

        assertEquals(LocalDate.of(2026, 6, 20), today.day);
        assertEquals(LocalDate.of(2026, 6, 19), yesterday.day);
        assertEquals(2, yesterday.page);
        assertEquals(LocalDate.of(2026, 5, 1), date.day);
        assertEquals(4, date.page);
    }

    @Test
    public void rejectsInvalidDatesPagesAndExtraArguments() {
        assertFalse(HistoryCommandSupport.parse(new String[]{"2026-02-30"}, NOW).valid);
        assertFalse(HistoryCommandSupport.parse(new String[]{"0"}, NOW).valid);
        assertFalse(HistoryCommandSupport.parse(new String[]{"today", "-1"}, NOW).valid);
        assertFalse(HistoryCommandSupport.parse(new String[]{"today", "1", "extra"}, NOW).valid);
    }

    @Test
    public void suggestionsAreFilteredAndPageCountIsBounded() {
        assertEquals(Arrays.asList("today"), HistoryCommandSupport.dateSuggestions("to",
                Arrays.asList(LocalDate.of(2026, 6, 20))));
        assertEquals(Arrays.asList("2026-06-20"), HistoryCommandSupport.dateSuggestions("2026",
                Arrays.asList(LocalDate.of(2026, 6, 20))));
        assertEquals(Arrays.asList("1", "2", "3"),
                HistoryCommandSupport.pageSuggestions("", 3));
        assertEquals(Arrays.asList("2"), HistoryCommandSupport.pageSuggestions("2", 3));
    }

    @Test
    public void compactLineOnlyChangesValidInstantPrefixes() {
        assertEquals("[12:34:56] APPLIED Plugin", HistoryCommandSupport.compactLine(
                "[2026-06-20T12:34:56Z] APPLIED Plugin"));
        assertEquals("[broken] raw", HistoryCommandSupport.compactLine("[broken] raw"));
        assertEquals("raw", HistoryCommandSupport.compactLine("raw"));
    }
}
