package at.esoren.vystormchat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds links in chat text: anything with http(s)://, www., a common top-level domain (example.com, play.server.de)
 * or a bare IP address (server advertising). Allowed domains (and their subdomains) are never treated as foreign.
 */
final class Links {
    private static final String TLDS = "com|net|org|de|at|ch|io|gg|me|eu|co|uk|us|info|xyz|tv|ru|fr|nl|pl|it|es|be|dev|app|link|ly|to|cc|biz|online|site|store|shop|club|fun|pro|tk|ml|ga|cf|gq|top|live|cz|se|no|dk|fi|br|ca|au|in|cn|jp|kr";
    static final Pattern LINK = Pattern.compile(
            "(?i)(?<![\\w@.-])((?:https?://)[^\\s<>\"]+|www\\.[^\\s<>\"]+|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:" + TLDS
                    + ")(?::\\d{1,5})?(?:/[^\\s<>\"]*)?(?![\\w-])|\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?)");

    record Found(int start, int end, String text) {}

    private Links() {}

    static List<Found> find(String text) {
        List<Found> found = new ArrayList<>();
        Matcher matcher = LINK.matcher(text);
        while (matcher.find()) {
            String link = trimTrailing(matcher.group(1));
            found.add(new Found(matcher.start(1), matcher.start(1) + link.length(), link));
        }
        return found;
    }

    /** Sentence punctuation after a link is not part of it ("see example.com." → "example.com"). */
    static String trimTrailing(String link) {
        int end = link.length();
        while (end > 0 && ".,!?;:)]}'\"".indexOf(link.charAt(end - 1)) >= 0) end--;
        return link.substring(0, end);
    }

    /** Host of a link in lower case, without scheme, port, path and a leading "www.". */
    static String host(String link) {
        String host = link.toLowerCase(Locale.ROOT).replaceFirst("^[a-z]+://", "");
        int cut = host.length();
        for (char stop : new char[] {'/', ':', '?', '#'}) {
            int index = host.indexOf(stop);
            if (index >= 0) cut = Math.min(cut, index);
        }
        host = host.substring(0, cut);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    static boolean allowed(String link, Set<String> allowedDomains) {
        String host = host(link);
        for (String domain : allowedDomains) {
            String clean = domain.toLowerCase(Locale.ROOT).trim();
            if (!clean.isEmpty() && (host.equals(clean) || host.endsWith("." + clean))) return true;
        }
        return false;
    }

    /** Clickable target: adds https:// when no scheme is given. */
    static String url(String link) {
        return link.matches("(?i)^https?://.*") ? link : "https://" + link;
    }

    /** Replaces every link that is not allowed with the replacement; returns null when there was none. */
    static String censor(String text, Set<String> allowedDomains, String replacement) {
        List<Found> found = find(text);
        StringBuilder out = new StringBuilder();
        int at = 0;
        boolean changed = false;
        for (Found link : found) {
            if (allowed(link.text(), allowedDomains)) continue;
            out.append(text, at, link.start()).append(replacement);
            at = link.end();
            changed = true;
        }
        if (!changed) return null;
        return out.append(text.substring(at)).toString();
    }
}
