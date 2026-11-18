package at.esoren.vystormchat;

import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.dialog.DialogInterface;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * The chat menu ({@code /channel}, {@code /vchat}, Vystorm Core main menu → Community → Chat): every channel with
 * focus and show/hide on click, private message, reply, ignore list, personal settings, social spy and, for staff,
 * clear chat, slow mode and mutes. Every button runs the same code as the matching command.
 */
final class ChatMenu {
    private static final int[] SLOW_STEPS = {0, 3, 5, 10, 30, 60};

    private final ChatPlugin plugin;

    ChatMenu(ChatPlugin plugin) { this.plugin = plugin; }

    private Lang lang() { return plugin.lang(); }

    // ------------------------------------------------------------------ main page

    void open(Player player) {
        if (plugin.refuse(player, Settings.Feature.CHAT_MENU)) return;
        if (!plugin.openPanel(player, "chat", "")) openChest(player);
    }

    void openChest(Player player) {
        Lang lang = lang();
        Settings settings = plugin.service().settings();
        List<Channel> channels = new ArrayList<>();
        PlayerState state = plugin.states().get(player.getUniqueId());
        for (Channel channel : settings.channels.values()) {
            if (!channel.readable(player::hasPermission) && !channel.writable(player::hasPermission)) continue;
            // party, city and faction chats only show up for members
            if (channel.scope().group() && state != null && state.groupOf(channel.scope()) == null) continue;
            channels.add(channel);
        }
        int channelRows = Math.clamp((Math.min(27, channels.size()) + 8) / 9, 1, 3);
        Menu menu = Menu.create(lang.component(player, "menu.title"), channelRows + 2);
        Channel focus = plugin.focus(player);
        Profile profile = plugin.profiles().get(player.getUniqueId());
        for (int i = 0; i < channels.size() && i < 27; i++) {
            Channel channel = channels.get(i);
            menu.set(i, channelIcon(player, channel, channel.id().equals(focus.id()), profile), (who, click) -> channelClick(who, channel, click));
        }
        int base = (channelRows + 1) * 9;
        if (!plugin.off(Settings.Feature.REPORTS))
            menu.set(base + 3, Menu.item(lang, player, Material.WRITABLE_BOOK, "reports.submit", null), (who, click) -> plugin.reports().reportDialog(who));
        boolean msg = player.hasPermission("vystormchat.msg") && !plugin.off(Settings.Feature.PRIVATE_MESSAGES);
        if (msg) menu.set(base, Menu.item(lang, player, Material.WRITABLE_BOOK, "menu.message", "menu.message-lore"), (who, click) -> messageDialog(who, ""));
        UUID partner = plugin.messages().partner(player.getUniqueId());
        String partnerName = partner == null ? null : partner.equals(PrivateMessages.CONSOLE) ? "Console" : name(partner);
        if (msg && partnerName != null)
            menu.set(base + 1, Menu.item(lang, player, Material.FEATHER, "menu.reply", "menu.reply-lore", "player", partnerName), (who, click) -> messageDialog(who, partnerName));
        if (player.hasPermission("vystormchat.ignore") && !plugin.off(Settings.Feature.IGNORE))
            menu.set(base + 2, Menu.item(lang, player, Material.STRUCTURE_VOID, "menu.ignored", "menu.ignored-lore", "count", profile.ignored.size()), (who, click) -> ignored(who));
        menu.set(base + 4, Menu.item(lang, player, Material.COMPARATOR, "menu.settings", "menu.settings-lore"), (who, click) -> plugin.openSettings(who));
        if (player.hasPermission("vystormchat.spy") && !plugin.off(Settings.Feature.SOCIAL_SPY))
            menu.set(base + 6, Menu.item(lang, player, profile.spy() ? Material.ENDER_EYE : Material.ENDER_PEARL, profile.spy() ? "menu.spy-on" : "menu.spy-off", "menu.spy-lore"),
                    (who, click) -> { plugin.toggleSpy(who); open(who); });
        if (player.hasPermission("vystormchat.moderate"))
            menu.set(base + 8, Menu.item(lang, player, Material.IRON_BARS, "menu.moderation", "menu.moderation-lore"), (who, click) -> moderation(who));
        menu.open(player);
    }

