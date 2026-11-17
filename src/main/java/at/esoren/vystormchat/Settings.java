package at.esoren.vystormchat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Everything read from {@code config.yml}; a reload builds a new instance, so readers always see one consistent set. */
final class Settings {
    enum FilterAction { CENSOR, BLOCK }

    /** Features with an own on/off switch: reports.enabled, private-messages.enabled, ignore.enabled, mutes.enabled. */
    enum Feature { REPORTS, PRIVATE_MESSAGES, IGNORE, MUTES }

    final Map<String, Channel> channels;
    final String defaultChannel;
    final Formats formats;
    final List<String> hoverCard;
    final String timestampFormat;
    final boolean notifyNobodyHeard, consoleLog, placeholderApi, clientTabs;
    final int maxLength;

    final boolean mentionsEnabled, mentionsPlainNames, mentionActionBar;
    final String mentionHighlight, mentionHighlightSelf, mentionSound;
    final float mentionVolume, mentionPitch;
    final long mentionCooldownMillis;

    final boolean showcaseEnabled;
    final boolean emojisEnabled;
    final Map<String, String> emojis;
    final Set<String> itemTokens, inventoryTokens, positionTokens;

    final String linkFormat;
    final FilterAction linkAction;
    final Set<String> allowedDomains;
    final String linkReplacement;

    final SpamGuard.Settings spam;
    final WordFilter filter;
    final FilterAction filterAction;
    final boolean notifyStaffOnBlock;
    final Set<String> mutedCommands;
    final int clearLines;

    final boolean reportsEnabled, privateMessagesEnabled, ignoreEnabled, mutesEnabled;

    final String pmTo, pmFrom, pmSpy, pmSound;
    final float pmVolume, pmPitch;

    final boolean logEnabled;
    final double logMaxMegabytes;
    final int logKeepDays;

    final boolean regionLookup;
    final boolean keywordsEnabled;
    final int keywordLimit;
    final long keywordCooldownMillis;

    /** Lower-case PlaceholderAPI tokens without %, whose results keep their colours and tags. */
    final Set<String> trustedPlaceholders;
    /** Channel ids whose chat stays in the server chat for other plugins; "*" = all. */
    final Set<String> relayChannels;

