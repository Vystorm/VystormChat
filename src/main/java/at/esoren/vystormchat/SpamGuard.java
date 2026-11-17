package at.esoren.vystormchat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-spam: blocks the same message again within a time (ignoring case, spaces and punctuation), too many messages
 * in a short time (flood), shortens letters repeated too often and lowers shouting (caps). Thread-safe per player.
 */
final class SpamGuard {
    record Settings(boolean enabled, long repeatMillis, int floodMessages, long floodMillis, int capsMinLength,
                    int capsMaxPercent, boolean capsBlock, int maxRepeatedChars) {
        static final Settings OFF = new Settings(false, 0, 0, 0, 0, 100, false, 0);
    }

    enum Verdict { OK, REPEAT, FLOOD, CAPS }

    /** Outcome: a verdict and the (possibly changed) text. */
    record Result(Verdict verdict, String text) {
        boolean blocked() { return verdict != Verdict.OK; }
    }

    private static final class History {
        final Deque<Long> times = new ArrayDeque<>();
        String last = "";
        long lastTime;
    }

    private final Map<String, History> histories = new ConcurrentHashMap<>();
    private volatile Settings settings = Settings.OFF;

    void configure(Settings settings) { this.settings = settings; }

    Settings settings() { return settings; }

    /** Forgets a player (all their keys: chat and private messages). */
    void forget(UUID player) { String prefix = player.toString(); histories.keySet().removeIf(key -> key.startsWith(prefix)); }

    /**
     * Checks a message; on OK it is recorded. The text comes back with caps lowered and repeated letters shortened
     * (when configured), so it should be used instead of the original.
     */
    Result check(UUID player, String text, long now) { return check(player.toString(), text, now); }

    /** Like {@link #check(UUID, String, long)} for one conversation, e.g. "uuid:msg:other" for private messages. */
    Result check(String key, String text, long now) {
        Settings s = settings;
        if (!s.enabled()) return new Result(Verdict.OK, text);
        String changed = s.maxRepeatedChars() > 0 ? collapse(text, s.maxRepeatedChars()) : text;
        if (shouting(changed, s.capsMinLength(), s.capsMaxPercent())) {
            if (s.capsBlock()) return new Result(Verdict.CAPS, changed);
            changed = lowerShouting(changed);
        }
        History history = histories.computeIfAbsent(key, id -> new History());
        synchronized (history) {
            if (s.floodMessages() > 0 && s.floodMillis() > 0) {
                while (!history.times.isEmpty() && now - history.times.peekFirst() >= s.floodMillis()) history.times.pollFirst();
                if (history.times.size() >= s.floodMessages()) return new Result(Verdict.FLOOD, changed);
            }
            String normalized = normalize(changed);
            if (s.repeatMillis() > 0 && !normalized.isEmpty() && normalized.equals(history.last) && now - history.lastTime < s.repeatMillis())
                return new Result(Verdict.REPEAT, changed);
            history.times.addLast(now);
            history.last = normalized;
            history.lastTime = now;
        }
        return new Result(Verdict.OK, changed);
    }

    /** Lower case without spaces and punctuation, so "Hello!" and "hello" count as the same. */
    static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** Shortens runs of the same character longer than {@code max} to {@code max} ("heeeeeey" → "heeeey" for 4). */
    static String collapse(String text, int max) {
        StringBuilder out = new StringBuilder(text.length());
        int run = 0, previous = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            run = cp == previous ? run + 1 : 1;
            previous = cp;
            if (run <= max) out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /** At least {@code minLength} letters and more than {@code maxPercent} % of them upper case. */
    static boolean shouting(String text, int minLength, int maxPercent) {
        if (maxPercent >= 100) return false;
        int letters = 0, upper = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) continue;
            letters++;
            if (Character.isUpperCase(c)) upper++;
        }
        return letters >= Math.max(1, minLength) && upper * 100 > maxPercent * letters;
    }

    /** Lower case, but the first letter of the message stays as written. Words with digits (e.g. "1.21.8") are fine. */
    static String lowerShouting(String text) {
        if (text.isEmpty()) return text;
        return text.substring(0, 1) + text.substring(1).toLowerCase(Locale.ROOT);
    }
}
