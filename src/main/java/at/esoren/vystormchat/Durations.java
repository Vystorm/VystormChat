package at.esoren.vystormchat;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durations as players type them: {@code 30s}, {@code 10m}, {@code 2h}, {@code 7d}, {@code 1w}, combined {@code 1h30m}. */
final class Durations {
    private static final Pattern PART = Pattern.compile("(\\d{1,9})([smhdw])");

    private Durations() {}

    /** Milliseconds, or -1 when the text is no duration. A plain number counts as minutes. */
    static long parse(String text) {
        if (text == null) return -1;
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return -1;
        if (value.chars().allMatch(Character::isDigit)) {
            if (value.length() > 9) return -1;
            return Long.parseLong(value) * 60_000L;
        }
        Matcher matcher = PART.matcher(value);
        long total = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) return -1;
            end = matcher.end();
            long amount = Long.parseLong(matcher.group(1));
            long unit = switch (matcher.group(2)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> 604_800_000L;
            };
            total += amount * unit;
            if (total > 3_153_600_000_000L) return -1; // more than 100 years: treat as a typo
        }
        return end == value.length() && total > 0 ? total : -1;
    }

    /** Time argument of /mute: {@link #PERMANENT} for perm/permanent. */
    static final long PERMANENT = 0;
    /** {@link #muteTime}: the word is no time at all (it starts the reason). */
    static final long NO_TIME = -2;

    /**
     * The time argument of /mute: milliseconds, {@link #PERMANENT} for {@code perm}/{@code permanent}, {@link #NO_TIME}
     * for a word that is clearly no time (letters first: the reason starts here), or -1 for a time typed wrong
     * ({@code 30min}, {@code 2hours}): that must be rejected, never turned into a permanent mute.
     */
    static long muteTime(String text) {
        if (text == null) return NO_TIME;
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.equals("perm") || value.equals("permanent")) return PERMANENT;
        long parsed = parse(value);
        if (parsed > 0) return parsed;
        return !value.isEmpty() && Character.isDigit(value.charAt(0)) ? -1 : NO_TIME;
    }

    /** Short form for messages: {@code 1d 2h}, {@code 5m 3s}, {@code 12s}. At most two units. */
    static String format(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        long days = seconds / 86_400, hours = seconds % 86_400 / 3600, minutes = seconds % 3600 / 60, rest = seconds % 60;
        if (days > 0) return hours > 0 ? days + "d " + hours + "h" : days + "d";
        if (hours > 0) return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        if (minutes > 0) return rest > 0 ? minutes + "m " + rest + "s" : minutes + "m";
        return rest + "s";
    }

    /** Play time from ticks: {@code 12h 5m}, {@code 3d 4h}. */
    static String playtime(long ticks) {
        long minutes = Math.max(0, ticks / 20 / 60);
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h " + minutes % 60 + "m";
        return hours / 24 + "d " + hours % 24 + "h";
    }
}
