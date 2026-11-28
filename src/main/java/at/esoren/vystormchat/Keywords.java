package at.esoren.vystormchat;

import java.util.UUID;

/**
 * The keyword actions shared by {@code /vchat keywords add|remove|pause|resume|test}, the keyword screen and its chest:
 * each answers with the same language key the command always replied with.
 */
final class Keywords {
    record Reply(boolean ok, String key, Object... placeholders) {}

    private Keywords() { }

    static Reply add(ChatPlugin plugin, UUID player, String phrase) {
        Settings settings = plugin.service().settings();
        if (!settings.keywordsEnabled) return new Reply(false, "feature-off");
        boolean added = plugin.profiles().get(player).addKeyword(phrase, settings.keywordLimit);
        return new Reply(added, added ? "keyword-added" : "keyword-invalid", "limit", settings.keywordLimit);
    }

    static Reply remove(ChatPlugin plugin, UUID player, String phrase) {
        boolean removed = plugin.profiles().get(player).removeKeyword(phrase);
        return new Reply(removed, removed ? "keyword-removed" : "keyword-missing");
    }

    static Reply state(ChatPlugin plugin, UUID player, boolean enabled) {
        plugin.profiles().get(player).keywordAlerts(enabled);
        return new Reply(true, enabled ? "keywords-active" : "keywords-paused");
    }

    static Reply test(ChatPlugin plugin, UUID player, String message) {
        Settings settings = plugin.service().settings();
        if (!settings.keywordsEnabled) return new Reply(false, "feature-off");
        String keyword = KeywordAlerts.match(message == null ? "" : message, plugin.profiles().get(player).keywords(), settings.keywordLimit);
        return keyword == null ? new Reply(true, "keyword-test-miss") : new Reply(true, "keyword-test-hit", "keyword", keyword);
    }
}
