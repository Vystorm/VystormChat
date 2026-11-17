package at.esoren.vystormchat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/**
 * One chat channel from {@code config.yml}. Immutable; a reload builds new instances.
 *
 * @param id             lower-case key in the config
 * @param scope          who hears a message (see {@link Scope})
 * @param regionKinds    for {@link Scope#REGION}: Core region kinds that count (empty = any region)
 * @param forwardCommand when set, accepted messages are passed to this command (e.g. {@code partychat}) instead of
 *                       being delivered here, so another plugin's chat stays the one place for that group
 */
record Channel(String id, String displayName, String tag, String color, Scope scope, String format, String quickPrefix,
               List<String> aliases, String readPermission, String writePermission, int cooldownSeconds, double cost,
               boolean leavable, boolean autoJoin, boolean spy, Set<String> worlds, int radius, Set<String> regionKinds,
               String clientTab, String forwardCommand, String icon, boolean coreTrade) {

    /** Who receives a message. */
    enum Scope {
        GLOBAL, WORLD, LOCAL, REGION, PARTY, CITY, FACTION;

        static Scope parse(String text) {
            if (text == null) return null;
            try { return valueOf(text.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException unknown) { return null; }
        }

        /** Scopes that need the sender to belong to a group (party, city, faction). */
        boolean group() { return this == PARTY || this == CITY || this == FACTION; }
    }

    static final String ID_PATTERN = "[a-z0-9_-]{1,32}";

    Channel {
        aliases = List.copyOf(aliases);
        worlds = Set.copyOf(worlds);
        regionKinds = Set.copyOf(regionKinds);
    }

    /** The same channel delivering its messages itself (used when the forward command does not exist). */
    Channel withoutForward() {
        return new Channel(id, displayName, tag, color, scope, format, quickPrefix, aliases, readPermission, writePermission, cooldownSeconds, cost,
                leavable, autoJoin, spy, worlds, radius, regionKinds, clientTab, "", icon, coreTrade);
    }

    boolean readable(java.util.function.Predicate<String> permission) { return readPermission.isEmpty() || permission.test(readPermission); }

    boolean writable(java.util.function.Predicate<String> permission) { return writePermission.isEmpty() || permission.test(writePermission); }

    /** World filter: empty = every world. Names compare case-insensitively. */
    boolean inWorld(String world) { return worlds.isEmpty() || world != null && worlds.contains(world.toLowerCase(Locale.ROOT)); }

    /** Parses all channels below {@code section}; broken entries are skipped with a warning. Order = config order. */
    static Map<String, Channel> parseAll(ConfigurationSection section, Logger log) {
        Map<String, Channel> channels = new LinkedHashMap<>();
        if (section == null) return channels;
        Set<String> prefixes = new java.util.HashSet<>(), aliasSeen = new java.util.HashSet<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            String id = key.toLowerCase(Locale.ROOT);
            if (entry == null || !id.matches(ID_PATTERN)) { warn(log, "Channel '" + key + "' skipped: the name may only use a-z, 0-9, _ and -"); continue; }
            Scope scope = Scope.parse(entry.getString("scope", "global"));
            if (scope == null) { warn(log, "Channel '" + key + "' skipped: unknown scope '" + entry.getString("scope") + "'"); continue; }
            String prefix = entry.getString("quick-prefix", "").trim();
            if (!prefix.isEmpty() && !prefixes.add(prefix)) { warn(log, "Channel '" + key + "': quick prefix '" + prefix + "' is used twice, ignored here"); prefix = ""; }
            List<String> aliases = new ArrayList<>();
            for (String alias : entry.getStringList("aliases")) {
                String clean = alias.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", "");
                if (clean.matches("[a-z0-9_-]{1,32}") && aliasSeen.add(clean)) aliases.add(clean);
                else warn(log, "Channel '" + key + "': alias '" + alias + "' ignored (invalid or used twice)");
            }
            Set<String> worlds = new java.util.HashSet<>();
            for (String world : entry.getStringList("worlds")) if (!world.isBlank()) worlds.add(world.trim().toLowerCase(Locale.ROOT));
            Set<String> kinds = new java.util.HashSet<>();
            for (String kind : entry.getStringList("region-kinds")) if (!kind.isBlank()) kinds.add(kind.trim().toLowerCase(Locale.ROOT));
            String tab = entry.getString("client-tab", scope == Scope.PARTY ? "party" : "global").trim().toLowerCase(Locale.ROOT);
            if (!Set.of("global", "party", "trade", "system", "none").contains(tab)) tab = "none";
            channels.put(id, new Channel(id,
                    entry.getString("display-name", Character.toUpperCase(id.charAt(0)) + id.substring(1)),
                    entry.getString("tag", "<gray>[" + id + "]</gray>"),
                    entry.getString("color", "white"),
                    scope,
                    entry.getString("format", "<channel> <name> <dark_gray>»</dark_gray> <message>"),
                    prefix, aliases,
                    entry.getString("read-permission", "").trim(),
                    entry.getString("write-permission", "").trim(),
                    Math.max(0, entry.getInt("cooldown", 0)),
                    Math.max(0, entry.getDouble("cost", 0)),
                    entry.getBoolean("leavable", true),
                    entry.getBoolean("auto-join", true),
                    entry.getBoolean("spy", false),
                    worlds,
                    Math.max(1, entry.getInt("radius", 100)),
                    kinds, tab,
                    entry.getString("forward-command", "").trim().replaceFirst("^/", ""),
                    entry.getString("icon", "OAK_SIGN"),
                    entry.getBoolean("core-trade", false)));
        }
        return channels;
    }

    private static void warn(Logger log, String text) { if (log != null) log.warning(text); }

    /**
     * Which channel a message with a quick prefix goes to. Returns the channel and the text without the prefix, or
     * null when no prefix matches. A message that is only the prefix (or the prefix repeated, like "!!!") is no
     * prefixed message. The longest matching prefix wins.
     */
    static Prefixed quickPrefix(Iterable<Channel> channels, String text) {
        Channel best = null;
        for (Channel channel : channels) {
            String prefix = channel.quickPrefix();
            if (prefix.isEmpty() || !text.startsWith(prefix)) continue;
            if (best == null || prefix.length() > best.quickPrefix().length()) best = channel;
        }
        if (best == null) return null;
        String rest = text.substring(best.quickPrefix().length()).strip();
        if (rest.isEmpty() || rest.startsWith(best.quickPrefix()) && rest.replace(best.quickPrefix(), "").isBlank()) return null;
        return new Prefixed(best, rest);
    }

    record Prefixed(Channel channel, String text) {}
}
