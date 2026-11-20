package at.esoren.vystormchat;

import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The message pipeline shared by normal chat, channel commands and Core's {@code /vtrade}: mute, write rights,
 * cooldown and slow mode, anti-spam, word and link filter, cost, showcase, mentions and the recipient list. Runs on
 * any thread; the few steps that need the server thread (payment, copying an item) hop over with a time limit.
 */
final class ChatService {
    enum Source { CHAT, COMMAND, CORE_TRADE }

    sealed interface Outcome permits Denied, Forward, Deliver, Ignored {}

    /** Not sent; {@code key} is a lang key shown to the sender. */
    record Denied(String key, Object... placeholders) implements Outcome {}

    /** Accepted for a channel that hands its messages to another plugin's command. */
    record Forward(String command, String text) implements Outcome {}

    record Deliver(Delivery delivery) implements Outcome {}

    record Ignored() implements Outcome {}

    private record Paid(boolean ok, Showcase.Snapshot snapshot) {}

    static final String SLOW_ALL = "*";

    private final ChatPlugin plugin;
    private final SpamGuard spam = new SpamGuard();
    private final KeywordAlerts keywordAlerts = new KeywordAlerts();
    private final Map<UUID, Map<String, Long>> lastSent = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMention = new ConcurrentHashMap<>();
    private final Map<String, Integer> slowMode = new ConcurrentHashMap<>();
    private volatile Settings settings;

    ChatService(ChatPlugin plugin) { this.plugin = plugin; }

    void configure(Settings settings) {
        this.settings = settings;
        spam.configure(settings.spam);
    }

    Settings settings() { return settings; }

    Lang lang() { return plugin.lang(); }

    ChatPlugin plugin() { return plugin; }

    void forget(UUID player) {
        spam.forget(player);
        keywordAlerts.forget(player);
        lastSent.remove(player);
        lastMention.remove(player);
    }

    // ------------------------------------------------------------------ slow mode

    /** Seconds between messages for everyone without bypass; {@code channel} null = all channels; 0 = off. */
    void slowMode(String channel, int seconds) {
        String key = channel == null ? SLOW_ALL : channel;
        if (seconds <= 0) slowMode.remove(key); else slowMode.put(key, seconds);
    }

    int slowMode(String channel) {
        Settings s = settings;
        if (s != null && !s.enabled(Settings.Feature.SLOW_MODE)) return 0; // slow-mode.enabled off: the set times wait
        return Math.max(slowMode.getOrDefault(SLOW_ALL, 0), channel == null ? 0 : slowMode.getOrDefault(channel, 0));
    }

    Map<String, Integer> slowModes() { return Map.copyOf(slowMode); }

    // ------------------------------------------------------------------ pipeline

