package spigot;

import common.ListEntryLoader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Safely changes enabled state in list.yml while preserving its comments and order.
 */
final class AupListToggle {
    private static final Object WRITE_LOCK = new Object();

    private AupListToggle() {
    }

    static Result setEnabled(File listFile, Collection<String> entryNames, boolean enabled) throws IOException {
        if (listFile == null || entryNames == null || entryNames.isEmpty()) {
            return new Result(0, Collections.<String>emptySet());
        }

        Set<String> requested = new LinkedHashSet<>();
        for (String name : entryNames) {
            if (name != null && !name.trim().isEmpty()) {
                requested.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (requested.isEmpty()) {
            return new Result(0, Collections.<String>emptySet());
        }

        synchronized (WRITE_LOCK) {
            ListEntryLoader.LoadedList loaded = ListEntryLoader.loadList(listFile);
            Set<String> groupsToEnable = new LinkedHashSet<>();
            if (enabled) {
                for (ListEntryLoader.LoadedEntry entry : loaded.entries.values()) {
                    if (!requested.contains(entry.name.toLowerCase(Locale.ROOT)) || entry.group == null) {
                        continue;
                    }
                    ListEntryLoader.LoadedGroup group = findGroup(loaded, entry.group);
                    if (group != null && !group.enabled) {
                        groupsToEnable.add(group.name);
                    }
                }
            }

            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            List<String> changedLines = new ArrayList<>(lines);
            int changedEntries = 0;
            Set<String> changedEntryKeys = new LinkedHashSet<>();
            Set<String> changedGroupKeys = new LinkedHashSet<>();

            for (int i = 0; i < changedLines.size(); i++) {
                String line = changedLines.get(i);
                ParsedLine parsed = parseLine(line);
                if (parsed == null) {
                    continue;
                }

                String normalizedKey = parsed.key.toLowerCase(Locale.ROOT);
                if (parsed.indent == 0 && containsIgnoreCase(groupsToEnable, parsed.key) && parsed.commented) {
                    changedLines.set(i, ListEntryLoader.uncommentLine(line));
                    changedGroupKeys.add(normalizedKey);
                    continue;
                }

                if (!requested.contains(normalizedKey) || changedEntryKeys.contains(normalizedKey)) {
                    continue;
                }
                if (enabled && parsed.commented) {
                    changedLines.set(i, ListEntryLoader.uncommentLine(line));
                    changedEntries++;
                    changedEntryKeys.add(normalizedKey);
                } else if (!enabled && !parsed.commented) {
                    changedLines.set(i, ListEntryLoader.commentLine(line));
                    changedEntries++;
                    changedEntryKeys.add(normalizedKey);
                } else {
                    changedEntryKeys.add(normalizedKey);
                }
            }

            if (!changedGroupKeys.isEmpty() || changedEntries > 0) {
                writeAtomically(listFile.toPath(), changedLines);
            }
            return new Result(changedEntries + changedGroupKeys.size(), groupsToEnable);
        }
    }

    private static ListEntryLoader.LoadedGroup findGroup(ListEntryLoader.LoadedList loaded, String name) {
        for (ListEntryLoader.LoadedGroup group : loaded.groups.values()) {
            if (group.name.equalsIgnoreCase(name)) {
                return group;
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(Collection<String> values, String expected) {
        for (String value : values) {
            if (value.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    private static ParsedLine parseLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        int indent = leadingWhitespace(line);
        int cursor = indent;
        boolean commented = cursor < line.length() && line.charAt(cursor) == '#';
        if (commented) {
            cursor++;
            while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) {
                cursor++;
            }
        }
        int colon = line.indexOf(':', cursor);
        if (colon <= cursor) {
            return null;
        }
        String key = line.substring(cursor, colon).trim();
        if (key.isEmpty()) {
            return null;
        }
        return new ParsedLine(key, indent, commented);
    }

    private static int leadingWhitespace(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static void writeAtomically(Path target, List<String> lines) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) {
            Files.write(absolute, lines, StandardCharsets.UTF_8);
            return;
        }
        Path temporary = Files.createTempFile(parent, ".aup-list-", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static final class Result {
        final int changedLines;
        final Set<String> enabledGroups;

        Result(int changedLines, Collection<String> enabledGroups) {
            this.changedLines = changedLines;
            this.enabledGroups = Collections.unmodifiableSet(new LinkedHashSet<>(enabledGroups));
        }

        boolean changed() {
            return changedLines > 0;
        }
    }

    private static final class ParsedLine {
        final String key;
        final int indent;
        final boolean commented;

        private ParsedLine(String key, int indent, boolean commented) {
            this.key = key;
            this.indent = indent;
            this.commented = commented;
        }
    }
}
