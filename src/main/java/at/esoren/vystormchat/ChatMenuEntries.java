package at.esoren.vystormchat;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Entries in the Vystorm Core player menu (sneak + right-click a player): send a private message,
 * ignore/unignore and, for staff, mute/unmute.
 */
final class ChatMenuEntries {
    private ChatMenuEntries() {}

    static void register(ChatPlugin plugin) {
        Lang lang = plugin.lang();
        var playerMenu = plugin.core().gui().playerMenu();
        playerMenu.register(plugin, PlayerMenuEntry.builder("chat-message")
                .priority(20)
                .visibleWhen((viewer, target) -> viewer.hasPermission("vystormchat.msg") && !plugin.off(Settings.Feature.PRIVATE_MESSAGES))
                .icon((viewer, target) -> Menu.item(lang, viewer, Material.WRITABLE_BOOK, "player-menu.message", "player-menu.message-lore", "player", target.getName()))
                .onClick((viewer, target, click) -> {
                    plugin.menu().messageDialog(viewer, target.getName());
                    return PlayerMenu.Result.KEEP;
                })
                .build());
        playerMenu.register(plugin, PlayerMenuEntry.builder("chat-ignore")
                .priority(30)
                .visibleWhen((viewer, target) -> viewer.hasPermission("vystormchat.ignore") && !target.hasPermission("vystormchat.ignore.exempt") && !plugin.off(Settings.Feature.IGNORE))
                .icon((viewer, target) -> {
                    boolean ignoring = plugin.profiles().get(viewer.getUniqueId()).ignores(target.getUniqueId());
                    return Menu.item(lang, viewer, ignoring ? Material.LIME_DYE : Material.GRAY_DYE,
                            ignoring ? "player-menu.unignore" : "player-menu.ignore", ignoring ? "player-menu.unignore-lore" : "player-menu.ignore-lore",
                            "player", target.getName());
                })
                .onClick((viewer, target, click) -> {
                    plugin.toggleIgnore(viewer, target);
                    return PlayerMenu.Result.REFRESH;
                })
                .build());
        playerMenu.register(plugin, PlayerMenuEntry.builder("chat-mute")
                .priority(250)
                .visibleWhen((viewer, target) -> viewer.hasPermission("vystormchat.mute") && !plugin.off(Settings.Feature.MUTES))
                .icon((viewer, target) -> {
                    MuteStore.Mute mute = plugin.mutes().active(target.getUniqueId(), System.currentTimeMillis());
                    ItemStack item = Menu.item(lang, viewer, mute != null ? Material.TRIPWIRE_HOOK : Material.LEAD,
                            mute != null ? "player-menu.unmute" : "player-menu.mute", mute != null ? "player-menu.unmute-lore" : "player-menu.mute-lore",
                            "player", target.getName());
                    return item;
                })
                .onClick((viewer, target, click) -> {
                    if (plugin.mutes().active(target.getUniqueId(), System.currentTimeMillis()) != null) {
                        plugin.unmute(viewer, target);
                        return PlayerMenu.Result.REFRESH;
                    }
                    plugin.menu().muteDialog(viewer, target.getName());
                    return PlayerMenu.Result.KEEP;
                })
                .build());
    }

}
