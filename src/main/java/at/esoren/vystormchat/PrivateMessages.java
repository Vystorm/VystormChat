package at.esoren.vystormchat;

import Vystorm.vystorm_core.lang.Lang;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /msg}, {@code /r} and social spy. Private messages pass the same mute, spam, word and link checks as chat,
 * respect ignore lists and the "allow private messages" setting, and hidden (vanished) players count as offline.
 * Server thread.
 */
final class PrivateMessages {
    /** Reply partner id used for the console. */
    static final UUID CONSOLE = new UUID(0, 0);

    private final ChatPlugin plugin;
    private final ReplyTargets replies = new ReplyTargets();

    PrivateMessages(ChatPlugin plugin) { this.plugin = plugin; }

    void forget(UUID player) { replies.forget(player); }

    UUID partner(UUID player) { return replies.partner(player); }

    void toggleReplyLock(Player player) {
        if (plugin.refuse(player, Settings.Feature.PRIVATE_MESSAGES)) return;
        UUID id = player.getUniqueId();
        if (replies.unlock(id)) { plugin.lang().send(player, "reply-lock-off"); return; }
        UUID partner = replies.partner(id);
        if (partner == null) { plugin.lang().send(player, "reply-nobody"); return; }
        Player target = partner.equals(CONSOLE) ? null : Bukkit.getPlayer(partner);
        if (!partner.equals(CONSOLE) && (target == null || !player.canSee(target) && !player.hasPermission("vystormchat.seehidden"))) {
            plugin.lang().send(player, "reply-offline");
            return;
        }
        replies.lock(id);
        plugin.lang().send(player, "reply-lock-on", "player", target == null ? "Console" : target.getName());
    }

    /** Finds an online player the sender may see: exact name first, then a unique start of a name. */
    static Player find(CommandSender sender, String name) {
        Player exact = Bukkit.getPlayerExact(name);
        Player found = exact != null ? exact : Bukkit.getPlayer(name);
        if (found == null) return null;
        if (sender instanceof Player player && !player.canSee(found) && !player.hasPermission("vystormchat.seehidden")) return null;
        return found;
    }

    void send(CommandSender from, String targetName, String text) {
        if (plugin.refuse(from, Settings.Feature.PRIVATE_MESSAGES)) return;
        Lang lang = plugin.lang();
        Player target = find(from, targetName);
        if (target == null && from instanceof Player player && targetName.equalsIgnoreCase("Console") && CONSOLE.equals(replies.partner(player.getUniqueId()))) {
            // "Reply" in the chat menu after the console wrote: answer the console like /r does
            send(from, Bukkit.getConsoleSender(), text);
            return;
        }
        if (target == null) { lang.send(from, "player-not-found", "player", targetName); return; }
        send(from, target, text);
    }

    void reply(Player from, String text) {
        if (plugin.refuse(from, Settings.Feature.PRIVATE_MESSAGES)) return;
        Lang lang = plugin.lang();
        UUID partner = replies.partner(from.getUniqueId());
        if (partner == null) { lang.send(from, "reply-nobody"); return; }
        if (partner.equals(CONSOLE)) { send(from, Bukkit.getConsoleSender(), text); return; }
        Player target = Bukkit.getPlayer(partner);
        if (target == null || !from.canSee(target) && !from.hasPermission("vystormchat.seehidden")) { lang.send(from, "reply-offline"); return; }
        send(from, target, text);
    }

