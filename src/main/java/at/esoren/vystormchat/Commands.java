package at.esoren.vystormchat;

import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandContext;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * All commands as Vystorm Core command trees: names, root aliases, usage and help come from
 * {@code commands.<command>.*} in the language files, so {@code /kanal liste} or {@code /频道 列表} work next to the
 * English names. Most commands also exist as buttons in the chat menu.
 */
final class Commands {
    private final ChatPlugin plugin;

    Commands(ChatPlugin plugin) { this.plugin = plugin; }

    private Lang lang() { return plugin.lang(); }

    /** The command trees; {@code plugin} may be {@code null} in tests that only inspect names and help. */
    static List<CommandSpec> specs(ChatPlugin plugin) {
        return specs(plugin, alias -> plugin != null && plugin.core().compat().platform().getCommandMap().getCommand(alias) != null);
    }

    static List<CommandSpec> specs(ChatPlugin plugin, java.util.function.Predicate<String> taken) {
        Commands c = new Commands(plugin);
        List<CommandSpec> specs = new ArrayList<>(base(plugin, taken, c));
        // /discord (invite link) only while no other plugin (DiscordSRV ...) owns the name; it is a soft dependency, so it is loaded first
        if (!taken.test("discord"))
            specs.add(gate(CommandSpec.command("discord"), plugin, Settings.Feature.DISCORD_INVITE).permission("vystormchat.use")
                    .executes(c::discordInvite).build());
        else if (plugin != null) plugin.getLogger().info("/discord belongs to another plugin; Vystorm Chat's invite command is left out.");
        return specs;
    }