    Settings(FileConfiguration config, Logger log) {
        channels = Channel.parseAll(config.getConfigurationSection("channels"), log);
        if (channels.isEmpty()) {
            log.warning("No valid channel in config.yml; using a plain global channel");
            channels.put("global", new Channel("global", "Global", "<gray>[G]</gray>", "white", Channel.Scope.GLOBAL,
                    "<channel> <name> <dark_gray>»</dark_gray> <message>", "", List.of(), "", "", 0, 0, true, true, false,
                    Set.of(), 100, Set.of(), "global", "", "OAK_SIGN", false));
        }
        String wanted = config.getString("default-channel", "global").toLowerCase(Locale.ROOT);
        if (!channels.containsKey(wanted)) {
            String first = channels.keySet().iterator().next();
            log.warning("default-channel '" + wanted + "' does not exist; using '" + first + "'");
            wanted = first;
        }
        defaultChannel = wanted;
        formats = Formats.parse(config.getConfigurationSection("name-formats"));
        hoverCard = lower(config.getStringList("hover-card"));
        String pattern = config.getString("timestamp-format", "HH:mm:ss");
        try {
            java.time.format.DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException invalid) {
            log.warning("timestamp-format '" + pattern + "' is no valid time pattern; using HH:mm:ss");
            pattern = "HH:mm:ss";
        }
        timestampFormat = pattern;
        notifyNobodyHeard = config.getBoolean("notify-nobody-heard", true);
        consoleLog = config.getBoolean("console-log", true);
        placeholderApi = config.getBoolean("placeholderapi", true);
        trustedPlaceholders = new HashSet<>();
        for (String token : config.getStringList("placeholderapi-trusted")) trustedPlaceholders.add(placeholderKey(token));
        relayChannels = new HashSet<>(config.isList("relay.only-channels") ? lower(config.getStringList("relay.only-channels")) : List.of("global"));
        clientTabs = config.getBoolean("client-tabs", true);
        maxLength = Math.clamp(config.getInt("max-message-length", 256), 16, 256);

        mentionsEnabled = config.getBoolean("mentions.enabled", true);
        mentionsPlainNames = config.getBoolean("mentions.plain-names", true);
        mentionActionBar = config.getBoolean("mentions.action-bar", true);
        mentionHighlight = config.getString("mentions.highlight", "<aqua><mention></aqua>");
        mentionHighlightSelf = config.getString("mentions.highlight-self", "<yellow><bold><mention></bold></yellow>");
        mentionSound = config.getString("mentions.sound", "entity.experience_orb.pickup");
        mentionVolume = (float) config.getDouble("mentions.volume", 0.8);
        mentionPitch = (float) config.getDouble("mentions.pitch", 1.4);
        mentionCooldownMillis = Math.max(0, config.getInt("mentions.cooldown-seconds", 3)) * 1000L;

        showcaseEnabled = config.getBoolean("showcase.enabled", true);
        emojisEnabled = config.getBoolean("emojis.enabled", true);
        emojis = Emojis.read(config.getConfigurationSection("emojis.codes"));
        itemTokens = tokens(config.getStringList("showcase.item"), List.of("[item]", "[i]"));
        inventoryTokens = tokens(config.getStringList("showcase.inventory"), List.of("[inv]", "[inventory]"));
        positionTokens = tokens(config.getStringList("showcase.position"), List.of("[pos]", "[loc]"));

        linkFormat = config.getString("links.format", "<aqua><underlined><link></underlined></aqua>");
        linkAction = action(config.getString("links.without-permission", "censor"));
        allowedDomains = new HashSet<>(lower(config.getStringList("links.allowed-domains")));
        linkReplacement = config.getString("links.replacement", "[link removed]");

        ConfigurationSection spamSection = config.getConfigurationSection("moderation.spam");
        spam = spamSection == null ? SpamGuard.Settings.OFF : new SpamGuard.Settings(
                spamSection.getBoolean("enabled", true),
                Math.max(0, spamSection.getInt("repeat-seconds", 30)) * 1000L,
                Math.max(0, spamSection.getInt("flood-messages", 4)),
                Math.max(0, spamSection.getInt("flood-seconds", 5)) * 1000L,
                Math.max(1, spamSection.getInt("caps-min-letters", 8)),
                Math.clamp(spamSection.getInt("caps-max-percent", 70), 1, 100),
                "block".equalsIgnoreCase(spamSection.getString("caps-action", "lowercase")),
                Math.max(0, spamSection.getInt("max-repeated-characters", 4)));
        boolean filterOn = config.getBoolean("moderation.filter.enabled", true);
        filter = filterOn ? WordFilter.of(config.getStringList("moderation.filter.words"), config.getStringList("moderation.filter.regex"),
                config.getString("moderation.filter.replacement", "*"), log) : WordFilter.NONE;
        filterAction = action(config.getString("moderation.filter.action", "censor"));
        notifyStaffOnBlock = config.getBoolean("moderation.notify-staff", true);
        mutedCommands = new HashSet<>();
        for (String command : config.getStringList("moderation.muted-commands")) mutedCommands.add(command.toLowerCase(Locale.ROOT).replaceFirst("^/", "").trim());
        clearLines = Math.clamp(config.getInt("moderation.clear-lines", 100), 10, 300);

        reportsEnabled = config.getBoolean("reports.enabled", true);
        privateMessagesEnabled = config.getBoolean("private-messages.enabled", true);
        ignoreEnabled = config.getBoolean("ignore.enabled", true);
        mutesEnabled = config.getBoolean("mutes.enabled", true);

        pmTo = config.getString("private-messages.format-to", "<gray>[<white>me</white> <dark_gray>→</dark_gray> <name>]</gray> <white><message>");
        pmFrom = config.getString("private-messages.format-from", "<gray>[<name> <dark_gray>→</dark_gray> <white>me</white>]</gray> <white><message>");
        pmSpy = config.getString("private-messages.format-spy", "<dark_gray>[Spy] <gray><sender> → <receiver>:</gray> <message>");
        pmSound = config.getString("private-messages.sound", "block.note_block.pling");
        pmVolume = (float) config.getDouble("private-messages.volume", 0.6);
        pmPitch = (float) config.getDouble("private-messages.pitch", 1.6);

        keywordsEnabled = config.getBoolean("keyword-alerts.enabled", true);
        keywordLimit = Math.clamp(config.getInt("keyword-alerts.max-keywords", 10), 1, KeywordAlerts.MAX_KEYWORDS);
        keywordCooldownMillis = Math.clamp(config.getInt("keyword-alerts.cooldown-seconds", 10), 0, 3600) * 1000L;

        logEnabled = config.getBoolean("log.enabled", true);
        logMaxMegabytes = Math.max(0.1, config.getDouble("log.max-file-mb", 10.0));
        logKeepDays = Math.max(0, config.getInt("log.keep-days", 30));

        regionLookup = channels.values().stream().anyMatch(channel -> channel.scope() == Channel.Scope.REGION);
    }

