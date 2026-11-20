package at.esoren.vystormchat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * Vystorm Chat's Discord bridge without a Discord library.
 *
 * <p>Level 1 ({@code discord.enabled}): chat channels with a webhook post their messages to Discord with the
 * player's name and head; server events go to the events webhook. Level 2 ({@code discord.bot.enabled}): the bot
 * reads the mapped Discord channels through the gateway and shows their messages in the chat channel, after the
 * plugin's word filter, link filter and anti-spam; it can post for channels that only have a Discord id and show the
 * player count as its status.
 *
 * <p>Only channels named under {@code discord.channels} with a global scope take part; channels with a read
 * permission that not everyone has (staff ...) need {@code allow-restricted: true}. Private messages never pass.
 * Everything that touches the network runs off the server thread; the server thread only builds strings and queues.
 */
final class DiscordBridge {
    /** What the bridge needs from the running plugin; the tests give their own. */
    interface Host {
        void async(Runnable task);
        /** Runs on the server thread. */
        void main(Runnable task);
        Logger log();
        /** A text of the language files in the server's language, without formatting. */
        String text(String key, Object... placeholders);
        /** Whether everyone has this permission by default. */
        boolean publicPermission(String permission);
        Path dataFolder();
        String environment(String name);
        boolean pluginPresent(String name);
        int onlinePlayers();
        int maxPlayers();
        /** Server thread: shows a Discord line to everyone who reads the channel, console and chat log. */
        void show(Channel channel, Component line, String logLine);
    }

    enum TokenSource { ENVIRONMENT, FILE, CONFIG, NONE }

    static final String TOKEN_ENV = "VYSTORM_CHAT_DISCORD_TOKEN";
    static final String TOKEN_FILE = "discord-token.txt";
    static final URI API = URI.create("https://discord.com/api/v10");

    private final Host host;
    private final DiscordGateway.Transport transport;
    private final LongSupplier clock;
    private final URI api, gatewayUrl;
    private final DiscordRest rest;
    private final SpamGuard spam = new SpamGuard();
    private volatile Settings settings;
    private volatile DiscordGateway gateway;
    private volatile String token = "";
    private volatile TokenSource tokenSource = TokenSource.NONE;
    private volatile String lastWarning = "";
    private boolean warnedDiscordSrv, started;
    private int lastCount = -1;
    private long lastPresence = Long.MIN_VALUE / 2;

    DiscordBridge(Host host, DiscordRest.Http http, DiscordGateway.Transport transport, LongSupplier clock, URI api, URI gatewayUrl) {
        this.host = host;
        this.transport = transport;
        this.clock = clock;
        this.api = api == null ? API : api;
        this.gatewayUrl = gatewayUrl;
        this.rest = new DiscordRest(http, clock, host::async, host.log(), 200, () -> token);
    }

    // ------------------------------------------------------------------ configuration

    /** Start and every reload. Connects, reconnects with a new token or disconnects the bot as the switches say. */
    synchronized void configure(Settings settings) {
        this.settings = settings;
        spam.configure(settings.spam);
        rest.cap(settings.discord.queueSize);
        rest.reset();
        Logger log = host.log();
        for (Settings.Feature feature : Settings.Feature.values()) {
            if (!feature.name().startsWith("DISCORD") || !settings.enabled(feature)) continue;
            Settings.Feature missing = settings.missing(feature);
            if (missing != null) log.warning(feature.path + " is on but needs " + missing.path + ", which is off; it does nothing.");
        }
        if (!settings.active(Settings.Feature.DISCORD)) { stopGateway(); return; }
        for (DiscordConfig.Link link : settings.discord.links.values()) {
            Channel channel = settings.channel(link.channel());
            String problem = problem(settings, link, channel);
            if (problem != null) log.warning("discord.channels." + link.channel() + ": " + problem);
        }
        if (settings.discord.links.isEmpty()) log.info("Discord bridge is on, but no channel under discord.channels has a webhook or a discord-id yet.");
        if (!warnedDiscordSrv && host.pluginPresent("DiscordSRV")) {
            warnedDiscordSrv = true;
            log.warning("DiscordSRV is installed too. Channels in relay.only-channels reach DiscordSRV and Vystorm Chat's own bridge, so a channel "
                    + "bridged by both posts twice. Bridge each channel in only one of them.");
        }
        readToken(settings);
        if (!settings.active(Settings.Feature.DISCORD_BOT)) { stopGateway(); return; }
        if (token.isEmpty()) {
            stopGateway();
            lastWarning = "no bot token";
            log.warning("discord.bot.enabled is on, but there is no bot token: put it into plugins/VystormChat/" + TOKEN_FILE
                    + " (or the environment variable " + TOKEN_ENV + ") and run /vchat reload.");
            return;
        }
        DiscordGateway current = gateway;
        if (current != null && current.state() != DiscordGateway.State.STOPPED && current.state() != DiscordGateway.State.OFF && same(current)) return;
        stopGateway();
        DiscordGateway created = new DiscordGateway(transport, clock, log, token, gatewayUrl, this::inbound, Math::random);
        gatewayToken = token;
        gateway = created;
        created.start();
    }

