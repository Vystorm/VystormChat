package at.esoren.vystormchat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Personal literal phrases; notifications only for normal recipients, never spy copies. */
final class KeywordAlerts {
    static final int MAX_KEYWORDS = 20, MAX_LENGTH = 48;
    private final Map<UUID, Long> lastAlert = new ConcurrentHashMap<>();

    static String normalize(String text) { return text == null ? "" : text.strip().replaceAll("\\s+", " "); }

    static boolean valid(String text) {
        return text.length() >= 2 && text.length() <= MAX_LENGTH && text.codePoints().noneMatch(Character::isISOControl);
    }

    static String match(String text, List<String> keywords, int limit) {
        for (String keyword : keywords.stream().limit(limit).toList()) {
            for (int i = 0; i <= text.length() - keyword.length(); i++)
                if (text.regionMatches(true, i, keyword, 0, keyword.length())) return keyword;
        }
        return null;
    }

    boolean claim(UUID player, long now, long cooldown) {
        boolean[] claimed = {false};
        lastAlert.compute(player, (id, last) -> {
            if (last == null || now - last >= cooldown) { claimed[0] = true; return now; }
            return last;
        });
        return claimed[0];
    }

    void forget(UUID player) { lastAlert.remove(player); }

    void after(ChatPlugin plugin, Delivery delivery) {
        if (!plugin.service().settings().keywordsEnabled) return;
        List<UUID> listeners = delivery.listeners();
        if (listeners.isEmpty()) return;
        plugin.tasks().now(() -> {
            Settings current = plugin.service().settings();
            if (!current.keywordsEnabled) return;
            for (UUID id : listeners) {
                Player player = Bukkit.getPlayer(id);
                Profile profile = plugin.profiles().cached(id);
                if (player == null || profile == null || !player.hasPermission("vystormchat.keywords")
                        || !profile.keywordAlerts() || delivery.mentions(id)) continue;
                String keyword = match(delivery.plain, profile.keywords(), current.keywordLimit);
                if (keyword == null || !claim(id, System.currentTimeMillis(), current.keywordCooldownMillis)) continue;
                plugin.lang().actionBar(player, "keyword-hit", "keyword", keyword,
                        "player", delivery.sender.getName(), "channel", delivery.channel.displayName());
                if (profile.mentionSound()) ChatService.play(plugin.core().compat().platform(), player, current.mentionSound, current.mentionVolume, current.mentionPitch);
            }
        });
    }
}
