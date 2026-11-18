package at.esoren.vystormchat;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/** Bukkit chat adapter; the Paper renderer remains in its separately loaded adapter. */
final class BukkitChatListener implements Listener {
    private final ChatPlugin plugin;
    private final Map<AsyncPlayerChatEvent, Delivery> pending = new ConcurrentHashMap<>();
    BukkitChatListener(ChatPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void muted(AsyncPlayerChatEvent event) {
        long now = System.currentTimeMillis();
        MuteStore.Mute mute = plugin.mutes().active(event.getPlayer().getUniqueId(), now);
        if (mute == null) return;
        event.setCancelled(true);
        ChatService.Denied denied = plugin.service().muted(mute, now);
        plugin.lang().send(event.getPlayer(), denied.key(), denied.placeholders());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void chat(AsyncPlayerChatEvent event) {
        if (plugin.off(Settings.Feature.CHAT)) return; // chat.enabled off: the server chat stays as it is
        try {
            String raw = event.getMessage();
            Player player = event.getPlayer();
            Settings settings = plugin.service().settings();
            Channel.Prefixed prefixed = settings.enabled(Settings.Feature.QUICK_PREFIXES) ? Channel.quickPrefix(settings.channels.values(), raw) : null;
            Channel channel = prefixed != null && prefixed.channel().writable(player::hasPermission) ? prefixed.channel() : plugin.focus(player);
            String text = prefixed != null && prefixed.channel() == channel ? prefixed.text() : raw;
            ChatService.Outcome outcome = plugin.service().prepare(player, channel, text, ChatService.Source.CHAT, new ArrayList<>(event.getRecipients()));
            switch (outcome) {
                case ChatService.Denied denied -> {
                    event.setCancelled(true);
                    plugin.lang().send(player, denied.key(), denied.placeholders());
                }
                case ChatService.Ignored ignored -> event.setCancelled(true);
                case ChatService.Forward forward -> {
                    event.setCancelled(true);
                    plugin.tasks().now(() -> { if (player.isOnline()) player.performCommand(forward.command() + " " + forward.text()); });
                }
                case ChatService.Deliver deliver -> {
                    Delivery delivery = deliver.delivery();
                    if (!settings.relayed(delivery.channel)) {
                        event.setCancelled(true);
                        event.getRecipients().clear();
                        plugin.tasks().now(() -> plugin.service().deliver(delivery));
                    } else {
                        // Keep public events available to configured chat relays. Bukkit cannot render
                        // per viewer, so real player delivery is performed once after final cancellation checks.
                        event.getRecipients().removeIf(viewer -> !delivery.reach.containsKey(viewer.getUniqueId()));
                        event.setMessage(delivery.plain);
                        event.setFormat(settings.consoleLog ? plugin.core().compat().platform().legacy(delivery.render(Bukkit.getConsoleSender())).replace("%", "%%") : "");
                        pending.put(event, delivery);
                    }
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            event.setCancelled(true);
            event.getRecipients().clear();
            pending.remove(event);
            plugin.getLogger().log(Level.WARNING, "Bukkit chat pipeline failed; message was not delivered", failure);
            plugin.core().compat().platform().sendMessage(event.getPlayer(), Component.text(event.getMessage()));
            plugin.lang().send(event.getPlayer(), "chat-failed");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finished(AsyncPlayerChatEvent event) {
        Delivery delivery = pending.remove(event);
        if (delivery == null || event.isCancelled()) return;
        Delivery accepted = takeRecipients(event, delivery);
        plugin.tasks().now(() -> {
            // A later MONITOR listener can still cancel before this main-thread task runs.
            if (event.isCancelled()) return;
            for (UUID id : accepted.reach.keySet()) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null) plugin.core().compat().platform().sendMessage(viewer, accepted.render(viewer));
            }
            plugin.service().after(accepted);
        });
    }

    static Delivery takeRecipients(AsyncPlayerChatEvent event, Delivery delivery) {
        var recipients = new java.util.HashSet<UUID>();
        for (Player viewer : event.getRecipients()) recipients.add(viewer.getUniqueId());
        // Suppress native string delivery after all ordinary recipient filters have run.
        event.getRecipients().clear();
        return delivery.restrictTo(recipients);
    }
}
