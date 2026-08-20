package common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared parsing and display helpers for the platform-specific /aup log commands. */
public final class HistoryCommandSupport {
    public static final String USAGE = "/aup log [today|yesterday|yyyy-MM-dd|page] [page]";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private HistoryCommandSupport() {
    }

    public static Request parse(String[] args) {
        return parse(args, Clock.systemUTC());
    }

    static Request parse(String[] args, Clock clock) {
        String[] safeArgs = args == null ? new String[0] : args;
        LocalDate today = LocalDate.now(clock == null ? Clock.systemUTC() : clock);
        if (safeArgs.length == 0) {
            return Request.valid(today, 1);
        }
        if (safeArgs.length > 2) {
            return Request.invalid("Usage: " + USAGE);
        }

        if (safeArgs.length == 1 && looksNumeric(safeArgs[0])) {
            Integer page = parsePage(safeArgs[0]);
            return page == null
                    ? Request.invalid("Page must be a positive number. Usage: " + USAGE)
                    : Request.valid(today, page);
        }

        LocalDate day = parseDay(safeArgs[0], today);
        if (day == null) {
            return Request.invalid("Invalid log date. Usage: " + USAGE);
        }
        if (safeArgs.length == 1) {
            return Request.valid(day, 1);
        }
        Integer page = parsePage(safeArgs[1]);
        return page == null
                ? Request.invalid("Page must be a positive number. Usage: " + USAGE)
                : Request.valid(day, page);
    }

    public static List<String> dateSuggestions(String current, List<LocalDate> recentDays) {
        String prefix = current == null ? "" : current.toLowerCase(Locale.ROOT);
        Set<String> candidates = new LinkedHashSet<String>();
        candidates.add("today");
        candidates.add("yesterday");
        if (recentDays != null) {
            for (LocalDate day : recentDays) {
                if (day != null) {
                    candidates.add(day.toString());
                }
            }
        }
        List<String> result = new ArrayList<String>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(candidate);
            }
        }
        return result;
    }

    public static List<String> pageSuggestions(String current, int totalPages) {
        String prefix = current == null ? "" : current;
        int pages = Math.max(1, Math.min(totalPages, 100));
        List<String> result = new ArrayList<String>();
        for (int page = 1; page <= pages; page++) {
            String candidate = Integer.toString(page);
            if (candidate.startsWith(prefix)) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** Converts an ISO instant prefix to compact UTC time while preserving raw legacy lines. */
    public static String compactLine(String line) {
        if (line == null || line.length() < 3 || line.charAt(0) != '[') {
            return line;
        }
        int closing = line.indexOf(']');
        if (closing < 2) {
            return line;
        }
        try {
            Instant instant = Instant.parse(line.substring(1, closing));
            return "[" + TIME.format(instant) + "]" + line.substring(closing + 1);
        } catch (DateTimeParseException ignored) {
            return line;
        }
    }

    private static LocalDate parseDay(String value, LocalDate today) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("today".equals(normalized)) {
            return today;
        }
        if ("yesterday".equals(normalized)) {
            return today.minusDays(1);
        }
        try {
            return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean looksNumeric(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        String normalized = value.trim();
        int start = normalized.charAt(0) == '+' || normalized.charAt(0) == '-' ? 1 : 0;
        if (start == normalized.length()) {
            return false;
        }
        for (int i = start; i < normalized.length(); i++) {
            if (!Character.isDigit(normalized.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Integer parsePage(String value) {
        try {
            int page = Integer.parseInt(value == null ? "" : value.trim());
            return page > 0 ? page : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static final class Request {
        public final boolean valid;
        public final LocalDate day;
        public final int page;
        public final String error;

        private Request(boolean valid, LocalDate day, int page, String error) {
            this.valid = valid;
            this.day = day;
            this.page = page;
            this.error = error;
        }

        private static Request valid(LocalDate day, int page) {
            return new Request(true, day, page, null);
        }

        private static Request invalid(String error) {
            return new Request(false, null, 1, error);
        }
    }
}