    private static List<CommandSpec> base(ChatPlugin plugin, java.util.function.Predicate<String> taken, Commands c) {
        return List.of(
                gate(CommandSpec.command("vchat"), plugin, Settings.Feature.CHAT).permission("vystormchat.use").aliases("chatmenu")
                        .executes(ctx -> {
                            if (ctx.sender() instanceof Player p && !plugin.off(Settings.Feature.CHAT_MENU)) plugin.menu().open(p);
                            else if (plugin.refuse(ctx.sender(), Settings.Feature.CHAT)) return;
                            else ctx.registration().help(ctx.sender(), ctx.label(), 1);
                        })
                        .sub("menu", s -> gate(s, plugin, Settings.Feature.CHAT_MENU).playerOnly().executes(ctx -> plugin.menu().open(ctx.player())))
                        .sub("report", s -> gate(s, plugin, Settings.Feature.REPORTS).playerOnly().arg(Arg.playerName("player")).arg(Arg.greedy("reason"))
                                .executes(ctx -> plugin.reports().submit(ctx.player(), ctx.get("player"), ctx.get("reason"))))
                        .sub("reports", s -> gate(s, plugin, Settings.Feature.REPORTS).playerOnly().permission("vystormchat.moderate").executes(ctx -> plugin.reports().list(ctx.player(), 0)))
                        .sub("mutes", s -> gate(s, plugin, Settings.Feature.MUTES).permission("vystormchat.mute").executes(ctx -> {
                            if (ctx.sender() instanceof Player p) plugin.menu().mutes(p); else c.listMutes(ctx.sender());
                        }))
                        .sub("announcements", s -> gate(s, plugin, Settings.Feature.ANNOUNCEMENTS).permission("vystormchat.announce").playerOnly().executes(ctx -> plugin.announcements().open(ctx.player())))
                        .sub("moderation", s -> gate(s, plugin, Settings.Feature.CHAT_MENU).aliases("mod").permission("vystormchat.moderate").playerOnly().executes(ctx -> plugin.menu().moderation(ctx.player())))
                        .sub("emojis", s -> gate(s, plugin, Settings.Feature.EMOJIS).executes(ctx -> {
                            if (plugin.refuse(ctx.sender(), Settings.Feature.EMOJIS)) return;
                            Settings settings = plugin.service().settings();
                            if (!settings.emojisEnabled || settings.emojis.isEmpty() || !ctx.sender().hasPermission("vystormchat.emoji")) { ctx.reply("emojis-off"); return; }
                            StringBuilder list = new StringBuilder();
                            settings.emojis.forEach((code, symbol) -> list.append(list.isEmpty() ? "" : "  ").append(":").append(code).append(": ").append(symbol));
                            ctx.reply("emojis-list", "list", list.toString());
                        }))
                        .sub("keywords", s -> gate(s, plugin, Settings.Feature.KEYWORDS).permission("vystormchat.keywords").playerOnly()
                                .executes(c::listKeywords)
                                .sub("add", a -> a.arg(Arg.greedy("phrase")).executes(c::addKeyword))
                                .sub("test", a -> a.arg(Arg.greedy("message")).executes(c::testKeyword))
                                .sub("remove", a -> a.arg(Arg.greedy("phrase").suggests((ctx, prefix) -> plugin.profiles().get(ctx.player().getUniqueId()).keywords()))
                                        .executes(ctx -> ctx.reply(plugin.profiles().get(ctx.player().getUniqueId()).removeKeyword(ctx.get("phrase")) ? "keyword-removed" : "keyword-missing")))
                                .sub("pause", a -> a.executes(ctx -> c.keywordState(ctx, false)))
                                .sub("resume", a -> a.executes(ctx -> c.keywordState(ctx, true))))
                        .sub("replylock", s -> gate(s, plugin, Settings.Feature.PRIVATE_MESSAGES).permission("vystormchat.msg").playerOnly()
                                .executes(ctx -> plugin.messages().toggleReplyLock(ctx.player())))
                        // not gated: the status must also answer (and name the switch) while the bridge is off
                        .sub("discord", s -> s.permission("vystormchat.admin").executes(c::discordStatus)
                                .sub("status", a -> a.executes(c::discordStatus))
                                .sub("test", a -> a.arg(Arg.word("channel").suggests((ctx, prefix) -> c.discordChannels())).executes(c::discordTest)))
                        .sub("reload", s -> s.permission("vystormchat.admin").executes(ctx -> {
                            plugin.reload();
                            if (plugin.configProblem() != null) ctx.reply("config-broken", "file", "config.yml", "problem", plugin.configProblem());
                            else ctx.reply("reloaded", "channels", plugin.service().settings().channels.size(), "hooks", plugin.hooks().summary());
                        }))
                        .build(),
                gate(CommandSpec.command("channel"), plugin, Settings.Feature.CHAT).permission("vystormchat.use").aliases("ch", "channels").playerOnly()
                        .arg(Arg.word("channel").optional().suggests((ctx, prefix) -> c.channels(ctx.sender())))
                        .arg(Arg.greedy("message").optional())
                        .executes(c::channel)
                        .sub("list", s -> s.executes(ctx -> c.listChannels(ctx.player())))
                        .sub("join", s -> gate(s, plugin, Settings.Feature.CHANNEL_CHOICE).aliases("show").arg(Arg.word("channel").suggests((ctx, prefix) -> c.channels(ctx.sender())))
                                .executes(ctx -> c.listen(ctx, true)))
                        .sub("leave", s -> gate(s, plugin, Settings.Feature.CHANNEL_CHOICE).aliases("hide").arg(Arg.word("channel").suggests((ctx, prefix) -> c.channels(ctx.sender())))
                                .executes(ctx -> c.listen(ctx, false)))
                        .build(),
                gate(CommandSpec.command("msg"), plugin, Settings.Feature.PRIVATE_MESSAGES).permission("vystormchat.msg").aliases(availableAliases(taken, "tell", "w", "whisper", "m", "pm", "dm"))
                        .arg(Arg.playerName("player").suggests((ctx, prefix) -> c.players(ctx.sender())))
                        .arg(Arg.greedy("message"))
                        .executes(ctx -> plugin.messages().send(ctx.sender(), ctx.<String>get("player"), ctx.<String>get("message")))
                        .build(),
                gate(CommandSpec.command("reply"), plugin, Settings.Feature.PRIVATE_MESSAGES).permission("vystormchat.msg").aliases("r").playerOnly()
                        .arg(Arg.greedy("message"))
                        .executes(ctx -> plugin.messages().reply(ctx.player(), ctx.get("message")))
                        .build(),
                gate(CommandSpec.command("ignore"), plugin, Settings.Feature.IGNORE).permission("vystormchat.ignore").aliases("unignore").playerOnly()
                        .arg(Arg.playerName("player").optional().suggests((ctx, prefix) -> c.players(ctx.sender())))
                        .executes(ctx -> {
                            String name = ctx.get("player");
                            if (name == null) plugin.menu().ignored(ctx.player()); else plugin.ignoreCommand(ctx.player(), name);
                        })
                        .sub("list", s -> s.executes(ctx -> c.listIgnored(ctx.player())))
                        .build(),
                gate(CommandSpec.command("socialspy"), plugin, Settings.Feature.SOCIAL_SPY).permission("vystormchat.spy").aliases("spy").playerOnly()
                        .executes(ctx -> plugin.toggleSpy(ctx.player()))
                        .build(),
                gate(CommandSpec.command("mute"), plugin, Settings.Feature.MUTES).permission("vystormchat.mute")
                        .arg(Arg.playerName("player").optional().suggests((ctx, prefix) -> c.players(ctx.sender())))
                        .arg(Arg.greedy("time-reason").optional().suggests(List.of("10m", "30m", "1h", "1d", "7d", "perm")))
                        .executes(c::mute)
                        .build(),
                gate(CommandSpec.command("unmute"), plugin, Settings.Feature.MUTES).permission("vystormchat.mute")
                        .arg(Arg.playerName("player").suggests((ctx, prefix) -> c.players(ctx.sender())))
                        .executes(ctx -> {
                            if (plugin.refuse(ctx.sender(), Settings.Feature.MUTES)) return;
                            OfflinePlayer target = plugin.offline(ctx.get("player"));
                            if (target == null) throw ctx.fail("player-unknown", "player", ctx.get("player"));
                            plugin.unmute(ctx.sender(), target);
                        })
                        .build(),
                gate(CommandSpec.command("clearchat"), plugin, Settings.Feature.CLEAR_CHAT).permission("vystormchat.clearchat").aliases("cc", "chatclear")
                        .executes(ctx -> plugin.clearChat(ctx.sender()))
                        .build(),
                gate(CommandSpec.command("slowmode"), plugin, Settings.Feature.SLOW_MODE).permission("vystormchat.slowmode").aliases("slow")
                        .arg(Arg.word("seconds").optional().suggests((ctx, prefix) -> List.of(ctx.valueName("off"), "3", "5", "10", "30", "60")))
                        .arg(Arg.word("channel").optional().suggests((ctx, prefix) -> new ArrayList<>(plugin.service().settings().channels.keySet())))
                        .executes(c::slowMode)
                        .build());
    }

