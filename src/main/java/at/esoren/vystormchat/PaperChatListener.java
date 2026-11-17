package at.esoren.vystormchat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Paper event adapter, loaded only when its event API exists. */
final class PaperChatListener implements Listener {
    private final ChatPlugin plugin;
    PaperChatListener(ChatPlugin plugin) { this.plugin = plugin; }
    /** Mutes first, before party chat toggles and other chat plugins get the message. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatMuted(AsyncChatEvent event) {
        long now = System.currentTimeMillis();
        MuteStore.Mute mute = plugin.mutes().active(event.getPlayer().getUniqueId(), now);
        if (mute == null) return;
        event.setCancelled(true);
        ChatService.Denied denied = plugin.service().muted(mute, now);
        plugin.lang().send(event.getPlayer(), denied.key(), denied.placeholders());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        failClosed(event, () -> handle(event), failure -> {
            // never fall back to the server chat: a staff or party message would reach everyone
            plugin.getLogger().log(Level.WARNING, "Chat pipeline failed for " + player.getName() + "; the message was not sent", failure);
            plugin.core().compat().platform().sendMessage(player, event.originalMessage().colorIfAbsent(NamedTextColor.GRAY));
            plugin.lang().send(player, "chat-failed");
        });
    }

    /**
     * Runs {@code handling}; when it fails, the event is cancelled and its viewers cleared, so the message never goes
     * out through the server chat with the wrong (all) recipients.
     */
    static void failClosed(AsyncChatEvent event, Runnable handling, Consumer<Throwable> failed) {
        try {
            handling.run();
        } catch (RuntimeException | LinkageError failure) {
            event.setCancelled(true);
            try { event.viewers().clear(); } catch (RuntimeException unmodifiable) { /* cancelled is enough */ }
            failed.accept(failure);
        }
    }

    private void handle(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Settings settings = plugin.service().settings();
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        Channel channel = null;
        String text = raw;
        Channel.Prefixed prefixed = Channel.quickPrefix(settings.channels.values(), raw);
        if (prefixed != null && prefixed.channel().writable(player::hasPermission)) {
            channel = prefixed.channel();
            text = prefixed.text();
        }
        if (channel == null) channel = plugin.focus(player);
        List<Player> candidates = new ArrayList<>();
        for (Audience viewer : event.viewers()) if (viewer instanceof Player other) candidates.add(other);
        ChatService.Outcome outcome = plugin.service().prepare(player, channel, text, ChatService.Source.CHAT, candidates);
        switch (outcome) {
            case ChatService.Denied denied -> {
                event.setCancelled(true);
                plugin.lang().send(player, denied.key(), denied.placeholders());
            }
            case ChatService.Ignored ignored -> event.setCancelled(true);
            case ChatService.Forward forward -> {
                event.setCancelled(true);
                String command = forward.command() + " " + forward.text();
                plugin.tasks().now(() -> { if (player.isOnline()) player.performCommand(command); });
            }
            case ChatService.Deliver deliver -> {
                Delivery delivery = deliver.delivery();
                if (!settings.relayed(delivery.channel)) {
                    // out of the server chat, so DiscordSRV and other chat listeners never see staff/party/... messages
                    event.setCancelled(true);
                    plugin.tasks().now(() -> plugin.service().deliver(delivery));
                    return;
                }
                boolean console = settings.consoleLog;
                event.viewers().removeIf(viewer -> viewer instanceof Player other ? !delivery.reach.containsKey(other.getUniqueId()) : !console);
                event.message(delivery.body("en", null));
                event.renderer((source, displayName, message, viewer) -> delivery.render(viewer));
                plugin.service().after(delivery);
            }
        }
    }

}
