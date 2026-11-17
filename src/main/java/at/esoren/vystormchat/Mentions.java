package at.esoren.vystormchat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds mentioned players in a message: {@code @Name} always, the bare name too when enabled ({@code plainNames}),
 * and {@code @everyone}/{@code @here} for players allowed to use them. Names compare case-insensitively.
 */
final class Mentions {
    static final String EVERYONE = "@everyone";

    private static final Pattern WORD = Pattern.compile("(@?)([A-Za-z0-9_]{1,16})(?![A-Za-z0-9_])");

    /** A mention in the text: position, the mentioned player (null for @everyone). */
    record Hit(int start, int end, UUID player, boolean everyone) {}

    private Mentions() {}

    /**
     * @param online lower-case name → player id of everyone online
     */
    static List<Hit> find(String text, Map<String, UUID> online, boolean plainNames, boolean everyoneAllowed) {
        List<Hit> hits = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            if (matcher.start() > 0) {
                char before = text.charAt(matcher.start() - 1);
                if (Character.isLetterOrDigit(before) || before == '_' || before == '@') continue;
            }
            boolean at = !matcher.group(1).isEmpty();
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            if (at && everyoneAllowed && (name.equals("everyone") || name.equals("here"))) {
                hits.add(new Hit(matcher.start(), matcher.end(), null, true));
                continue;
            }
            if (!at && !plainNames) continue;
            if (!at && name.length() < 3) continue; // "me", "ok" ... never mention someone by accident
            UUID id = online.get(name);
            if (id != null) hits.add(new Hit(matcher.start(), matcher.end(), id, false));
        }
        return hits;
    }

    static Set<UUID> players(List<Hit> hits) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Hit hit : hits) if (hit.player() != null) ids.add(hit.player());
        return ids;
    }

    static boolean everyone(List<Hit> hits) {
        for (Hit hit : hits) if (hit.everyone()) return true;
        return false;
    }
}
