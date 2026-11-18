package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.commands.CommandRegistration;
import java.util.Optional;
import java.util.Set;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.junit.jupiter.api.Test;

/** chat.enabled off: the plugin's /msg steps aside and the vanilla /msg handles the command, at once. */
class VanillaMessageFallbackTest {
    /** Labels Core reports for the plugin's /msg: English name, free fixed aliases, a localized alias. */
    private static final Set<String> OWN = Set.of("msg", "w", "pm", "nachricht");

    private static ChatPlugin plugin(boolean masterOff, String msgOwnerConflict) {
        ChatPlugin plugin = mock(ChatPlugin.class);
        API core = mock(API.class, RETURNS_DEEP_STUBS);
        CommandRegistration msg = mock(CommandRegistration.class);
        when(msg.plugin()).thenReturn(plugin);
        when(msg.label()).thenReturn("msg");
        when(msg.labelConflict()).thenReturn(msgOwnerConflict);
        CommandRegistration reply = mock(CommandRegistration.class);
        when(reply.plugin()).thenReturn(plugin);
        when(reply.label()).thenReturn("reply");
        when(core.command().find(anyString())).thenAnswer(call -> {
            String label = call.getArgument(0);
            if (OWN.contains(label)) return Optional.of(msg);
            if (label.equals("reply") || label.equals("r")) return Optional.of(reply);
            return Optional.empty();
        });
        when(plugin.core()).thenReturn(core);
        when(plugin.off(Settings.Feature.CHAT)).thenReturn(masterOff);
        return plugin;
    }

    private static ChatPlugin plugin(boolean masterOff) { return plugin(masterOff, null); }

    /** Events are mocked: their constructors need a running server. */
    private static String typed(ChatPlugin plugin, String line) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        java.util.concurrent.atomic.AtomicReference<String> text = new java.util.concurrent.atomic.AtomicReference<>(line);
        when(event.getMessage()).thenAnswer(call -> text.get());
        doAnswer(call -> { text.set(call.getArgument(0)); return null; }).when(event).setMessage(anyString());
        new ChatListener(plugin).onPrivateMessageWhileOff(event);
        return text.get();
    }

    @Test void masterOffSendsTheOwnMsgLabelsToTheVanillaCommand() {
        ChatPlugin off = plugin(true);
        assertEquals("/minecraft:msg Bob hi there", typed(off, "/msg Bob hi there"));
        assertEquals("/minecraft:msg Bob hi", typed(off, "/MSG Bob hi"));
        assertEquals("/minecraft:msg Bob hi", typed(off, "/w Bob hi"));
        assertEquals("/minecraft:msg Bob hi", typed(off, "/pm Bob hi"));
        assertEquals("/minecraft:msg Bob hallo", typed(off, "/nachricht Bob hallo"));
        assertEquals("/minecraft:msg", typed(off, "/msg"));
    }

    @Test void masterOffLeavesOtherCommandsAndExplicitNamespacesAlone() {
        ChatPlugin off = plugin(true);
        assertEquals("/reply hi", typed(off, "/reply hi"), "vanilla has no /reply: the plugin's off message stays");
        assertEquals("/vystormchat:msg Bob hi", typed(off, "/vystormchat:msg Bob hi"));
        assertEquals("/tell Bob hi", typed(off, "/tell Bob hi"), "not ours: vanilla or another plugin already has it");
        assertEquals("/msgx Bob", typed(off, "/msgx Bob"));
    }

    @Test void anotherPluginsMsgStaysTheirs() {
        assertEquals("/msg Bob hi", typed(plugin(true, "Essentials"), "/msg Bob hi"));
    }

    @Test void masterOnKeepsThePluginsMsg() {
        assertEquals("/msg Bob hi", typed(plugin(false), "/msg Bob hi"));
    }

    @Test void consoleIsHandledToo() {
        ServerCommandEvent event = mock(ServerCommandEvent.class);
        java.util.concurrent.atomic.AtomicReference<String> text = new java.util.concurrent.atomic.AtomicReference<>("msg Bob hi");
        when(event.getCommand()).thenAnswer(call -> text.get());
        doAnswer(call -> { text.set(call.getArgument(0)); return null; }).when(event).setCommand(anyString());
        new ChatListener(plugin(true)).onConsolePrivateMessageWhileOff(event);
        assertEquals("minecraft:msg Bob hi", text.get());
    }
}