    /**
     * Checks and prepares one message.
     *
     * @param candidates everyone who could receive it (the chat event's viewers or all online players)
     */
    Outcome prepare(Player sender, Channel channel, String input, Source source, Collection<? extends Player> candidates) {
        Settings s = settings;
        if (!s.enabled(Settings.Feature.CHAT)) return new Denied("feature-off");
        long now = System.currentTimeMillis();
        UUID id = sender.getUniqueId();
        MuteStore.Mute mute = plugin.mutes().active(id, now);
        if (mute != null) return muted(mute, now);

        PlayerState state = plugin.states().get(id);
        if (state == null) state = plugin.onMain(() -> plugin.states().join(sender), null);
        if (state == null) return new Denied("busy");
        String problem = Visibility.writeProblem(channel, state, sender::hasPermission);
        if (problem != null) return new Denied(problem, "channel", channel.displayName());
        // family-links.enabled off: party, city, faction and region chats and forwarded channels are switched off
        if (!s.enabled(Settings.Feature.FAMILY_LINKS) && familyLink(channel))
            return new Denied("feature-needs", "switch", Settings.Feature.FAMILY_LINKS.path, "name", plugin.lang().plain(sender, Settings.Feature.FAMILY_LINKS.label()));

        String text = clean(input);
        if (text.isEmpty()) return new Ignored();
        if (text.length() > s.maxLength) return new Denied("too-long", "max", s.maxLength);

        if (!sender.hasPermission("vystormchat.bypass.cooldown")) {
            int wait = Math.max(channel.cooldownSeconds(), slowMode(channel.id()));
            Long last = lastSent.getOrDefault(id, Map.of()).get(channel.id());
            if (wait > 0 && last != null && now - last < wait * 1000L)
                return new Denied(slowMode(channel.id()) >= wait && slowMode(channel.id()) > 0 ? "slow-mode-wait" : "cooldown",
                        "time", Durations.format(wait * 1000L - (now - last)));
        }

        Set<PlayerText.Style> styles = EnumSet.noneOf(PlayerText.Style.class);
        if (s.enabled(Settings.Feature.PLAYER_FORMATTING)) styles.addAll(PlayerText.Style.of(sender::hasPermission));
        Object screened = screen(sender, text, styles, channel.id(), id.toString());
        if (screened instanceof Denied denied) return denied;
        text = (String) screened;
        if (s.emojisEnabled && sender.hasPermission("vystormchat.emoji")) text = Emojis.apply(text, s.emojis);
        boolean linksAllowed = sender.hasPermission("vystormchat.links");

        boolean forward = !channel.forwardCommand().isEmpty();
        String lower = text.toLowerCase(Locale.ROOT);
        boolean item = !forward && s.showcaseEnabled && containsAny(lower, s.itemTokens) && sender.hasPermission("vystormchat.showcase.item");
        boolean inventory = !forward && s.showcaseEnabled && containsAny(lower, s.inventoryTokens) && sender.hasPermission("vystormchat.showcase.inventory");
        boolean position = !forward && s.showcaseEnabled && containsAny(lower, s.positionTokens) && sender.hasPermission("vystormchat.showcase.position");
        Money money = plugin.hooks().money();
        double cost = channel.cost() > 0 && money != null && s.enabled(Settings.Feature.PAID_CHANNELS) && !sender.hasPermission("vystormchat.bypass.cost") ? channel.cost() : 0;
        Showcase.Snapshot snapshot = Showcase.Snapshot.NONE;
        if (item || inventory || position || cost > 0) {
            Paid paid = plugin.onMain(() -> {
                if (cost > 0 && !(money.has(sender, cost) && money.withdraw(sender, cost))) return new Paid(false, null);
                return new Paid(true, item || inventory || position ? plugin.showcase().take(sender, item, inventory, position) : Showcase.Snapshot.NONE);
            }, null);
            if (paid == null) return new Denied("busy");
            if (!paid.ok()) return new Denied("cost-too-poor", "price", money.format(cost), "channel", channel.displayName());
            snapshot = paid.snapshot();
        }

        Profile own = plugin.profiles().cached(id);
        if (own != null && s.enabled(Settings.Feature.CHANNEL_CHOICE) && !own.listening(channel)) own.listen(channel, true);
        lastSent.computeIfAbsent(id, key -> new ConcurrentHashMap<>()).put(channel.id(), now);
        if (forward) return new Forward(channel.forwardCommand(), text);

        String plain = PlayerText.plain(text, styles);
        List<Mentions.Hit> hits = s.mentionsEnabled && sender.hasPermission("vystormchat.mention")
                ? Mentions.find(plain, plugin.states().names(), s.mentionsPlainNames, sender.hasPermission("vystormchat.mention.everyone"))
                : List.of();

        Map<UUID, Visibility.Reach> reach = new HashMap<>();
        boolean exempt = sender.hasPermission("vystormchat.ignore.exempt");
        for (Player viewer : candidates) {
            PlayerState viewerState = plugin.states().get(viewer.getUniqueId());
            if (viewerState == null) continue;
            Profile profile = plugin.profiles().cached(viewer.getUniqueId());
            boolean listening = listens(s, profile, channel);
            boolean ignoring = !exempt && s.ignoreEnabled && profile != null && profile.ignores(id);
            boolean spying = s.enabled(Settings.Feature.SOCIAL_SPY) && profile != null && profile.spy() && viewer.hasPermission("vystormchat.spy");
            Visibility.Reach result = Visibility.reach(channel, state, viewerState, viewer::hasPermission, listening, ignoring, spying);
            if (result != Visibility.Reach.NONE) reach.put(viewer.getUniqueId(), result);
        }
        reach.put(id, Visibility.Reach.NORMAL);
        return new Deliver(new Delivery(this, sender, state, channel, text, plain, Set.copyOf(styles), linksAllowed, snapshot, hits, reach));
    }