    private ItemStack channelIcon(Player player, Channel channel, boolean focused, Profile profile) {
        Lang lang = lang();
        boolean read = channel.readable(player::hasPermission), write = channel.writable(player::hasPermission);
        boolean listening = profile.listening(channel);
        String state = focused ? "menu.state-focus" : !read ? "menu.state-write-only" : listening ? "menu.state-listening" : channel.autoJoin() ? "menu.state-hidden" : "menu.state-not-joined";
        Material material = Material.matchMaterial(channel.icon());
        ItemStack item = Menu.item(lang, player, material == null ? Material.OAK_SIGN : material, "menu.channel", null,
                "channel", channel.displayName());
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(lang.component(player, state));
        lore.add(lang.component(player, "menu.scope-" + channel.scope().name().toLowerCase(Locale.ROOT), "radius", channel.radius()));
        if (!channel.quickPrefix().isEmpty()) lore.add(lang.component(player, "menu.channel-prefix", "prefix", channel.quickPrefix()));
        if (!channel.aliases().isEmpty()) lore.add(lang.component(player, "menu.channel-command", "command", "/" + channel.aliases().getFirst()));
        if (channel.cost() > 0 && plugin.hooks().money() != null) lore.add(lang.component(player, "menu.channel-cost", "price", plugin.hooks().money().format(channel.cost())));
        int slow = plugin.service().slowMode(channel.id());
        if (slow > 0) lore.add(lang.component(player, "menu.channel-slow", "time", Durations.format(slow * 1000L)));
        lore.add(net.kyori.adventure.text.Component.empty());
        if (write && !focused) lore.add(lang.component(player, "menu.click-focus"));
        if (!write) lore.add(lang.component(player, "menu.cannot-write"));
        if (read && channel.leavable() && !plugin.off(Settings.Feature.CHANNEL_CHOICE)) lore.add(lang.component(player, listening ? "menu.click-hide" : "menu.click-show"));
        plugin.core().compat().platform().editMeta(item, meta -> {
            plugin.core().compat().platform().lore(meta, Menu.lines(lore));
            if (focused) meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }

    private void channelClick(Player player, Channel channel, ClickType click) {
        if (click.isRightClick()) {
            if (channel.leavable() && channel.readable(player::hasPermission) && !plugin.off(Settings.Feature.CHANNEL_CHOICE)) {
                Profile profile = plugin.profiles().get(player.getUniqueId());
                plugin.listen(player, channel, !profile.listening(channel));
            }
        } else if (channel.writable(player::hasPermission)) {
            plugin.focus(player, channel);
        }
        open(player);
    }

    // ------------------------------------------------------------------ private message dialog

    /** Dialog with player name (pre-filled when known) and message. */
    void messageDialog(Player player, String target) {
        if (!player.hasPermission("vystormchat.msg")) { lang().send(player, "no-permission"); return; }
        if (plugin.refuse(player, Settings.Feature.PRIVATE_MESSAGES)) return;
        Lang lang = lang();
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("player", lang.component(player, "dialog.message-player")).initial(target == null ? "" : target).maxLength(16).build());
        inputs.add(DialogInput.text("message", lang.component(player, "dialog.message-text")).maxLength(256).width(300).build());
        dialogs().open(plugin, player, new DialogRequest(lang.component(player, "dialog.message-title"), List.of(), inputs,
                List.of(new DialogButton(lang.component(player, "dialog.send"), (who, response) -> {
                    if (!who.hasPermission("vystormchat.msg")) { lang.send(who, "no-permission"); return; }
                    String name = response.getText("player"), text = response.getText("message");
                    if (name == null || name.isBlank() || text == null || text.isBlank()) { lang.send(who, "usage-msg"); return; }
                    plugin.messages().send(who, name.trim(), text);
                })), Player::isOnline, () -> {}, lang.plain(player, "dialog.cancel"), 2));
    }

    // ------------------------------------------------------------------ ignore list

    void ignored(Player player) {
        if (plugin.refuse(player, Settings.Feature.IGNORE)) return;
        if (!plugin.openPanel(player, "ignored", "")) ignoredChest(player);
    }

    void ignoredChest(Player player) {
        Lang lang = lang();
        Profile profile = plugin.profiles().get(player.getUniqueId());
        List<UUID> ignored = new ArrayList<>(profile.ignored);
        Menu menu = Menu.create(lang.component(player, "menu.ignored-title"), Math.clamp((Math.min(45, ignored.size()) + 8) / 9 + 1, 2, 6));
        for (int i = 0; i < ignored.size() && i < 45; i++) {
            UUID other = ignored.get(i);
            String name = name(other);
            menu.set(i, head(player, other, "menu.ignored-entry", "menu.ignored-entry-lore", name), (who, click) -> {
                plugin.toggleIgnore(who, Bukkit.getOfflinePlayer(other));
                ignored(who);
            });
        }
        int last = menu.getInventory().getSize() - 9;
        menu.set(last, Menu.item(lang, player, Material.ARROW, "menu.back", null), (who, click) -> open(who));
        menu.set(last + 4, Menu.item(lang, player, Material.NAME_TAG, "menu.ignore-add", "menu.ignore-add-lore"), (who, click) -> ignoreDialog(who));
        menu.open(player);
    }

    void ignoreDialog(Player who) {
        if (!who.hasPermission("vystormchat.ignore") || plugin.refuse(who, Settings.Feature.IGNORE)) return;
        dialogs().prompt(plugin, who, lang().ref("dialog.ignore-title"), lang().ref("dialog.ignore-question"), "", name -> {
                    if (!who.hasPermission("vystormchat.ignore")) return;
                    if (name == null || name.isBlank()) return;
                    plugin.ignoreCommand(who, name.trim());
                    ignored(who);
                }, () -> {}, p -> p.isOnline() && p.hasPermission("vystormchat.ignore"));
    }

    // ------------------------------------------------------------------ moderation

    void moderation(Player player) {
        if (plugin.refuse(player, Settings.Feature.CHAT_MENU)) return;
        if (!plugin.openPanel(player, "moderation", "")) moderationChest(player);
    }

    void moderationChest(Player player) {
        if (!player.hasPermission("vystormchat.moderate")) { lang().send(player, "no-permission"); return; }
        Lang lang = lang();
        Menu menu = Menu.create(lang.component(player, "menu.moderation-title"), 3);
        if (player.hasPermission("vystormchat.clearchat") && !plugin.off(Settings.Feature.CLEAR_CHAT))
            menu.set(10, Menu.item(lang, player, Material.SPONGE, "menu.clear", "menu.clear-lore"), (who, click) ->
                    dialogs().confirm(plugin, who, lang.ref("dialog.clear-title"), lang.ref("dialog.clear-question"),
                            () -> plugin.clearChat(who), () -> moderation(who), Player::isOnline));
        if (player.hasPermission("vystormchat.slowmode") && !plugin.off(Settings.Feature.SLOW_MODE)) {
            int current = plugin.service().slowMode(null);
            menu.set(12, Menu.item(lang, player, Material.CLOCK, current > 0 ? "menu.slow-on" : "menu.slow-off", "menu.slow-lore",
                    "time", Durations.format(current * 1000L)), (who, click) -> {
                int now = plugin.service().slowMode(null);
                int index = 0;
                for (int i = 0; i < SLOW_STEPS.length; i++) if (SLOW_STEPS[i] == now) index = i;
                int next = SLOW_STEPS[Math.floorMod(index + (click.isRightClick() ? -1 : 1), SLOW_STEPS.length)];
                plugin.slowMode(who, null, next);
                moderation(who);
            });
        }
        if (player.hasPermission("vystormchat.mute") && !plugin.off(Settings.Feature.MUTES)) {
            menu.set(14, Menu.item(lang, player, Material.PAPER, "menu.mutes", "menu.mutes-lore", "count", plugin.mutes().list(System.currentTimeMillis()).size()),
                    (who, click) -> mutes(who));
            menu.set(16, Menu.item(lang, player, Material.LEAD, "menu.mute-player", "menu.mute-player-lore"), (who, click) -> muteDialog(who, ""));
        }
        menu.set(18, Menu.item(lang, player, Material.ARROW, "menu.back", null), (who, click) -> open(who));
        if (!plugin.off(Settings.Feature.REPORTS))
            menu.set(20, Menu.item(lang, player, Material.WRITABLE_BOOK, "reports.title", null), (who, click) -> plugin.reports().list(who, 0));
        menu.open(player);
    }

    void mutes(Player player) {
        if (plugin.refuse(player, Settings.Feature.MUTES)) return;
        if (!plugin.openPanel(player, "mutes", "")) mutesChest(player);
    }

    void mutesChest(Player player) {
        if (!player.hasPermission("vystormchat.mute")) { lang().send(player, "no-permission"); return; }
        if (plugin.off(Settings.Feature.MUTES)) return;
        Lang lang = lang();
        long now = System.currentTimeMillis();
        List<MuteStore.Mute> mutes = plugin.mutes().list(now);
        Menu menu = Menu.create(lang.component(player, "menu.mutes-title"), Math.clamp((Math.min(45, mutes.size()) + 8) / 9 + 1, 2, 6));
        for (int i = 0; i < mutes.size() && i < 45; i++) {
            MuteStore.Mute mute = mutes.get(i);
            ItemStack head = head(player, mute.player(), "menu.mute-entry", null, mute.name());
            plugin.core().compat().platform().editMeta(head, meta -> plugin.core().compat().platform().lore(meta, Menu.lines(lang.lines(player, "menu.mute-entry-lore",
                    "time", mute.permanent() ? lang.plain(player, "permanent") : Durations.format(mute.remaining(now)),
                    "reason", mute.reason().isBlank() ? "-" : mute.reason(), "by", mute.by()))));
            menu.set(i, head, (who, click) -> dialogs().confirm(plugin, who, lang.ref("dialog.unmute-title"), lang.ref("dialog.unmute-question"),
                    () -> { plugin.unmute(who, Bukkit.getOfflinePlayer(mute.player())); mutes(who); }, () -> mutes(who), Player::isOnline));
        }
        int last = menu.getInventory().getSize() - 9;
        menu.set(last, Menu.item(lang, player, Material.ARROW, "menu.back", null), (who, click) -> moderation(who));
        menu.set(last + 4, Menu.item(lang, player, Material.LEAD, "menu.mute-player", "menu.mute-player-lore"), (who, click) -> muteDialog(who, ""));
        menu.open(player);
    }

    /** Dialog: player, duration (empty = until unmuted), reason. */
    void muteDialog(Player player, String target) {
        if (!player.hasPermission("vystormchat.mute")) { lang().send(player, "no-permission"); return; }
        if (plugin.refuse(player, Settings.Feature.MUTES)) return;
        Lang lang = lang();
        List<DialogInput> inputs = List.of(
                DialogInput.text("player", lang.component(player, "dialog.mute-player")).initial(target == null ? "" : target).maxLength(16).build(),
                DialogInput.text("time", lang.component(player, "dialog.mute-time")).initial("30m").maxLength(16).build(),
                DialogInput.text("reason", lang.component(player, "dialog.mute-reason")).maxLength(100).width(300).build());
        dialogs().open(plugin, player, new DialogRequest(lang.component(player, "dialog.mute-title"),
                List.of(DialogBody.plainMessage(lang.component(player, "dialog.mute-help"), 300)), inputs,
                List.of(new DialogButton(lang.component(player, "dialog.mute"), (who, response) -> {
                    if (!who.hasPermission("vystormchat.mute")) { lang.send(who, "no-permission"); return; }
                    String name = response.getText("player");
                    if (name == null || name.isBlank()) return;
                    String time = response.getText("time");
                    long millis = time == null || time.isBlank() ? Durations.PERMANENT : Durations.muteTime(time);
                    if (millis < 0) { lang.send(who, "invalid-duration", "input", time); return; }
                    OfflinePlayer offline = plugin.offline(name.trim());
                    if (offline == null) { lang.send(who, "player-unknown", "player", name.trim()); return; }
                    String reason = response.getText("reason");
                    plugin.mute(who, offline, millis, reason == null ? "" : reason.trim());
                })), Player::isOnline, () -> {}, lang.plain(player, "dialog.cancel"), 2));
    }

    // ------------------------------------------------------------------ helpers

    private ItemStack head(Player viewer, UUID id, String nameKey, String loreKey, String name) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        plugin.core().compat().platform().editMeta(head, SkullMeta.class, meta -> {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            plugin.core().compat().platform().displayName(meta, Menu.plain(lang().component(viewer, nameKey, "player", name)));
            if (loreKey != null) plugin.core().compat().platform().lore(meta, Menu.lines(lang().lines(viewer, loreKey, "player", name)));
        });
        return head;
    }

    static String name(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? id.toString().substring(0, 8) : name;
    }

    private DialogInterface dialogs() { return plugin.core().dialogs(); }
}
