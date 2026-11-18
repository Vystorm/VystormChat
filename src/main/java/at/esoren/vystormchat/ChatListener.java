package at.esoren.vystormchat;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Normal chat (Paper's Paper chat adapter): picks the channel (quick prefix, else the player's focus), runs the
 * pipeline and narrows the viewers. Channels not listed in {@code relay.only-channels} leave the server chat (cancelled,
 * delivered by us), so other chat plugins never relay them; a failing pipeline cancels the message. Other plugins that cancel chat first (e.g. a party chat toggle) are respected;
 * mutes are checked at the lowest priority so they also hold for those.
 */
final class ChatListener implements Listener {
    private final ChatPlugin plugin;

    ChatListener(ChatPlugin plugin) { this.plugin = plugin; }

    /** Muted players cannot use the configured commands (/me, /msg, /partychat, /vtrade ...). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message.length() < 2) return;
        String label = message.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!plugin.service().settings().mutedCommands.contains(label) && !plugin.service().settings().mutedCommands.contains(english(label))) return;
        long now = System.currentTimeMillis();
        MuteStore.Mute mute = plugin.mutes().active(event.getPlayer().getUniqueId(), now);
        if (mute == null) return;
        event.setCancelled(true);
        ChatService.Denied denied = plugin.service().muted(mute, now);
        plugin.lang().send(event.getPlayer(), denied.key(), denied.placeholders());
    }

    /**
     * chat.enabled off: the plugin's /msg (any of its names) steps aside and the vanilla /msg runs instead, so private
     * messages work as on a server without the plugin. Applies at once, no restart. An explicit namespace
     * ({@code /vystormchat:msg}) and /reply (vanilla has none) keep the plugin's off message.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPrivateMessageWhileOff(PlayerCommandPreprocessEvent event) {
        if (!plugin.off(Settings.Feature.CHAT)) return;
        String vanilla = vanillaMessage(event.getMessage(), this::ownMsgLabel);
        if (vanilla != null) event.setMessage(vanilla);
    }

    /** The same for the console. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsolePrivateMessageWhileOff(org.bukkit.event.server.ServerCommandEvent event) {
        if (!plugin.off(Settings.Feature.CHAT)) return;
        String vanilla = vanillaMessage(event.getCommand(), this::ownMsgLabel);
        if (vanilla != null) event.setCommand(vanilla);
    }

    /** {@code line} sent to the vanilla /msg when its label reaches this plugin's /msg, else {@code null}. */
    static String vanillaMessage(String line, java.util.function.Predicate<String> ownMsgLabel) {
        boolean slash = line.startsWith("/");
        String[] parts = (slash ? line.substring(1) : line).split(" ", 2);
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (label.isEmpty() || label.indexOf(':') >= 0 || !ownMsgLabel.test(label)) return null;
        return (slash ? "/" : "") + "minecraft:msg" + (parts.length > 1 ? " " + parts[1] : "");
    }

    /**
     * Whether {@code label} reaches this plugin's /msg: its English name (unless another plugin has /msg), a fixed alias
     * no other command took, or an active localized alias. Labels of other plugins (an Essentials /msg, a /pm) stay theirs.
     */
    private boolean ownMsgLabel(String label) {
        return plugin.core().command().find(label)
                .filter(r -> r.plugin() == plugin && "msg".equals(r.label()))
                .filter(r -> !label.equals("msg") || r.labelConflict() == null)
                .isPresent();
    }

    /** The English label of a localized Core alias (/nachricht → msg), else the label itself. */
    private String english(String label) {
        return plugin.core().command().find(label).map(r -> r.label()).orElse(label);
    }

    /** Loads the chat profile on the login thread, so it is ready when the player joins. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID id = event.getUniqueId();
        try {
            plugin.profiles().load(id).get(5, TimeUnit.SECONDS);
        } catch (Exception slow) {
            plugin.getLogger().log(Level.FINE, "Chat profile of " + id + " loads late", slow);
        }
    }

    /** Plugins enabled after us (economy, parties, cities, factions) register their services by now. */
    @EventHandler
    public void onServerLoaded(org.bukkit.event.server.ServerLoadEvent event) {
        plugin.load(); // again: forward commands like /partychat exist only now
        plugin.getLogger().info("Hooks: " + plugin.hooks().summary());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.profiles().get(player.getUniqueId());
        plugin.states().join(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.states().quit(player);
        plugin.profiles().unload(player.getUniqueId());
        plugin.service().forget(player.getUniqueId());
        plugin.messages().forget(player.getUniqueId());
        plugin.forgetPanels(player.getUniqueId());
    }
}