    /**
     * Spam, word and link checks for text outside channels (private messages). Returns the text to send (maybe
     * censored; {@code styles} cleared when formatting hid a filtered word) or a {@link Denied}.
     */
    Object screen(Player sender, String text, Set<PlayerText.Style> styles, String context, String spamKey) {
        Settings s = settings;
        if (!sender.hasPermission("vystormchat.bypass.spam")) {
            SpamGuard.Result result = spam.check(spamKey, text, System.currentTimeMillis());
            if (result.blocked()) {
                plugin.chatLog().log("blocked:spam", sender.getName() + " (" + context + "): " + text);
                return new Denied("spam-" + result.verdict().name().toLowerCase(Locale.ROOT));
            }
            text = result.text();
        }
        if (!sender.hasPermission("vystormchat.bypass.filter") && !s.filter.empty()) {
            String plainNow = PlayerText.plain(text, styles);
            WordFilter.Result raw = s.filter.apply(text);
            WordFilter.Result shown = plainNow.equals(text) ? raw : s.filter.apply(plainNow);
            if (raw.matched() || shown.matched()) {
                plugin.chatLog().log("filter", sender.getName() + " (" + context + "): " + text);
                if (s.filterAction == Settings.FilterAction.BLOCK) {
                    if (s.notifyStaffOnBlock) notifyStaff("staff-filter-blocked", "player", sender.getName(), "message", plainNow);
                    return new Denied("filter-blocked");
                }
                if (shown.matched() && !plainNow.equals(text)) { text = shown.text(); styles.clear(); }
                else text = raw.text();
            }
        }
        if (s.enabled(Settings.Feature.LINKS) && !sender.hasPermission("vystormchat.links")) {
            String censored = Links.censor(text, s.allowedDomains, s.linkReplacement);
            if (censored != null) {
                if (s.linkAction == Settings.FilterAction.BLOCK) return new Denied("link-blocked");
                text = censored;
            }
        }
        return text;
    }

    /** A channel that needs another Vystorm plugin: party, city, faction, region chat and channels forwarded to a command. */
    static boolean familyLink(Channel channel) {
        return channel.scope().group() || channel.scope() == Channel.Scope.REGION || !channel.forwardCommand().isEmpty();
    }

    /**
     * Whether a viewer receives a channel: their own choice, or with channel-choice.enabled off the channel's default
     * (auto-join channels for everyone, opt-in channels for nobody).
     */
    static boolean listens(Settings s, Profile profile, Channel channel) {
        if (!s.enabled(Settings.Feature.CHANNEL_CHOICE)) return !channel.leavable() || channel.autoJoin();
        return profile == null || profile.listening(channel);
    }

    static boolean containsAny(String lower, Set<String> tokens) {
        for (String token : tokens) if (lower.contains(token)) return true;
        return false;
    }

    /** Control characters removed, spaces trimmed. */
    static String clean(String input) {
        if (input == null) return "";
        StringBuilder out = new StringBuilder(input.length());
        input.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(out::appendCodePoint);
        return out.toString().strip();
    }

    Denied muted(MuteStore.Mute mute, long now) {
        String reason = mute.reason().isBlank() ? "-" : mute.reason();
        return mute.permanent() ? new Denied("muted-permanent", "reason", reason)
                : new Denied("muted", "time", Durations.format(mute.remaining(now)), "reason", reason);
    }

