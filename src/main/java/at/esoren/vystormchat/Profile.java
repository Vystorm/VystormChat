package at.esoren.vystormchat;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Chat settings of one player: focus channel, channels left or joined, ignored players and personal options.
 * Read from the async chat thread and changed on the server thread, so every field is safe to share.
 */
final class Profile {
    final UUID id;
    private volatile String focus = "";
    /** Channels the player left (auto-join channels) or joined (opt-in channels). */
    final Set<String> left = ConcurrentHashMap.newKeySet();
    final Set<String> joined = ConcurrentHashMap.newKeySet();
    final Set<UUID> ignored = ConcurrentHashMap.newKeySet();
    private volatile boolean mentionSound = true, messageSound = true, timestamps = false, allowMessages = true, spy = false;
    private volatile List<String> keywords = List.of();
    private volatile boolean keywordAlerts = true;
    private volatile boolean dirty;

    List<String> keywords() { return keywords; }

    boolean keywordAlerts() { return keywordAlerts; }

    void keywordAlerts(boolean value) { keywordAlerts = value; dirty = true; }

    synchronized boolean addKeyword(String text, int limit) {
        String keyword = KeywordAlerts.normalize(text);
        if (!KeywordAlerts.valid(keyword) || keywords.size() >= Math.clamp(limit, 1, KeywordAlerts.MAX_KEYWORDS)
                || keywords.stream().anyMatch(existing -> existing.equalsIgnoreCase(keyword))) return false;
        var next = new java.util.ArrayList<>(keywords);
        next.add(keyword);
        keywords = List.copyOf(next);
        dirty = true;
        return true;
    }

    synchronized boolean removeKeyword(String text) {
        String keyword = KeywordAlerts.normalize(text);
        var next = keywords.stream().filter(existing -> !existing.equalsIgnoreCase(keyword)).toList();
        if (next.size() == keywords.size()) return false;
        keywords = next;
        dirty = true;
        return true;
    }

    Profile(UUID id) { this.id = id; }

    String focus() { return focus; }

    void focus(String channel) { focus = channel == null ? "" : channel; dirty = true; }

    boolean mentionSound() { return mentionSound; }

    void mentionSound(boolean value) { mentionSound = value; dirty = true; }

    boolean messageSound() { return messageSound; }

    void messageSound(boolean value) { messageSound = value; dirty = true; }

    boolean timestamps() { return timestamps; }

    void timestamps(boolean value) { timestamps = value; dirty = true; }

    boolean allowMessages() { return allowMessages; }

    void allowMessages(boolean value) { allowMessages = value; dirty = true; }

    boolean spy() { return spy; }

    void spy(boolean value) { spy = value; dirty = true; }

    /** Whether the player receives this channel (auto-join channels until left, others once joined). */
    boolean listening(Channel channel) {
        if (!channel.leavable()) return true;
        return channel.autoJoin() ? !left.contains(channel.id()) : joined.contains(channel.id());
    }

    void listen(Channel channel, boolean on) {
        if (on) { left.remove(channel.id()); joined.add(channel.id()); }
        else { joined.remove(channel.id()); left.add(channel.id()); }
        dirty = true;
    }

    boolean ignores(UUID other) { return ignored.contains(other); }

    /** Toggles ignoring; returns the new state. */
    boolean toggleIgnore(UUID other) {
        dirty = true;
        if (ignored.remove(other)) return false;
        ignored.add(other);
        return true;
    }

    void markDirty() { dirty = true; }

    boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    // ------------------------------------------------------------------ YAML

    String toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        yaml.set("focus", focus);
        yaml.set("left", sorted(left));
        yaml.set("joined", sorted(joined));
        yaml.set("ignored", ignored.stream().map(UUID::toString).sorted().toList());
        yaml.set("mention-sound", mentionSound);
        yaml.set("message-sound", messageSound);
        yaml.set("timestamps", timestamps);
        yaml.set("allow-messages", allowMessages);
        yaml.set("spy", spy);
        yaml.set("keywords", keywords);
        yaml.set("keyword-alerts", keywordAlerts);
        return yaml.saveToString();
    }

    private static List<String> sorted(Set<String> values) {
        List<String> list = new java.util.ArrayList<>(values);
        Collections.sort(list);
        return list;
    }

    /** Parses a profile; throws {@link org.bukkit.configuration.InvalidConfigurationException} for broken YAML. */
    static Profile fromYaml(UUID id, String text) throws org.bukkit.configuration.InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        Profile profile = new Profile(id);
        profile.focus = yaml.getString("focus", "").toLowerCase(Locale.ROOT);
        for (String channel : yaml.getStringList("left")) profile.left.add(channel.toLowerCase(Locale.ROOT));
        for (String channel : yaml.getStringList("joined")) profile.joined.add(channel.toLowerCase(Locale.ROOT));
        for (String other : yaml.getStringList("ignored")) {
            try { profile.ignored.add(UUID.fromString(other.trim())); } catch (IllegalArgumentException skipped) { /* broken entry */ }
        }
        profile.mentionSound = yaml.getBoolean("mention-sound", true);
        profile.messageSound = yaml.getBoolean("message-sound", true);
        profile.timestamps = yaml.getBoolean("timestamps", false);
        profile.allowMessages = yaml.getBoolean("allow-messages", true);
        profile.spy = yaml.getBoolean("spy", false);
        for (String keyword : yaml.getStringList("keywords")) profile.addKeyword(keyword, KeywordAlerts.MAX_KEYWORDS);
        profile.keywordAlerts = yaml.getBoolean("keyword-alerts", true);
        profile.dirty = false;
        return profile;
    }
}
