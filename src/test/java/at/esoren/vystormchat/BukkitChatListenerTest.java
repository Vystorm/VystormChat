package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.Test;

class BukkitChatListenerTest {
    private Player player() {
        Player player=mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }
    @Test void finalRecipientsRespectOtherPluginsWithoutAddingUnauthorizedViewers() {
        Player sender=player(), allowed=player(), removed=player(), outsider=player();
        ChatService service=mock(ChatService.class);
        when(service.settings()).thenReturn(new Settings(new YamlConfiguration(),Logger.getLogger("test")));
        var reach=Map.of(sender.getUniqueId(),Visibility.Reach.NORMAL,allowed.getUniqueId(),Visibility.Reach.NORMAL,removed.getUniqueId(),Visibility.Reach.SPY);
        Delivery delivery=new Delivery(service,sender,null,TestChannels.one("scope: global"),"message","message",Set.of(),false,Showcase.Snapshot.NONE,List.of(),reach);
        var event=new AsyncPlayerChatEvent(true,sender,"message",new HashSet<>(Set.of(sender,allowed,outsider)));
        Delivery accepted=BukkitChatListener.takeRecipients(event,delivery);
        assertEquals(Set.of(sender.getUniqueId(),allowed.getUniqueId()),accepted.reach.keySet());
        assertTrue(event.getRecipients().isEmpty(),"native Bukkit string delivery must not duplicate rendered messages");
        assertEquals(3,delivery.reach.size(),"the original delivery remains unchanged");
    }
    @Test void emptyFinalRecipientSetDoesNotRestoreOriginalAudience() {
        Player sender=player();ChatService service=mock(ChatService.class);
        when(service.settings()).thenReturn(new Settings(new YamlConfiguration(),Logger.getLogger("test")));
        Delivery delivery=new Delivery(service,sender,null,TestChannels.one("scope: global"),"message","message",Set.of(),false,Showcase.Snapshot.NONE,List.of(),Map.of(sender.getUniqueId(),Visibility.Reach.NORMAL));
        var event=new AsyncPlayerChatEvent(true,sender,"message",new HashSet<>());
        assertTrue(BukkitChatListener.takeRecipients(event,delivery).reach.isEmpty());
    }
}
