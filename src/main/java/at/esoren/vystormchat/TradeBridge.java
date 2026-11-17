package at.esoren.vystormchat;

import Vystorm.vystorm_core.chat.ChannelChatEvent;
import Vystorm.vystorm_core.chat.ChatChannels;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Core's {@code /vtrade}: when a channel has {@code core-trade: true}, every {@code /vtrade} message
 * runs through that channel instead (format, hover card, mute, filters, ignore, hidden channel), so /vtrade and the
 * chat channel are one trade chat. Without such a channel, /vtrade stays Core's own and only mutes apply.
 * Only loaded after the capability check.
 */
final class TradeBridge implements Listener {
    private final ChatPlugin plugin;

    TradeBridge(ChatPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrade(ChannelChatEvent event) {
        if (event.channel() != ChatChannels.Channel.TRADE) return;
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        MuteStore.Mute mute = plugin.mutes().active(player.getUniqueId(), now);
        if (mute != null) {
            event.setCancelled(true);
            ChatService.Denied denied = plugin.service().muted(mute, now);
            plugin.lang().send(player, denied.key(), denied.placeholders());
            return;
        }
        Channel trade = plugin.service().settings().coreTrade();
        if (trade == null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.sendTo(player, trade, text, ChatService.Source.CORE_TRADE);
    }

    static void register(ChatPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(new TradeBridge(plugin), plugin);
    }
}
