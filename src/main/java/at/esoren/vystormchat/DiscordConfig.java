package at.esoren.vystormchat;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code discord} section of config.yml. Webhook URLs are kept here but never shown: {@link #toString()} masks
 * them, Core's config editor hides keys named {@code webhook}, and logs name the chat channel instead. The bot token
 * is not part of this class (see {@code DiscordBridge.token}).
 */
final class DiscordConfig {
    /** One chat channel's Discord side: a webhook (Minecraft to Discord) and/or a Discord channel id (both ways with the bot). */
    record Link(String channel, String webhook, String discordId, boolean allowRestricted) {
        boolean hasWebhook() { return !webhook.isEmpty(); }
        boolean hasDiscordId() { return !discordId.isEmpty(); }
        @Override public String toString() {
            return "Link[" + channel + ", webhook=" + (hasWebhook() ? "set" : "none") + ", discord-id=" + discordId + ", allow-restricted=" + allowRestricted + "]";
        }
    }

    private static final Pattern WEBHOOK = Pattern.compile("^https://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api(?:/v\\d{1,2})?/webhooks/\\d{5,21}/[\\w-]{10,200}$");
    private static final Pattern LOCAL_WEBHOOK = Pattern.compile("^http://(?:127\\.0\\.0\\.1|localhost):\\d{2,5}/\\S+$");
    private static final Pattern SNOWFLAKE = Pattern.compile("^\\d{5,21}$");

    static final String DEFAULT_AVATAR = "https://mc-heads.net/avatar/{uuid}/128";
    static final String DEFAULT_FORMAT = "<gray>[<#5865f2>Discord</#5865f2>]</gray> <white><name></white><dark_gray>:</dark_gray> <message>";

    final Map<String, Link> links;
    final String avatar, webhookName, inboundFormat, eventsWebhook, reportsWebhook, invite, configToken;
    final int queueSize;
    final Set<String> blockedUsers;

    static final DiscordConfig NONE = new DiscordConfig(null, Logger.getLogger("VystormChat"), false);

    /** Tests only (set by the unit tests, never by config): webhook URLs of a local fake server count as valid. */
    static volatile boolean allowLocalForTests;

    /**
     * @param localWebhooks tests only: also accept {@code http://127.0.0.1:port/...} as webhook URL
     */
    DiscordConfig(ConfigurationSection section, Logger log, boolean localWebhooks) {
        Map<String, Link> parsed = new LinkedHashMap<>();
        ConfigurationSection channels = section == null ? null : section.getConfigurationSection("channels");
        if (channels != null) {
            for (String key : channels.getKeys(false)) {
                ConfigurationSection entry = channels.getConfigurationSection(key);
                if (entry == null) continue;
                String channel = key.toLowerCase(Locale.ROOT);
                String webhook = webhook(entry.getString("webhook", ""), localWebhooks);
                if (webhook == null) { log.warning("discord.channels." + key + ".webhook is no Discord webhook URL; that channel sends nothing to Discord."); webhook = ""; }
                String id = id(entry.getString("discord-id", ""));
                if (id == null) { log.warning("discord.channels." + key + ".discord-id must be the channel id (only digits); ignored."); id = ""; }
                if (webhook.isEmpty() && id.isEmpty()) continue;
                parsed.put(channel, new Link(channel, webhook, id, entry.getBoolean("allow-restricted", false)));
            }
        }
        links = Collections.unmodifiableMap(parsed);
        avatar = string(section, "avatar", DEFAULT_AVATAR);
        webhookName = string(section, "webhook-name", "{name}");
        inboundFormat = string(section, "bot.format", DEFAULT_FORMAT);
        String events = section == null ? "" : section.getString("events.webhook", "");
        String checkedEvents = webhook(events, localWebhooks);
        if (checkedEvents == null) log.warning("discord.events.webhook is no Discord webhook URL; events use the default channel's webhook.");
        eventsWebhook = checkedEvents == null ? "" : checkedEvents;
        String reports = section == null ? "" : section.getString("reports.webhook", "");
        String checkedReports = webhook(reports, localWebhooks);
        if (checkedReports == null) log.warning("discord.reports.webhook is no Discord webhook URL; reports are not sent.");
        reportsWebhook = checkedReports == null ? "" : checkedReports;
        invite = section == null ? "" : section.getString("invite.link", "").strip();
        configToken = section == null ? "" : section.getString("bot-token", "").strip();
        queueSize = section == null ? 200 : Math.clamp(section.getInt("queue-size", 200), 10, 5000);
        Set<String> blocked = new HashSet<>();
        if (section != null) for (String user : section.getStringList("blocked-users")) if (user != null && SNOWFLAKE.matcher(user.strip()).matches()) blocked.add(user.strip());
        blockedUsers = Collections.unmodifiableSet(blocked);
    }

    private static String string(ConfigurationSection section, String path, String fallback) {
        String value = section == null ? null : section.getString(path);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** "" for none, null for something that is no webhook URL. */
    static String webhook(String value, boolean local) {
        String url = value == null ? "" : value.strip();
        if (url.isEmpty()) return "";
        if (WEBHOOK.matcher(url).matches() || local && LOCAL_WEBHOOK.matcher(url).matches()) return url;
        return null;
    }

    /** "" for none, null for something that is no Discord id. */
    static String id(String value) {
        String id = value == null ? "" : value.strip();
        if (id.isEmpty()) return "";
        return SNOWFLAKE.matcher(id).matches() ? id : null;
    }

    Link link(String channel) { return channel == null ? null : links.get(channel.toLowerCase(Locale.ROOT)); }

    /** The chat channel mapped to a Discord channel id, or null. */
    Link byDiscordId(String discordId) {
        if (discordId == null) return null;
        for (Link link : links.values()) if (link.discordId().equals(discordId)) return link;
        return null;
    }

    @Override public String toString() {
        return "DiscordConfig[links=" + links.values() + ", events-webhook=" + (eventsWebhook.isEmpty() ? "none" : "set")
                + ", reports-webhook=" + (reportsWebhook.isEmpty() ? "none" : "set") + ", bot-token=" + (configToken.isEmpty() ? "none" : "set")
                + ", queue-size=" + queueSize + "]";
    }
}