    /**
     * Keeps a command or subcommand out of help and tab completion while its switch (or chat.enabled) is off, asked at
     * every completion so a switch applies at once; it still answers the off message.
     */
    static CommandSpec.Builder gate(CommandSpec.Builder node, ChatPlugin plugin, Settings.Feature feature) {
        if (plugin == null) return node;
        return node.visibleWhen(() -> plugin.service() == null || plugin.service().settings() == null || !plugin.off(feature));
    }

    /** Core reports localized alias collisions, but fixed aliases go straight to Bukkit first. */
    static String[] availableAliases(java.util.function.Predicate<String> taken, String... aliases) {
        return Arrays.stream(aliases).filter(alias -> !taken.test(alias)).toArray(String[]::new);
    }

    private void listKeywords(CommandContext ctx) {
        if (plugin.refuse(ctx.sender(), Settings.Feature.KEYWORDS)) return;
        Profile profile = plugin.profiles().get(ctx.player().getUniqueId());
        ctx.reply("keywords-list", "list", String.join(", ", profile.keywords()), "count", profile.keywords().size(),
                "limit", plugin.service().settings().keywordLimit);
        ctx.reply(!plugin.service().settings().keywordsEnabled ? "keywords-disabled" : profile.keywordAlerts() ? "keywords-active" : "keywords-paused");
    }

    private void addKeyword(CommandContext ctx) {
        Settings settings = plugin.service().settings();
        if (!settings.keywordsEnabled) { ctx.reply("feature-off"); return; }
        boolean added = plugin.profiles().get(ctx.player().getUniqueId()).addKeyword(ctx.get("phrase"), settings.keywordLimit);
        ctx.reply(added ? "keyword-added" : "keyword-invalid", "limit", settings.keywordLimit);
    }

    private void keywordState(CommandContext ctx, boolean enabled) {
        if (plugin.refuse(ctx.sender(), Settings.Feature.KEYWORDS)) return;
        plugin.profiles().get(ctx.player().getUniqueId()).keywordAlerts(enabled);
        ctx.reply(enabled ? "keywords-active" : "keywords-paused");
    }

    private void testKeyword(CommandContext ctx) {
        Settings settings = plugin.service().settings();
        if (!settings.keywordsEnabled) { ctx.reply("feature-off"); return; }
        Profile profile = plugin.profiles().get(ctx.player().getUniqueId());
        String keyword = KeywordAlerts.match(ctx.get("message"), profile.keywords(), settings.keywordLimit);
        if (keyword == null) ctx.reply("keyword-test-miss");
        else ctx.reply("keyword-test-hit", "keyword", keyword);
    }

    // ------------------------------------------------------------------ Discord

