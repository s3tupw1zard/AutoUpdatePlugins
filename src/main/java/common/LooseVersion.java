package common;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tolerant comparison for plugin versions that are not guaranteed to be SemVer. */
public final class LooseVersion implements Comparable<LooseVersion> {
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9])(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:\\.(\\d+))?(?!\\d)");
    private static final Pattern QUALIFIER_NUMBER = Pattern.compile("(?:snapshot|dev|nightly|canary|alpha|beta|preview|pre|rc|release|stable|final|a|b)[-._ ]?(\\d+)", Pattern.CASE_INSENSITIVE);

    public static final LooseVersion UNKNOWN = new LooseVersion(null, false, 0, 0, 0, 0, 0, 0, "unknown");

    public final String raw;
    public final boolean known;
    public final int major;
    public final int minor;
    public final int patch;
    public final int build;
    public final int qualifierRank;
    public final int qualifierNumber;
    public final String qualifier;

    private LooseVersion(String raw, boolean known, int major, int minor, int patch, int build,
                         int qualifierRank, int qualifierNumber, String qualifier) {
        this.raw = raw;
        this.known = known;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.build = build;
        this.qualifierRank = qualifierRank;
        this.qualifierNumber = qualifierNumber;
        this.qualifier = qualifier;
    }

    public static LooseVersion parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return UNKNOWN;
        String text = raw.trim();
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".jar")) normalized = normalized.substring(0, normalized.length() - 4);
        if (normalized.startsWith("v") && normalized.length() > 1 && Character.isDigit(normalized.charAt(1))) {
            normalized = normalized.substring(1);
        }
        Matcher numbers = NUMBER.matcher(normalized);
        if (!numbers.find()) return UNKNOWN;
        try {
            int major = component(numbers.group(1));
            int minor = component(numbers.group(2));
            int patch = component(numbers.group(3));
            int build = component(numbers.group(4));
            String suffix = normalized.substring(numbers.end());
            Qualifier q = qualifier(suffix);
            return new LooseVersion(text, true, major, minor, patch, build, q.rank, q.number, q.name);
        } catch (NumberFormatException ignored) {
            return UNKNOWN;
        }
    }

    private static int component(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static Qualifier qualifier(String suffix) {
        String value = suffix == null ? "" : suffix.toLowerCase(Locale.ROOT);
        String name = "release";
        int rank = 50;
        if (containsWord(value, "snapshot") || containsWord(value, "dev")
                || containsWord(value, "nightly") || containsWord(value, "canary")) {
            name = "snapshot";
            rank = 10;
        } else if (containsWord(value, "alpha") || shortQualifier(value, 'a')) {
            name = "alpha";
            rank = 20;
        } else if (containsWord(value, "beta") || shortQualifier(value, 'b')) {
            name = "beta";
            rank = 30;
        } else if (containsWord(value, "preview") || containsWord(value, "pre") || containsWord(value, "rc")) {
            name = "rc";
            rank = 40;
        }
        int number = 0;
        Matcher m = QUALIFIER_NUMBER.matcher(value);
        if (m.find()) {
            try {
                number = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                number = 0;
            }
        }
        return new Qualifier(name, rank, number);
    }

    private static boolean containsWord(String text, String value) {
        return Pattern.compile("(?:^|[-._+ ])" + Pattern.quote(value) + "(?:$|[-._+ ]|\\d)").matcher(text).find();
    }

    private static boolean shortQualifier(String text, char letter) {
        return Pattern.compile("(?:^|[-._+ ])" + letter + "\\d*(?:$|[-._+ ])").matcher(text).find();
    }

    public boolean isSnapshotLike() {
        return known && qualifierRank == 10;
    }

    @Override
    public int compareTo(LooseVersion other) {
        if (other == null) return known ? 1 : 0;
        if (known != other.known) return known ? 1 : -1;
        if (!known) return 0;
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        if (result == 0) result = Integer.compare(build, other.build);
        if (result == 0) result = Integer.compare(qualifierRank, other.qualifierRank);
        if (result == 0) result = Integer.compare(qualifierNumber, other.qualifierNumber);
        return result;
    }

    @Override
    public String toString() {
        if (!known) return "UNKNOWN";
        return major + "." + minor + "." + patch + (build == 0 ? "" : "." + build)
                + (qualifierRank == 50 ? "" : "-" + qualifier + (qualifierNumber == 0 ? "" : qualifierNumber));
    }

    private static final class Qualifier {
        private final String name;
        private final int rank;
        private final int number;

        private Qualifier(String name, int rank, int number) {
            this.name = name;
            this.rank = rank;
            this.number = number;
        }
    }
}
