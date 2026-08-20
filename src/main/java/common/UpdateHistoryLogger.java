package common;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Daily, human-readable history of update decisions. */
public final class UpdateHistoryLogger {
    private static final String DEFAULT_DIRECTORY = "logs";
    private static final String DEFAULT_PATTERN = "yyyy-MM-dd'.log'";

    private final Path dataFolder;
    private final Logger logger;
    private String activePattern;
    private DateTimeFormatter activeFormatter;

    private UpdateHistoryLogger(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder == null ? null : dataFolder.toAbsolutePath().normalize();
        this.logger = logger;
    }

    public static UpdateHistoryLogger open(Path dataFolder, Logger logger) {
        return new UpdateHistoryLogger(dataFolder, logger);
    }

    public void log(UpdateEvent event) {
        write(event);
    }

    public synchronized void write(UpdateEvent event) {
        if (event == null || !UpdateOptions.updateLogEnabled || !shouldInclude(event)) {
            return;
        }
        Path logFile = fileFor(event.time.atZone(ZoneOffset.UTC).toLocalDate());
        if (logFile == null) {
            return;
        }
        try {
            Path parent = logFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] bytes = (format(event) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            Files.write(logFile, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            warn("Unable to append update history at " + logFile, ex);
        }
    }

    public synchronized Page read(LocalDate day, int page) {
        return read(day, page, UpdateOptions.updateLogCommandPageSize);
    }

    public synchronized Page read(LocalDate day, int page, int pageSize) {
        LocalDate selectedDay = day == null
                ? LocalDate.now(ZoneOffset.UTC) : day;
        int selectedPageSize = pageSize > 0 ? pageSize
                : Math.max(1, UpdateOptions.updateLogCommandPageSize);
        Path logFile = fileFor(selectedDay);
        if (logFile == null || !Files.isRegularFile(logFile)) {
            return new Page(selectedDay, 1, 1, Collections.<String>emptyList());
        }

        try {
            int lineCount = countLines(logFile);
            int totalPages = Math.max(1, (lineCount + selectedPageSize - 1) / selectedPageSize);
            int selectedPage = Math.max(1, Math.min(page, totalPages));
            int firstLine = (selectedPage - 1) * selectedPageSize;
            List<String> lines = readRange(logFile, firstLine, selectedPageSize);
            return new Page(selectedDay, selectedPage, totalPages, lines);
        } catch (IOException ex) {
            warn("Unable to read update history at " + logFile, ex);
            return new Page(selectedDay, 1, 1, Collections.<String>emptyList());
        }
    }

    public synchronized List<LocalDate> recentDays(int maxDays) {
        if (maxDays <= 0) {
            return Collections.emptyList();
        }
        Path directory = logDirectory();
        if (directory == null || !Files.isDirectory(directory)) {
            return Collections.emptyList();
        }

        List<LocalDate> days = new ArrayList<LocalDate>();
        DateTimeFormatter formatter = formatter();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    days.add(LocalDate.parse(file.getFileName().toString(), formatter));
                } catch (DateTimeParseException ignored) {
                    // Other log files in the configured directory are unrelated.
                }
            }
        } catch (IOException ex) {
            warn("Unable to list update history at " + directory, ex);
            return Collections.emptyList();
        }

        Collections.sort(days, Comparator.reverseOrder());
        if (days.size() > maxDays) {
            return Collections.unmodifiableList(new ArrayList<LocalDate>(days.subList(0, maxDays)));
        }
        return Collections.unmodifiableList(days);
    }

    public String format(UpdateEvent event) {
        StringBuilder line = new StringBuilder();
        line.append('[').append(event.time).append("] ")
                .append(event.type.name()).append(' ')
                .append(nonBlank(event.pluginName, "<unknown>"));

        String oldVersion = clean(event.oldVersion);
        String newVersion = clean(event.newVersion);
        String oldHash = clean(event.oldHash);
        String newHash = clean(event.newHash);
        if (oldVersion != null || newVersion != null || oldHash != null || newHash != null) {
            line.append(' ').append(transitionValue(oldVersion, oldHash, oldVersion, newVersion, newHash))
                    .append(" -> ")
                    .append(transitionValue(newVersion, newHash, oldVersion, newVersion, oldHash));
        }

        appendField(line, "provider", event.provider);
        appendField(line, "target", event.targetPath);
        appendField(line, "metadata", event.metadataId);
        appendField(line, "oldSha1", event.oldHash);
        appendField(line, "newSha1", event.newHash);
        appendField(line, "reason", event.reason);
        return line.toString();
    }

    private static String transitionValue(String version,
                                          String hash,
                                          String oldVersion,
                                          String newVersion,
                                          String oppositeHash) {
        String value = version == null ? "?" : version;
        boolean sameVersionHashChange = oldVersion != null
                && oldVersion.equals(newVersion)
                && hash != null
                && oppositeHash != null
                && !hash.equals(oppositeHash);
        if (sameVersionHashChange || (version == null && hash != null)) {
            value += "#" + shortHash(hash);
        }
        return value;
    }

    private boolean shouldInclude(UpdateEvent event) {
        if (event.type == UpdateEvent.Type.AVAILABLE && !UpdateOptions.updateLogIncludeChecks) {
            return false;
        }
        if (event.type != UpdateEvent.Type.SKIPPED || UpdateOptions.updateLogIncludeUnchanged) {
            return true;
        }
        String reason = clean(event.reason);
        if (reason != null && reason.toLowerCase(Locale.ROOT).contains("metadata")) {
            return true;
        }
        if (reason == null) {
            return false;
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        return !(lower.contains("unchanged")
                || lower.contains("duplicate")
                || lower.contains("same hash")
                || lower.contains("same md5"));
    }

    private Path fileFor(LocalDate day) {
        Path directory = logDirectory();
        return directory == null ? null : directory.resolve(formatter().format(day));
    }

    private Path logDirectory() {
        if (dataFolder == null) {
            return null;
        }
        String configured = UpdateOptions.updateLogPath;
        if (configured == null || configured.trim().isEmpty()) {
            configured = DEFAULT_DIRECTORY;
        }
        try {
            Path selected = Paths.get(configured.trim());
            return (selected.isAbsolute() ? selected : dataFolder.resolve(selected))
                    .toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            warn("Invalid update history path '" + configured
                    + "'; using " + DEFAULT_DIRECTORY, ex);
            return dataFolder.resolve(DEFAULT_DIRECTORY).toAbsolutePath().normalize();
        }
    }

    private DateTimeFormatter formatter() {
        String configured = UpdateOptions.updateLogFilePattern;
        if (configured == null || configured.trim().isEmpty()) {
            configured = DEFAULT_PATTERN;
        }
        if (activeFormatter != null && configured.equals(activePattern)) {
            return activeFormatter;
        }

        try {
            DateTimeFormatter candidate = DateTimeFormatter.ofPattern(configured, Locale.ROOT);
            String sample = candidate.format(LocalDate.of(2000, 12, 31));
            if (!isSafeFileName(sample)) {
                throw new IllegalArgumentException("pattern does not produce a safe file name");
            }
            activePattern = configured;
            activeFormatter = candidate;
        } catch (IllegalArgumentException ex) {
            warn("Invalid update history file pattern '" + configured
                    + "'; using " + DEFAULT_PATTERN, ex);
            activePattern = DEFAULT_PATTERN;
            activeFormatter = DateTimeFormatter.ofPattern(DEFAULT_PATTERN, Locale.ROOT);
        }
        return activeFormatter;
    }

    private static boolean isSafeFileName(String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return false;
        }
        return name.indexOf('/') < 0 && name.indexOf('\\') < 0
                && name.indexOf(':') < 0 && name.indexOf('*') < 0
                && name.indexOf('?') < 0 && name.indexOf('"') < 0
                && name.indexOf('<') < 0 && name.indexOf('>') < 0
                && name.indexOf('|') < 0;
    }

    private static int countLines(Path file) throws IOException {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }

    private static List<String> readRange(Path file, int firstLine, int limit) throws IOException {
        List<String> result = new ArrayList<String>(limit);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int index = 0;
            while ((line = reader.readLine()) != null && result.size() < limit) {
                if (index++ >= firstLine) {
                    result.add(line);
                }
            }
        }
        return result;
    }

    private static void appendField(StringBuilder line, String key, String value) {
        String cleaned = clean(value);
        if (cleaned != null) {
            line.append(' ').append(key).append('=').append(cleaned);
        }
    }

    private static String nonBlank(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned == null ? fallback : cleaned;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        while (cleaned.contains("  ")) {
            cleaned = cleaned.replace("  ", " ");
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String shortHash(String hash) {
        return hash.length() <= 8 ? hash : hash.substring(0, 8);
    }

    private void warn(String message, Exception ex) {
        if (logger != null) {
            logger.log(Level.WARNING, message, ex);
        }
    }

    public static final class Page {
        public final LocalDate day;
        public final int page;
        public final int totalPages;
        public final List<String> lines;

        private Page(LocalDate day, int page, int totalPages, List<String> lines) {
            this.day = day;
            this.page = page;
            this.totalPages = totalPages;
            this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
        }
    }
}