    /** Sends a prepared message as system messages (commands, /vtrade); server thread. */
    void deliver(Delivery delivery) {
        for (UUID id : delivery.reach.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) plugin.core().compat().platform().sendMessage(player, delivery.render(player));
        }
        if (settings.consoleLog) plugin.core().compat().platform().sendMessage(Bukkit.getConsoleSender(), delivery.render(Bukkit.getConsoleSender()));
        after(delivery);
    }

    /** Log, mention sounds and the "nobody heard you" hint. Any thread. */
    void after(Delivery delivery) {
        Settings s = settings;
        plugin.chatLog().log(delivery.channel.id(), delivery.sender.getName() + ": " + delivery.plain);
        keywordAlerts.after(plugin, delivery);
        if (plugin.bubbles() != null) plugin.bubbles().after(delivery);
        UUID sender = delivery.sender.getUniqueId();
        if (!delivery.hits.isEmpty()) {
            long now = System.currentTimeMillis();
            Long last = lastMention.get(sender);
            boolean sound = last == null || now - last >= s.mentionCooldownMillis;
            if (sound) lastMention.put(sender, now);
            List<UUID> targets = new ArrayList<>();
            for (UUID id : delivery.reach.keySet()) if (delivery.mentions(id)) targets.add(id);
            if (!targets.isEmpty()) plugin.tasks().now(() -> {
                for (UUID id : targets) {
                    Player target = Bukkit.getPlayer(id);
                    if (target == null) continue;
                    Profile profile = plugin.profiles().cached(id);
                    if (sound && (profile == null || profile.mentionSound())) play(plugin.core().compat().platform(), target, s.mentionSound, s.mentionVolume, s.mentionPitch);
                    if (s.mentionActionBar) lang().actionBar(target, "mentioned-by", "player", delivery.sender.getName(), "channel", delivery.channel.displayName());
                }
            });
        }
        if (s.notifyNobodyHeard && delivery.channel.scope() != Channel.Scope.GLOBAL && delivery.listeners().isEmpty()) {
            plugin.tasks().later(1L, () -> { if (delivery.sender.isOnline()) lang().send(delivery.sender, "nobody-heard", "channel", delivery.channel.displayName()); });
        }
    }

    static void play(Vystorm.vystorm_core.compat.PlatformCompatInterface platform, Player player, String sound, float volume, float pitch) {
        if (sound == null || sound.isBlank()) return;
        try {
            platform.playSound(player, Sound.sound(Key.key(sound.toLowerCase(Locale.ROOT)), Sound.Source.PLAYER, volume, pitch));
        } catch (RuntimeException invalid) {
            // wrong sound name in the config: stay quiet
        }
    }

    /** Wraps a line for the Vystorm client chat tabs when the channel asks for it. */
    Component clientTab(Channel channel, Component line) {
        if (!settings.clientTabs || channel.clientTab().equals("none")) return line;
        try {
            return plugin.core().message().channels().tag(
                    Vystorm.vystorm_core.chat.ChatChannels.Channel.valueOf(channel.clientTab().toUpperCase(Locale.ROOT)), line);
        } catch (RuntimeException unsupported) {
            return line;
        }
    }

    /** Clickable coordinates through Core's chat actions (teleport only with Core's teleport permission). */
    Component position(Location location) {
        try {
            return plugin.core().message().chat().location(location);
        } catch (RuntimeException unavailable) {
            return Component.text("[" + location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ() + "]", NamedTextColor.AQUA);
        }
    }

    /** The hover card on a player's name, in the viewer's language. */
    Component hoverCard(String language, PlayerState state) {
        Lang lang = lang();
        List<Component> lines = new ArrayList<>();
        for (String entry : settings.hoverCard) {
            switch (entry) {
                case "name" -> lines.add(lang.component(language, "hover-card.name", "name", state.displayName(), "player", state.name()));
                case "rank" -> { if (!state.group().isEmpty()) lines.add(lang.component(language, "hover-card.rank", "rank", state.group())); }
                case "world" -> { if (state.worldName() != null) lines.add(lang.component(language, "hover-card.world", "world", state.worldName())); }
                case "playtime" -> lines.add(lang.component(language, "hover-card.playtime", "time", Durations.playtime(state.playTicks())));
                case "party" -> { if (state.partyName() != null) lines.add(lang.component(language, "hover-card.party", "party", state.partyName())); }
                case "city" -> { if (state.cityName() != null) lines.add(lang.component(language, "hover-card.city", "city", state.cityName())); }
                case "faction" -> { if (state.factionName() != null) lines.add(lang.component(language, "hover-card.faction", "faction", state.factionName())); }
                case "click" -> lines.add(lang.component(language, "hover-card.click", "player", state.name()));
                default -> { /* unknown entry: skipped */ }
            }
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** Tells online staff (vystormchat.notify) and the console. */
    void notifyStaff(String key, Object... placeholders) {
        plugin.tasks().now(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) if (player.hasPermission("vystormchat.notify")) lang().send(player, key, placeholders);
            lang().send(Bukkit.getConsoleSender(), key, placeholders);
        });
    }
}
