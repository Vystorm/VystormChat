package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ReplyLockTest {
    private final ChatPlugin plugin = mock(ChatPlugin.class);
    private final ChatService service = mock(ChatService.class);
    private final ProfileStore profiles = mock(ProfileStore.class);
    private final PrivateMessages messages = new PrivateMessages(plugin);

    private Player player(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.locale()).thenReturn(Locale.US);
        when(player.canSee(any(Player.class))).thenReturn(true);
        Profile profile = new Profile(id);
        profile.messageSound(false);
        when(profiles.cached(id)).thenReturn(profile);
        return player;
    }

    private void setup() throws Exception {
        when(plugin.lang()).thenReturn(Lang.create("VystormChat", Map.of("en", Files.readString(Path.of("src/main/resources/lang/en.yml")))));
        when(plugin.service()).thenReturn(service);
        var hub = TestCore.api();
        when(plugin.core()).thenReturn(hub);
        when(plugin.profiles()).thenReturn(profiles);
        when(plugin.mutes()).thenReturn(mock(MuteStore.class));
        when(plugin.chatLog()).thenReturn(mock(ChatLog.class));
        when(service.settings()).thenReturn(new Settings(YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")), Logger.getLogger("test")));
        when(service.screen(any(), anyString(), anySet(), anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
    }

    @Test
    void actualDeliveryKeepsLockedPartnerAndRejectsIgnoredReplies() throws Exception {
        setup();
        Player alice = player("Alice"), bob = player("Bob"), charlie = player("Charlie");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(() -> Bukkit.getPlayer(bob.getUniqueId())).thenReturn(bob);
            messages.send(bob, alice, "hello");
            messages.toggleReplyLock(alice);
            messages.send(charlie, alice, "another conversation");
            assertEquals(bob.getUniqueId(), messages.partner(alice.getUniqueId()));
            clearInvocations(bob);
            profiles.cached(bob.getUniqueId()).toggleIgnore(alice.getUniqueId());
            messages.reply(alice, "private reply");
            verify(bob, never()).sendMessage(any(Component.class));
            assertEquals(bob.getUniqueId(), messages.partner(alice.getUniqueId()));
            messages.toggleReplyLock(alice);
            messages.send(charlie, alice, "after unlock");
            assertEquals(charlie.getUniqueId(), messages.partner(alice.getUniqueId()));
        }
    }

    @Test
    void vanishedLockedPartnerNeverReceivesReplyAndCanStillBeUnlocked() throws Exception {
        setup();
        Player alice = player("Alice"), bob = player("Bob"), charlie = player("Charlie");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(() -> Bukkit.getPlayer(bob.getUniqueId())).thenReturn(bob);
            messages.send(bob, alice, "hello");
            messages.toggleReplyLock(alice);
            when(alice.canSee(bob)).thenReturn(false);
            clearInvocations(bob);
            messages.reply(alice, "hidden reply");
            verify(bob, never()).sendMessage(any(Component.class));
            messages.toggleReplyLock(alice);
            messages.send(charlie, alice, "after unlock");
            assertEquals(charlie.getUniqueId(), messages.partner(alice.getUniqueId()));
            messages.forget(alice.getUniqueId());
            assertNull(messages.partner(alice.getUniqueId()));
        }
    }
}