    private String gatewayToken = "";

    private boolean same(DiscordGateway current) { return current == gateway && token.equals(gatewayToken); }

    /** Environment variable first, then discord-token.txt, then discord.bot-token in config.yml. */
    private void readToken(Settings settings) {
        String fromEnv = clean(host.environment(TOKEN_ENV));
        if (!fromEnv.isEmpty()) { token = fromEnv; tokenSource = TokenSource.ENVIRONMENT; return; }
        Path file = host.dataFolder() == null ? null : host.dataFolder().resolve(TOKEN_FILE);
        if (file != null && Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String value = clean(line);
                    if (!value.isEmpty() && !value.startsWith("#")) { token = value; tokenSource = TokenSource.FILE; return; }
                }
            } catch (IOException unreadable) {
                host.log().warning("Discord: " + TOKEN_FILE + " could not be read (" + unreadable.getClass().getSimpleName() + ").");
            }
        }
        String fromConfig = clean(settings.discord.configToken);
        token = fromConfig;
        tokenSource = fromConfig.isEmpty() ? TokenSource.NONE : TokenSource.CONFIG;
    }

    private static String clean(String value) {
        String out = value == null ? "" : value.strip();
        if (out.regionMatches(true, 0, "Bot ", 0, 4)) out = out.substring(4).strip();
        return out;
    }

    /** Why a mapped channel does not take part, or null. */
    String problem(Settings settings, DiscordConfig.Link link, Channel channel) {
        if (channel == null) return "there is no chat channel with this id";
        if (channel.scope() != Channel.Scope.GLOBAL) return "only channels with scope global can be bridged (this one is " + channel.scope().name().toLowerCase(Locale.ROOT) + ")";
        if (!channel.forwardCommand().isEmpty()) return "this channel hands its messages to /" + channel.forwardCommand() + " and cannot be bridged";
        if (!channel.readPermission().isEmpty() && !link.allowRestricted() && !host.publicPermission(channel.readPermission()))
            return "only some players may read this channel (" + channel.readPermission() + "); set allow-restricted: true to bridge it anyway";
        return null;
    }

    /** The link of a channel that takes part in the bridge right now, or null. */
    private DiscordConfig.Link bridged(Settings settings, Channel channel) {
        if (channel == null) return null;
        DiscordConfig.Link link = settings.discord.link(channel.id());
        return link == null || problem(settings, link, channel) != null ? null : link;
    }

    // ------------------------------------------------------------------ Minecraft -> Discord

    /** One accepted chat message (after mutes, filters and anti-spam). Any thread; only queues. */
    void chat(Channel channel, UUID player, String name, String plain) {
        Settings s = settings;
        if (s == null || !s.active(Settings.Feature.DISCORD)) return;
        DiscordConfig.Link link = bridged(s, channel);
        if (link == null) return;
        boolean users = s.active(Settings.Feature.DISCORD_USER_MENTIONS);
        String content = DiscordText.content(plain, users);
        if (content.isBlank()) return;
        String label = "channel " + channel.id();
        if (link.hasWebhook()) {
            rest.submit(webhookJob(link.webhook(), label, content, DiscordText.webhookName(DiscordText.fill(s.discord.webhookName, player, name, false)),
                    DiscordText.fill(s.discord.avatar, player, name, true), users));
        } else if (link.hasDiscordId() && botOn(s)) {
            rest.submit(botJob(link.discordId(), label, DiscordText.cut("**" + DiscordText.escapeMarkdown(name) + "**: " + content, DiscordText.MAX_CONTENT), users));
        }
    }

    private boolean botOn(Settings s) { return s.active(Settings.Feature.DISCORD_BOT) && !token.isEmpty(); }

    /** A server event line ({@code key} in the language files) if its switch is on. */
    void event(Settings.Feature feature, String key, Object... placeholders) {
        Settings s = settings;
        if (s == null || !s.active(feature)) return;
        String content = DiscordText.cut(DiscordText.neutralise(host.text(key, placeholders), false), DiscordText.MAX_CONTENT);
        DiscordRest.Job job = eventJob(s, content);
        if (job != null) rest.submit(job);
    }

    private DiscordRest.Job eventJob(Settings s, String content) {
        if (!s.discord.eventsWebhook.isEmpty()) return webhookJob(s.discord.eventsWebhook, "events webhook", content, null, null, false);
        List<DiscordConfig.Link> candidates = new ArrayList<>();
        DiscordConfig.Link preferred = bridged(s, s.defaultChannel());
        if (preferred != null) candidates.add(preferred);
        for (Channel channel : s.channels.values()) {
            DiscordConfig.Link link = bridged(s, channel);
            if (link != null && !candidates.contains(link)) candidates.add(link);
        }
        for (DiscordConfig.Link link : candidates) {
            if (link.hasWebhook()) return webhookJob(link.webhook(), "channel " + link.channel(), content, null, null, false);
            if (link.hasDiscordId() && botOn(s)) return botJob(link.discordId(), "channel " + link.channel(), content, false);
        }
        return null;
    }

    /** A new player report for the staff webhook ({@code discord.reports.webhook}). */
    void report(String reporter, String target, String reason) {
        Settings s = settings;
        if (s == null || !s.active(Settings.Feature.DISCORD_REPORTS) || s.discord.reportsWebhook.isEmpty()) return;
        String content = DiscordText.cut(DiscordText.neutralise(host.text("discord.report", "reporter", DiscordText.escapeMarkdown(reporter),
                "player", DiscordText.escapeMarkdown(target), "reason", DiscordText.escapeMarkdown(reason)), false), DiscordText.MAX_CONTENT);
        rest.submit(webhookJob(s.discord.reportsWebhook, "reports webhook", content, null, null, false));
    }

    /** {@code /vchat discord test CHANNEL}: null when queued, else the lang key of the reason. */
    String test(String channelId, String by) {
        Settings s = settings;
        if (s == null) return "discord.test-failed";
        Channel channel = s.channel(channelId);
        if (channel == null) return "channel-unknown";
        DiscordConfig.Link link = s.discord.link(channel.id());
        if (link == null || problem(s, link, channel) != null) return "discord.test-unmapped";
        if (!link.hasWebhook() && !botOn(s)) return "discord.test-unmapped";
        String content = DiscordText.content(host.text("discord.test-line", "channel", channel.displayName(), "player", by), false);
        DiscordRest.Job job = link.hasWebhook() ? webhookJob(link.webhook(), "channel " + channel.id(), content, null, null, false)
                : botJob(link.discordId(), "channel " + channel.id(), content, false);
        return rest.submit(job) ? null : "discord.test-failed";
    }

    private DiscordRest.Job webhookJob(String url, String label, String content, String username, String avatar, boolean users) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        if (username != null) body.addProperty("username", username);
        if (avatar != null && !avatar.isBlank()) body.addProperty("avatar_url", avatar);
        body.add("allowed_mentions", allowedMentions(users));
        return new DiscordRest.Job("webhook:" + Integer.toHexString(url.hashCode()), "webhook of " + label, URI.create(url), false, body.toString(), 0);
    }

    private DiscordRest.Job botJob(String discordId, String label, String content, boolean users) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        body.add("allowed_mentions", allowedMentions(users));
        return new DiscordRest.Job("channel:" + discordId, "bot channel of " + label, URI.create(api + "/channels/" + discordId + "/messages"), true, body.toString(), 0);
    }

    /** Nothing pings, except user mentions when discord.user-mentions.enabled is on. Never @everyone, @here or roles. */
    static JsonObject allowedMentions(boolean users) {
        JsonObject mentions = new JsonObject();
        JsonArray parse = new JsonArray();
        if (users) parse.add("users");
        mentions.add("parse", parse);
        return mentions;
    }

    // ------------------------------------------------------------------ Discord -> Minecraft

    /** A line accepted from Discord: who wrote, the text as players will read it. */
    record Line(Channel channel, String authorId, String name, String text) {}

    /** MESSAGE_CREATE from the gateway (gateway thread). */
    void inbound(JsonObject message) {
        Settings s = settings;
        DiscordGateway current = gateway;
        if (s == null) return;
        Line line = accept(s, message, current == null ? null : current.selfId(), clock.getAsLong());
        if (line == null) return;
        Component rendered = render(s, line);
        String log = "[Discord] " + line.name() + ": " + line.text();
        host.main(() -> host.show(line.channel(), rendered, log));
    }

    /**
     * Checks one Discord message: bots, webhooks (our own posts included) and blocked users are ignored, only mapped
     * and bridged channels count, then word filter, link filter and anti-spam as for players without bypass rights.
     * Returns null for anything that is not shown.
     */
    Line accept(Settings s, JsonObject message, String selfId, long now) {
        if (!s.active(Settings.Feature.DISCORD_BOT)) return null;
        if (has(message, "webhook_id")) return null;
        JsonObject author = object(message, "author");
        if (author == null) return null;
        String authorId = string(author, "id");
        if (authorId == null || authorId.equals(selfId)) return null;
        if (flag(author, "bot") || flag(author, "system")) return null;
        if (s.discord.blockedUsers.contains(authorId)) return null;
        JsonElement typeValue = message.get("type");
        int type = typeValue != null && typeValue.isJsonPrimitive() ? typeValue.getAsInt() : 0;
        if (type != 0 && type != 19) return null; // only normal messages and replies
        DiscordConfig.Link link = s.discord.byDiscordId(string(message, "channel_id"));
        if (link == null) return null;
        Channel channel = s.channel(link.channel());
        if (bridged(s, channel) == null) return null;

        Map<String, String> users = new HashMap<>();
        JsonElement mentions = message.get("mentions");
        if (mentions != null && mentions.isJsonArray())
            for (JsonElement mention : mentions.getAsJsonArray()) {
                if (!mention.isJsonObject()) continue;
                JsonObject user = mention.getAsJsonObject();
                JsonObject member = object(user, "member");
                users.put(string(user, "id"), DiscordText.displayName(member == null ? null : string(member, "nick"), string(user, "global_name"), string(user, "username")));
            }
        JsonObject member = object(message, "member");
        String name = DiscordText.cut(DiscordText.displayName(member == null ? null : string(member, "nick"), string(author, "global_name"), string(author, "username")), 32);
        StringBuilder text = new StringBuilder(DiscordText.fromDiscord(string(message, "content"), users));

        boolean linksShown = !s.enabled(Settings.Feature.LINKS);
        JsonElement attachments = message.get("attachments");
        if (attachments != null && attachments.isJsonArray())
            for (JsonElement attachment : attachments.getAsJsonArray()) {
                if (!attachment.isJsonObject()) continue;
                String url = string(attachment.getAsJsonObject(), "url");
                text.append(text.isEmpty() ? "" : " ").append(host.text("discord.file"));
                if (url != null && (linksShown || Links.allowed(url, s.allowedDomains))) text.append(' ').append(url);
            }
        String body = text.toString().strip();
        if (body.isEmpty()) return null;
        JsonObject referenced = object(message, "referenced_message");
        if (type == 19 && referenced != null) {
            JsonObject to = object(referenced, "author");
            JsonObject toMember = object(referenced, "member");
            if (to != null) body = host.text("discord.reply", "name", DiscordText.displayName(toMember == null ? null : string(toMember, "nick"),
                    string(to, "global_name"), string(to, "username"))) + " " + body;
        }

        if (spam.settings() != s.spam) spam.configure(s.spam);
        SpamGuard.Result checked = spam.check("discord:" + authorId, body, now);
        if (checked.blocked()) return null;
        body = checked.text();
        if (!s.filter.empty()) {
            WordFilter.Result filtered = s.filter.apply(body);
            if (filtered.matched()) {
                if (s.filterAction == Settings.FilterAction.BLOCK) return null;
                body = filtered.text();
            }
        }
        if (s.enabled(Settings.Feature.LINKS)) {
            String censored = Links.censor(body, s.allowedDomains, s.linkReplacement);
            if (censored != null) {
                if (s.linkAction == Settings.FilterAction.BLOCK) return null;
                body = censored;
            }
        }
        body = DiscordText.cut(body, DiscordText.MAX_INBOUND);
        return new Line(channel, authorId, name, body);
    }

    /** The chat line from {@code discord.bot.format}; name and text are inserted as plain text (no tags from Discord). */
    static Component render(Settings s, Line line) {
        Channel channel = line.channel();
        return Formats.render(s.discord.inboundFormat, null,
                Placeholder.component("channel", Formats.render(channel.tag(), null)),
                Placeholder.unparsed("channel_name", channel.displayName()),
                Placeholder.unparsed("name", line.name()),
                Placeholder.unparsed("message", line.text()));
    }

    private static boolean has(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull(); }

    private static boolean flag(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    // ------------------------------------------------------------------ timing, status, shutdown

    /** About twice a second off the server thread: queue, heartbeat, reconnect, bot status. */
    void pulse() {
        rest.pulse();
        DiscordGateway current = gateway;
        if (current == null) return;
        current.pulse();
        Settings s = settings;
        if (s == null || !s.active(Settings.Feature.DISCORD_PLAYER_COUNT) || current.state() != DiscordGateway.State.READY) return;
        int count = host.onlinePlayers();
        long now = clock.getAsLong();
        if (count == lastCount || now - lastPresence < 30_000L) return;
        lastCount = count;
        lastPresence = now;
        current.presence(host.text("discord.presence", "online", count, "max", host.maxPlayers()));
    }

    /** onEnable after the first configure: the "server started" line. */
    void started() {
        if (started) return;
        started = true;
        event(Settings.Feature.DISCORD_SERVER_START, "discord.events.server-start");
    }

    /** onDisable: the "server stopped" line (sent directly, at most three seconds), then everything closes. */
    void close() {
        Settings s = settings;
        if (s != null && s.active(Settings.Feature.DISCORD_SERVER_STOP)) {
            DiscordRest.Job job = eventJob(s, DiscordText.cut(host.text("discord.events.server-stop"), DiscordText.MAX_CONTENT));
            if (job != null) {
                Thread last = new Thread(() -> rest.sendNow(job), "VystormChat-discord-stop");
                last.setDaemon(true);
                last.start();
                try { last.join(3000L); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
        rest.close();
        stopGateway();
    }

    private void stopGateway() {
        DiscordGateway current = gateway;
        gateway = null;
        gatewayToken = "";
        if (current != null) current.stop();
    }

    /** Status values for {@code /vchat discord status}; no token, no URL. */
    record Status(String webhooks, String bot, TokenSource token, int queued, long sent, long dropped, String error) {}

    Status status() {
        Settings s = settings;
        List<String> webhooks = new ArrayList<>();
        if (s != null) for (DiscordConfig.Link link : s.discord.links.values())
            if (problem(s, link, s.channel(link.channel())) == null) webhooks.add(link.channel() + (link.hasWebhook() ? "" : " (bot)") + (link.hasDiscordId() ? " <-> " + link.discordId() : ""));
        DiscordGateway current = gateway;
        String bot = current == null ? (s != null && s.active(Settings.Feature.DISCORD_BOT) && token.isEmpty() ? "no token" : "off") : current.state().name().toLowerCase(Locale.ROOT);
        String error = current != null && !current.lastError().isEmpty() ? current.lastError() : !rest.lastError().isEmpty() ? rest.lastError() : lastWarning;
        return new Status(String.join(", ", webhooks), bot, tokenSource, rest.queued(), rest.sent(), rest.dropped(), scrub(error));
    }

    /** Last safety net: a token or webhook URL never leaves through a status text. */
    String scrub(String text) {
        if (text == null || text.isEmpty()) return "";
        String out = text.replaceAll("https?://\\S+", "<url>");
        if (!token.isEmpty()) out = out.replace(token, "***");
        return out;
    }

    DiscordRest rest() { return rest; }
    DiscordGateway gateway() { return gateway; }
    TokenSource tokenSource() { return tokenSource; }
}
