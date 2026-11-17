package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** The chat commands as Core command trees with the real language files. */
class ChatCommandsTest {
    private final List<String> chat = new ArrayList<>();

    private static String read(String name) throws Exception {
        try (InputStream in = ChatCommandsTest.class.getResourceAsStream("/lang/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, CommandRegistration> trees() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("VystormChat");
        Lang lang = Lang.create("VystormChat", Map.of("en", read("en.yml"), "de", read("de.yml"), "zh_cn", read("zh_cn.yml")));
        Map<String, CommandRegistration> out = new LinkedHashMap<>();
        for (CommandSpec spec : Commands.specs(null)) out.put(spec.name(), CommandTesting.detached(plugin, lang, spec));
        return out;
    }

    private Player player(Locale locale, String... permissions) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenAnswer(i -> List.of(permissions).contains((String) i.getArgument(0)));
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(p).sendMessage(any(Component.class));
        return p;
    }

    @Test
    void givenAllCommands_whenListed_thenTheTenRootsWithTheirOldAliases() throws Exception {
        var trees = trees();
        assertEquals(List.of("vchat", "channel", "msg", "reply", "ignore", "socialspy", "mute", "unmute", "clearchat", "slowmode"), List.copyOf(trees.keySet()));
        assertEquals(List.of("tell", "w", "whisper", "m", "pm", "dm"), trees.get("msg").fixedAliases());
        for (CommandRegistration r : trees.values()) assertEquals(List.of(), r.conflicts(), r.label());
    }

    @Test
    void givenChannelCommand_whenCompleting_thenLocalizedSubcommands() throws Exception {
        CommandRegistration channel = trees().get("channel");
        // channel names come from the plugin (null here), so only the subcommands are offered without a running plugin
        assertTrue(channel.suggest(player(Locale.GERMANY, "vystormchat.use"), "kanal", new String[]{"l"}).contains("liste"));
        assertTrue(channel.suggest(player(Locale.SIMPLIFIED_CHINESE, "vystormchat.use"), "频道", new String[]{"列"}).contains("列表"));
        assertTrue(channel.suggest(player(Locale.US, "vystormchat.use"), "channel", new String[]{"le"}).contains("leave"));
    }

    @Test
    void givenVchat_whenCompletingWithPermissions_thenOnlyAllowedLocalizedSubs() throws Exception {
        CommandRegistration vchat = trees().get("vchat");
        assertEquals(List.of("menü", "report", "emojis", "hilfe"), vchat.suggest(player(Locale.GERMANY, "vystormchat.use"), "vchat", new String[]{""}));
        assertEquals(List.of("menu", "report", "reports", "mutes", "moderation", "emojis", "reload", "help"),
                vchat.suggest(player(Locale.US, "vystormchat.use", "vystormchat.mute", "vystormchat.moderate", "vystormchat.admin"), "vchat", new String[]{""}));
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsage() throws Exception {
        trees().get("msg").execute(player(Locale.GERMANY, "vystormchat.msg"), "nachricht", new String[0]);
        String all = String.join("\n", chat);
        assertTrue(all.contains("Es fehlt: SPIELER"), all);
        assertTrue(all.contains("Verwendung: /nachricht SPIELER NACHRICHT..."), all);
        chat.clear();
        trees().get("channel").execute(player(Locale.GERMANY, "vystormchat.use"), "kanal", new String[]{"hilfe"});
        assertTrue(String.join("\n", chat).contains("/kanal verlassen KANAL – Einen Kanal ausblenden"), chat.toString());
    }

    @Test
    void givenMissingPermission_whenRun_thenRefusedInPlayersLanguage() throws Exception {
        trees().get("mute").execute(player(Locale.GERMANY, "vystormchat.use"), "mute", new String[]{"Bob"});
        assertEquals("Dafür hast du keine Berechtigung.", chat.get(0));
    }

    @Test
    void occupiedMessageAliasesAreNotClaimedByCoreSpecs() {
        var map = mock(org.bukkit.command.CommandMap.class);
        var existing = mock(org.bukkit.command.Command.class);
        for (String alias : List.of("m", "dm", "tell", "w")) when(map.getCommand(alias)).thenReturn(existing);
        var msg = Commands.specs(null, alias -> map.getCommand(alias) != null).stream()
                .filter(spec -> spec.name().equals("msg")).findFirst().orElseThrow();
        assertEquals(List.of("whisper", "pm"), msg.aliases());
        assertEquals(List.of(), Commands.specs(null, alias -> true).stream()
                .filter(spec -> spec.name().equals("msg")).findFirst().orElseThrow().aliases());
        verify(map, never()).register(anyString(), any(org.bukkit.command.Command.class));
    }

    @Test
    void keywordCommandsAreLocalizedPersistentAndPermissionChecked() throws Exception {
        var plugin = mock(ChatPlugin.class);
        var profiles = mock(ProfileStore.class);
        var service = mock(ChatService.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("VystormChat");
        when(plugin.profiles()).thenReturn(profiles);
        when(plugin.service()).thenReturn(service);
        when(service.settings()).thenReturn(new Settings(new org.bukkit.configuration.file.YamlConfiguration(), java.util.logging.Logger.getLogger("test")));
        var lang = Lang.create("VystormChat", Map.of("en", read("en.yml"), "de", read("de.yml"), "zh_cn", read("zh_cn.yml")));
        var command = CommandTesting.detached(plugin, lang, Commands.specs(plugin, alias -> false).getFirst());
        Player allowed = player(Locale.GERMANY, "vystormchat.use", "vystormchat.keywords");
        Profile profile = new Profile(allowed.getUniqueId());
        when(profiles.get(allowed.getUniqueId())).thenReturn(profile);
        command.execute(allowed, "vchat", new String[]{"stichworte", "hinzufügen", "iron", "ingot"});
        assertEquals(List.of("iron ingot"), profile.keywords());
        command.execute(allowed, "vchat", new String[]{"stichworte", "pausieren"});
        assertFalse(profile.keywordAlerts());
        profile.takeDirty();
        chat.clear();
        command.execute(allowed, "vchat", new String[]{"stichworte", "testen", "Selling", "IRON", "INGOT"});
        assertTrue(String.join("\n", chat).contains("Vorschau: Treffer für iron ingot"));
        assertFalse(profile.keywordAlerts(), "preview must keep alerts paused");
        assertFalse(profile.takeDirty(), "preview must not write profile data");
        assertEquals(List.of("iron ingot"), profile.keywords());
        chat.clear();
        command.execute(allowed, "vchat", new String[]{"keywords", "test", "diamond"});
        assertTrue(String.join("\n", chat).contains("Vorschau: kein Treffer"));
        command.execute(allowed, "vchat", new String[]{"keywords", "resume"});
        assertTrue(profile.keywordAlerts());
        command.execute(allowed, "vchat", new String[]{"keywords"});
        assertTrue(String.join("\n", chat).contains("iron ingot"));
        command.execute(allowed, "vchat", new String[]{"关键词", "移除", "iron", "ingot"});
        assertTrue(profile.keywords().isEmpty());
        Player denied = player(Locale.US, "vystormchat.use");
        command.execute(denied, "vchat", new String[]{"keywords", "add", "gold"});
        command.execute(denied, "vchat", new String[]{"keywords", "test", "gold"});
        verify(profiles, never()).get(denied.getUniqueId());
        assertFalse(command.suggest(denied, "vchat", new String[]{""}).contains("keywords"));
    }

    @Test
    void replyLockIsLocalizedPermissionCheckedAndDispatched() throws Exception {
        var plugin = mock(ChatPlugin.class);
        var messages = mock(PrivateMessages.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("VystormChat");
        when(plugin.messages()).thenReturn(messages);
        var lang = Lang.create("VystormChat", Map.of("en", read("en.yml"), "de", read("de.yml"), "zh_cn", read("zh_cn.yml")));
        var spec = Commands.specs(plugin, alias -> false).getFirst();
        var command = CommandTesting.detached(plugin, lang, spec);
        Player allowed = player(Locale.GERMANY, "vystormchat.use", "vystormchat.msg");
        assertTrue(command.suggest(allowed, "vchat", new String[]{"antwort"}).contains("antwortsperre"));
        command.execute(allowed, "vchat", new String[]{"antwortsperre"});
        verify(messages).toggleReplyLock(allowed);
        Player denied = player(Locale.US, "vystormchat.use");
        command.execute(denied, "vchat", new String[]{"replylock"});
        verify(messages, never()).toggleReplyLock(denied);
        assertFalse(command.suggest(denied, "vchat", new String[]{""}).contains("replylock"));
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, "vystormchat.use", "vystormchat.msg");
        command.execute(chinese, "vchat", new String[]{"锁定回复"});
        verify(messages).toggleReplyLock(chinese);
    }
}