    /** Sends one private message after every check. */
    void send(CommandSender from, CommandSender to, String input) {
        if (plugin.refuse(from, Settings.Feature.PRIVATE_MESSAGES)) return;
        Lang lang = plugin.lang();
        Settings s = plugin.service().settings();
        if (from == to || from instanceof Player a && to instanceof Player b && a.getUniqueId().equals(b.getUniqueId())) {
            lang.send(from, "msg-self");
            return;
        }
        String text = ChatService.clean(input);
        if (text.isEmpty()) { lang.send(from, "usage-msg"); return; }
        if (text.length() > s.maxLength) { lang.send(from, "too-long", "max", s.maxLength); return; }
        Set<PlayerText.Style> styles = EnumSet.noneOf(PlayerText.Style.class);
        boolean linksAllowed = true;
        if (from instanceof Player sender) {
            long now = System.currentTimeMillis();
            MuteStore.Mute mute = plugin.mutes().active(sender.getUniqueId(), now);
            if (mute != null && !(to instanceof ConsoleCommandSender)) {
                ChatService.Denied denied = plugin.service().muted(mute, now);
                lang.send(sender, denied.key(), denied.placeholders());
                return;
            }
            if (to instanceof Player target) {
                boolean ignoreLists = !plugin.off(Settings.Feature.IGNORE);
                Profile own = plugin.profiles().cached(sender.getUniqueId());
                if (ignoreLists && own != null && own.ignores(target.getUniqueId())) { lang.send(sender, "msg-you-ignore", "player", target.getName()); return; }
                Profile theirs = plugin.profiles().cached(target.getUniqueId());
                boolean bypass = sender.hasPermission("vystormchat.msg.bypass");
                if (!bypass && theirs != null && (!theirs.allowMessages() || ignoreLists && theirs.ignores(sender.getUniqueId()) && !sender.hasPermission("vystormchat.ignore.exempt"))) {
                    lang.send(sender, "msg-not-accepted", "player", target.getName());
                    return;
                }
            }
            if (s.enabled(Settings.Feature.PLAYER_FORMATTING)) styles.addAll(PlayerText.Style.of(sender::hasPermission));
            Object screened = plugin.service().screen(sender, text, styles, "msg", sender.getUniqueId() + ":msg:" + to.getName());
            if (screened instanceof ChatService.Denied denied) { lang.send(sender, denied.key(), denied.placeholders()); return; }
            text = (String) screened;
            if (s.emojisEnabled && sender.hasPermission("vystormchat.emoji")) text = Emojis.apply(text, s.emojis);
            linksAllowed = sender.hasPermission("vystormchat.links");
        }
        String plain = PlayerText.plain(text, styles);
        Component body = body(text, styles, linksAllowed, s);
        plugin.core().compat().platform().sendMessage(from, Formats.render(s.pmTo, null, Placeholder.component("name", name(from, to, to)), Placeholder.component("message", body)));
        plugin.core().compat().platform().sendMessage(to, Formats.render(s.pmFrom, null, Placeholder.component("name", name(to, from, from)), Placeholder.component("message", body)));
        UUID fromId = id(from), toId = id(to);
        replies.delivered(fromId, toId);
        if (to instanceof Player target) {
            Profile profile = plugin.profiles().cached(target.getUniqueId());
            if (profile == null || profile.messageSound()) ChatService.play(plugin.core().compat().platform(), target, s.pmSound, s.pmVolume, s.pmPitch);
        }
        Component spy = Formats.render(s.pmSpy, null, Placeholder.unparsed("sender", from.getName()), Placeholder.unparsed("receiver", to.getName()),
                Placeholder.component("message", body));
        if (s.enabled(Settings.Feature.SOCIAL_SPY)) for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff == from || staff == to || !staff.hasPermission("vystormchat.spy")) continue;
            Profile profile = plugin.profiles().cached(staff.getUniqueId());
            if (profile != null && profile.spy()) plugin.core().compat().platform().sendMessage(staff, spy);
        }
        plugin.chatLog().log("msg", from.getName() + " -> " + to.getName() + ": " + plain);
    }

    private static UUID id(CommandSender sender) { return sender instanceof Player player ? player.getUniqueId() : CONSOLE; }

    /** The other side's name: hover "click to reply", click puts /msg NAME into the chat field. */
    private Component name(CommandSender viewer, CommandSender other, CommandSender named) {
        Component name = Component.text(named.getName());
        if (!(named instanceof Player)) return name;
        return name.hoverEvent(HoverEvent.showText(plugin.lang().component(viewer, "msg-name-hover", "player", named.getName())))
                .clickEvent(ClickEvent.suggestCommand("/msg " + named.getName() + " "));
    }

    private Component body(String text, Set<PlayerText.Style> styles, boolean linksAllowed, Settings s) {
        Component body = PlayerText.parse(text, styles);
        if (!s.enabled(Settings.Feature.LINKS)) return body;
        return body.replaceText(TextReplacementConfig.builder().match(Links.LINK).replacement((match, builder) -> {
            String link = Links.trimTrailing(match.group(1));
            if (!linksAllowed && !Links.allowed(link, s.allowedDomains)) return builder;
            Component clickable = Formats.render(s.linkFormat, null, Placeholder.unparsed("link", link)).clickEvent(ClickEvent.openUrl(Links.url(link)));
            String rest = match.group(1).substring(link.length());
            return rest.isEmpty() ? clickable : Component.text().append(clickable).append(Component.text(rest));
        }).build());
    }
}
