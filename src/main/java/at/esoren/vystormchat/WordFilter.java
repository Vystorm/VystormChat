package at.esoren.vystormchat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Word filter: plain words (whole words, any case, also with letters spread by spaces or dots like "b.a.d" and simple
 * look-alikes such as 0→o, 1→i, 3→e, 4→a, 5→s, 7→t, @→a, $→s) and admin regular expressions. Matches are replaced by
 * the replacement character, one per hidden character. Immutable and thread-safe.
 */
final class WordFilter {
    record Result(String text, boolean matched) {}

    private static final String LEET_FROM = "0134578@$!|";
    private static final String LEET_TO = "oieastbasii";

    private final List<Pattern> patterns;
    private final String replacement;

    private WordFilter(List<Pattern> patterns, String replacement) {
        this.patterns = List.copyOf(patterns);
        this.replacement = replacement.isEmpty() ? "*" : replacement;
    }

    static final WordFilter NONE = new WordFilter(List.of(), "*");

    static WordFilter of(List<String> words, List<String> regexes, String replacement, Logger log) {
        List<Pattern> patterns = new ArrayList<>();
        for (String word : words) {
            String clean = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
            if (clean.isEmpty()) continue;
            patterns.add(Pattern.compile(wordPattern(clean), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        for (String regex : regexes) {
            if (regex == null || regex.isBlank()) continue;
            try {
                patterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException broken) {
                if (log != null) log.warning("Word filter: invalid regex '" + regex + "' skipped (" + broken.getDescription() + ")");
            }
        }
        return new WordFilter(patterns, replacement == null ? "*" : replacement);
    }

    /**
     * A word as a pattern: each letter may be written as itself or a look-alike, letters may be separated by up to two
     * spaces, dots, dashes or underscores, and the word must not be part of a longer word ("class" does not hit "ass").
     */
    static String wordPattern(String word) {
        StringBuilder pattern = new StringBuilder("(?<![\\p{L}\\p{N}])");
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (i > 0) pattern.append("[\\s._\\-]{0,2}");
            StringBuilder options = new StringBuilder(Pattern.quote(String.valueOf(c)));
            for (int j = 0; j < LEET_TO.length(); j++)
                if (LEET_TO.charAt(j) == c) options.append('|').append(Pattern.quote(String.valueOf(LEET_FROM.charAt(j))));
            pattern.append("(?:").append(options).append(")+");
        }
        return pattern.append("(?![\\p{L}\\p{N}])").toString();
    }

    boolean empty() { return patterns.isEmpty(); }

    Result apply(String text) {
        boolean matched = false;
        String result = text;
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(result);
            if (!matcher.find()) continue;
            matched = true;
            StringBuilder out = new StringBuilder();
            do {
                matcher.appendReplacement(out, Matcher.quoteReplacement(mask(matcher.group())));
            } while (matcher.find());
            matcher.appendTail(out);
            result = out.toString();
        }
        return new Result(result, matched);
    }

    private String mask(String found) {
        int length = found.codePointCount(0, found.length());
        return replacement.length() == 1 ? replacement.repeat(Math.max(1, length)) : replacement;
    }
}