    /** Tells the sender which switch is missing; true when {@code feature} cannot run. */
    private boolean needs(CommandContext ctx, Settings.Feature feature) {
        Settings settings = plugin.service().settings();
        Settings.Feature missing = !settings.enabled(feature) ? feature : settings.missing(feature);
        if (missing == null) return false;
        ctx.reply("feature-needs", "switch", missing.path, "name", lang().plain(ctx.sender(), missing.label()));
        return true;
    }

    /** {@code /vchat discord [status]}: switches, bridged channels, token source, queue and the last error; no secrets. */
    private void discordStatus(CommandContext ctx) {
        DiscordBridge bridge = plugin.discord();
        Settings settings = plugin.service().settings();
        CommandSender sender = ctx.sender();
        ctx.reply("discord.status-header");
        String on = lang().plain(sender, "discord.word-on"), off = lang().plain(sender, "discord.word-off");
        if (bridge == null) { ctx.reply("discord.status-switches", "webhooks", off, "bot", off); return; }
        DiscordBridge.Status status = bridge.status();
        ctx.reply("discord.status-switches", "webhooks", settings.active(Settings.Feature.DISCORD) ? on : off, "bot", status.bot());
        ctx.reply("discord.status-channels", "channels", status.webhooks().isEmpty() ? "-" : status.webhooks());
        ctx.reply("discord.status-token", "source", lang().plain(sender, "discord.token-" + status.token().name().toLowerCase(java.util.Locale.ROOT)));
        ctx.reply("discord.status-queue", "queued", status.queued(), "sent", status.sent(), "dropped", status.dropped());
        ctx.reply("discord.status-error", "error", status.error().isEmpty() ? "-" : status.error());
        if (!settings.active(Settings.Feature.DISCORD)) needs(ctx, Settings.Feature.DISCORD);
    }

    private void discordTest(CommandContext ctx) {
        if (needs(ctx, Settings.Feature.DISCORD)) return;
        DiscordBridge bridge = plugin.discord();
        String problem = bridge == null ? "discord.test-failed" : bridge.test(ctx.get("channel"), ctx.sender().getName());
        if (problem == null) ctx.reply("discord.test-queued", "channel", ctx.<String>get("channel"));
        else ctx.reply(problem, "channel", ctx.<String>get("channel"));
    }

    private List<String> discordChannels() {
        return new ArrayList<>(plugin.service().settings().discord.links.keySet());
    }

    /** {@code /discord}: the invite link from {@code discord.invite.link}, clickable. */
    private void discordInvite(CommandContext ctx) {
        if (needs(ctx, Settings.Feature.DISCORD_INVITE)) return;
        String link = plugin.service().settings().discord.invite;
        if (link.isEmpty() || !link.startsWith("https://")) { ctx.reply("discord.invite-missing"); return; }
        Component line = lang().component(ctx.sender(), "discord.invite", "link", link)
                .clickEvent(ClickEvent.openUrl(link))
                .hoverEvent(HoverEvent.showText(lang().component(ctx.sender(), "link-hover", "url", link)));
        plugin.core().compat().platform().sendMessage(ctx.sender(), line);
    }

    // ------------------------------------------------------------------ channel

    private void channel(CommandContext ctx) {
        Player player = ctx.player();
        if (plugin.refuse(player, Settings.Feature.CHAT)) return;
        String name = ctx.get("channel");
        if (name == null) { plugin.menu().open(player); return; }
        Settings settings = plugin.service().settings();
        Channel channel = settings.channel(name);
        if (channel == null) for (Channel candidate : settings.channels.values()) if (candidate.aliases().contains(name.toLowerCase(java.util.Locale.ROOT))) channel = candidate;
        if (channel == null) throw ctx.fail("channel-unknown", "channel", name);
        String message = ctx.get("message");
        if (message == null) plugin.focus(player, channel);
        else plugin.sendTo(player, channel, message, ChatService.Source.COMMAND);
    }

    private void listen(CommandContext ctx, boolean on) {
        Channel channel = plugin.service().settings().channel(ctx.get("channel"));
        if (channel == null) throw ctx.fail("channel-unknown", "channel", ctx.get("channel"));
        plugin.listen(ctx.player(), channel, on);
    }

    private List<String> channels(CommandSender sender) {
        List<String> out = new ArrayList<>();
        for (Channel channel : plugin.service().settings().channels.values())
            if (channel.readable(sender::hasPermission) || channel.writable(sender::hasPermission)) out.add(channel.id());
        return out;
    }