    /** Add only the new section's missing leaves, preserving existing owner settings. */
    static boolean addKeywordDefaults(FileConfiguration config) {
        boolean changed = false;
        for (var entry : Map.<String, Object>of("enabled", true, "max-keywords", 10, "cooldown-seconds", 10).entrySet()) {
            String key = "keyword-alerts." + entry.getKey();
            if (!config.contains(key, true)) { config.set(key, entry.getValue()); changed = true; }
        }
        return changed;
    }

    private static FilterAction action(String text) {
        return "block".equalsIgnoreCase(text == null ? "" : text.trim()) ? FilterAction.BLOCK : FilterAction.CENSOR;
    }

    private static List<String> lower(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values) if (value != null && !value.isBlank()) out.add(value.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static Set<String> tokens(List<String> configured, List<String> fallback) {
        List<String> list = lower(configured);
        return Set.copyOf(list.isEmpty() ? fallback : list);
    }

    /**
     * Channels whose forward command does not exist (e.g. partychat without Vystorm Parties) deliver their messages
     * themselves instead. Call before the settings are used.
     */
    void dropMissingForwards(java.util.function.Predicate<String> commandExists, Logger log) {
        channels.replaceAll((id, channel) -> {
            if (channel.forwardCommand().isEmpty()) return channel;
            String command = channel.forwardCommand().split(" ", 2)[0];
            if (commandExists.test(command)) return channel;
            if (log != null) log.fine("Channel '" + id + "': /" + command + " does not exist (yet), messages are delivered by Vystorm Chat");
            return channel.withoutForward();
        });
    }

    /** Whether messages of this channel stay in the server chat, where other plugins (DiscordSRV ...) see them. */
    boolean relayed(Channel channel) { return relayChannels.contains("*") || relayChannels.contains(channel.id().toLowerCase(Locale.ROOT)); }

    /** Whether a PlaceholderAPI token (with or without %) may keep its colours and tags. */
    boolean trustedPlaceholder(String token) { return trustedPlaceholders.contains(placeholderKey(token)); }

    static String placeholderKey(String token) {
        String key = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("%")) key = key.substring(1);
        if (key.endsWith("%")) key = key.substring(0, key.length() - 1);
        return key;
    }

    /** Whether the feature is switched on in config.yml. */
    boolean enabled(Feature feature) {
        return switch (feature) {
            case REPORTS -> reportsEnabled;
            case PRIVATE_MESSAGES -> privateMessagesEnabled;
            case IGNORE -> ignoreEnabled;
            case MUTES -> mutesEnabled;
        };
    }

    Channel channel(String id) { return id == null ? null : channels.get(id.toLowerCase(Locale.ROOT)); }

    Channel defaultChannel() { return channels.get(defaultChannel); }

    /** The channel {@code /vtrade} is bridged into, or null. */
    Channel coreTrade() {
        for (Channel channel : channels.values()) if (channel.coreTrade()) return channel;
        return null;
    }
}