    /** Clickable channel list: click a channel to write there. */
    private void listChannels(Player player) {
        if (plugin.refuse(player, Settings.Feature.CHAT)) return;
        lang().send(player, "channel-list-header");
        Channel focus = plugin.focus(player);
        Profile profile = plugin.profiles().get(player.getUniqueId());
        for (Channel channel : plugin.service().settings().channels.values()) {
            if (!channel.readable(player::hasPermission) && !channel.writable(player::hasPermission)) continue;
            String state = channel.id().equals(focus.id()) ? "channel-list-focus" : profile.listening(channel) ? "channel-list-listening" : "channel-list-hidden";
            Component line = lang().component(player, "channel-list-entry", "channel", channel.displayName(), "state", lang().component(player, state),
                    "prefix", channel.quickPrefix().isEmpty() ? "-" : channel.quickPrefix());
            if (channel.writable(player::hasPermission))
                line = line.clickEvent(ClickEvent.runCommand("/channel " + channel.id()))
                        .hoverEvent(HoverEvent.showText(lang().component(player, "channel-list-hover", "channel", channel.displayName())));
            plugin.core().compat().platform().sendMessage(player, line);
        }
    }

    // ------------------------------------------------------------------ ignore and moderation

    private void listIgnored(Player player) {
        if (plugin.refuse(player, Settings.Feature.IGNORE)) return;
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (profile.ignored.isEmpty()) { lang().send(player, "ignore-list-empty"); return; }
        List<String> names = new ArrayList<>();
        for (var id : profile.ignored) names.add(ChatMenu.name(id));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        lang().send(player, "ignore-list", "players", String.join(", ", names));
    }

    private void listMutes(CommandSender sender) {
        if (plugin.refuse(sender, Settings.Feature.MUTES)) return;
        long now = System.currentTimeMillis();
        List<MuteStore.Mute> mutes = plugin.mutes().list(now);
        lang().send(sender, "mute-list-header", "count", mutes.size());
        for (MuteStore.Mute mute : mutes)
            lang().send(sender, "mute-list-entry", "player", mute.name(), "time", mute.permanent() ? lang().plain(sender, "permanent") : Durations.format(mute.remaining(now)),
                    "reason", mute.reason().isBlank() ? "-" : mute.reason(), "by", mute.by());
    }

    /** {@code /mute PLAYER [time] [reason...]}; without a time it lasts until /unmute. No arguments: mute menu. */
    private void mute(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        if (plugin.refuse(sender, Settings.Feature.MUTES)) return;
        String name = ctx.get("player");
        if (name == null) {
            if (sender instanceof Player player) plugin.menu().mutes(player); else listMutes(sender);
            return;
        }
        OfflinePlayer target = plugin.offline(name);
        if (target == null) throw ctx.fail("player-unknown", "player", name);
        String[] rest = ctx.<String>optional("time-reason").map(s -> s.split(" +")).orElse(new String[0]);
        long millis = 0;
        int reasonFrom = 0;
        if (rest.length >= 1) {
            long parsed = Durations.muteTime(rest[0]);
            if (parsed == -1) {
                lang().send(sender, "invalid-duration", "input", rest[0]);
                lang().send(sender, "usage-mute");
                return;
            }
            if (parsed != Durations.NO_TIME) { millis = parsed; reasonFrom = 1; }
        }
        String reason = String.join(" ", Arrays.copyOfRange(rest, reasonFrom, rest.length)).trim();
        plugin.mute(sender, target, millis, reason);
    }

    private void slowMode(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        String input = ctx.get("seconds");
        if (input == null) {
            int seconds = plugin.service().slowMode(null);
            lang().send(sender, seconds > 0 ? "slow-mode-status" : "slow-mode-status-off", "time", Durations.format(seconds * 1000L));
            lang().send(sender, "usage-slowmode");
            return;
        }
        int seconds;
        if (ctx.keyword(input, "off") != null || input.equals("0")) seconds = 0;
        else {
            long millis = input.chars().allMatch(Character::isDigit) && input.length() <= 5 ? Long.parseLong(input) * 1000L : Durations.parse(input);
            if (millis <= 0 || millis > 3_600_000L) throw ctx.fail("invalid-duration", "input", input);
            seconds = (int) (millis / 1000);
        }
        Channel channel = null;
        String name = ctx.get("channel");
        if (name != null) {
            channel = plugin.service().settings().channel(name);
            if (channel == null) throw ctx.fail("channel-unknown", "channel", name);
        }
        plugin.slowMode(sender, channel, seconds);
    }

    // ------------------------------------------------------------------ completion helpers

    private List<String> players(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sender instanceof Player self && (player.equals(self) || !self.canSee(player))) continue;
            names.add(player.getName());
        }
        return names;
    }
}
