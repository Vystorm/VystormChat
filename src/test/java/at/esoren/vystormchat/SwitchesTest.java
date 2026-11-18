package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every feature has a switch (1.17.0): off stops the feature and answers the one shared message, on behaves as before,
 * an old config.yml gains the new keys, and the placeholders resolve (also off the server thread) and are empty while
 * their feature is off.
 */
class SwitchesTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final List<String> PLACEHOLDERS = List.of("channel", "channel_name", "muted", "mute_remaining", "party_name", "city_name",
            "faction_name", "prefix", "suffix", "group", "ignored_count", "spy", "reply_partner");
    @TempDir Path dir;

    private static YamlConfiguration shippedConfig() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    private static Settings settings(Settings.Feature... off) {
        YamlConfiguration config = shippedConfig();
        for (Settings.Feature feature : off) config.set(feature.path, false);
        return new Settings(config, Logger.getLogger("test"));
    }

    // ------------------------------------------------------------------ config, descriptions, old configs

    @Test void givenPackagedConfig_whenRead_thenEverySwitchIsOnAndExplained() {
        YamlConfiguration config = shippedConfig();
        for (Settings.Feature feature : Settings.Feature.values()) {
            assertTrue(config.isBoolean(feature.path), feature.path);
            assertTrue(config.getBoolean(feature.path), feature.path);
            String parent = feature.path.contains(".") ? feature.path.substring(0, feature.path.lastIndexOf('.')) : feature.path;
            assertFalse(config.getComments(feature.path).isEmpty() && config.getComments(parent).isEmpty(), "comment for " + feature.path);
        }
        assertEquals(8, new Settings(config, Logger.getLogger("test")).channels.size(), "no switch is read as a channel or a name format");
    }

    @Test void givenEveryLanguage_whenRead_thenEverySwitchAndPlaceholderHasADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (Settings.Feature feature : Settings.Feature.values()) assertFalse(lang.getString(feature.description(), "").isBlank(), code + " " + feature.description());
            for (String key : PLACEHOLDERS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder." + key);
        }
        assertEquals("<red>This feature is switched off on this server.", YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/en.yml")).getString("feature-off"));
    }

    @Test void givenAConfigFrom1_16_whenRead_thenEveryFeatureStaysOn() {
        Settings old = new Settings(YamlConfiguration.loadConfiguration(new File("src/test/resources/config-1.16.yml")), Logger.getLogger("test"));
        for (Settings.Feature feature : Settings.Feature.values()) assertTrue(old.enabled(feature), feature.name());
    }

    @Test void givenAConfigFrom1_16_whenCoreMergesDefaults_thenEveryNewSwitchIsAddedAndOldValuesStay() throws Exception {
        Path data = Files.createDirectories(dir.resolve("VystormChat"));
        Files.writeString(data.resolve("config.yml"), Files.readString(Path.of("src/test/resources/config-1.16.yml")).replace("console-log: true", "console-log: false"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("VystormChat");
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getResource("config.yml")).thenAnswer(call -> Files.newInputStream(Path.of("src/main/resources/config.yml")));
        when(plugin.getConfig()).thenReturn(YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile()));

        assertTrue(ConfigEditor.mergeDefaults(plugin) >= 15);

        YamlConfiguration merged = YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile());
        for (Settings.Feature feature : Settings.Feature.values()) assertTrue(merged.isBoolean(feature.path), feature.path);
        Settings settings = new Settings(merged, Logger.getLogger("test"));
        assertEquals(Set.of(Settings.Feature.CONSOLE_LOG), settings.off, "an existing value is never changed");
        assertEquals(8, settings.channels.size());
        assertTrue(Files.readString(data.resolve("config.yml")).contains("The whole plugin"), "comments come along");
    }

    @Test void givenChatOff_whenSettingsAreRead_thenEveryFeatureCountsAsOff() {
        Settings settings = settings(Settings.Feature.CHAT);
        for (Settings.Feature feature : Settings.Feature.values()) assertFalse(settings.enabled(feature), feature.name());
        assertFalse(settings.mentionsEnabled || settings.showcaseEnabled || settings.emojisEnabled || settings.logEnabled || settings.mutesEnabled);
        assertTrue(settings.filter.empty());
    }

    // ------------------------------------------------------------------ pipeline

    private ChatPlugin plugin(Settings settings) throws Exception {
        ChatPlugin plugin = mock(ChatPlugin.class, CALLS_REAL_METHODS);
        ChatService service = new ChatService(plugin);
        service.configure(settings);
        field(plugin, "service", service);
        field(plugin, "lang", mock(Lang.class));
        return plugin;
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = ChatPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Lang lang(ChatPlugin plugin) throws Exception {
        Field field = ChatPlugin.class.getDeclaredField("lang");
        field.setAccessible(true);
        return (Lang) field.get(plugin);
    }

    @Test void givenChatOff_whenAMessageIsPrepared_thenItIsRefusedBeforeAnythingElse() throws Exception {
        ChatPlugin plugin = plugin(settings(Settings.Feature.CHAT));
        Player sender = mock(Player.class);
        ChatService.Outcome outcome = plugin.service().prepare(sender, settings().defaultChannel(), "hello", ChatService.Source.CHAT, List.of());
        assertEquals("feature-off", ((ChatService.Denied) outcome).key());
        verify(plugin, never()).mutes();
    }

    @Test void givenChatOff_whenThePlayerChats_thenTheServerChatIsLeftAlone() throws Exception {
        for (boolean on : List.of(false)) {
            ChatPlugin plugin = plugin(settings(Settings.Feature.CHAT));
            ChatService service = spy(plugin.service());
            field(plugin, "service", service);
            Player sender = mock(Player.class);
            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, sender, "hello", new HashSet<>(Set.of(sender)));
            new BukkitChatListener(plugin).chat(event);
            assertFalse(event.isCancelled(), "on: " + on);
            assertEquals("hello", event.getMessage());
            verify(service, never()).prepare(any(), any(), anyString(), any(), any());
        }
    }

    @Test void givenFamilyLinks_whenAChannelNeedsAnotherPlugin_thenItIsAFamilyLink() {
        assertTrue(ChatService.familyLink(TestChannels.one("scope: party")));
        assertTrue(ChatService.familyLink(TestChannels.one("scope: region")));
        assertTrue(ChatService.familyLink(TestChannels.one("scope: global\nforward-command: partychat")));
        assertFalse(ChatService.familyLink(TestChannels.one("scope: global\ncore-trade: true")), "the trade channel itself is an ordinary channel");
        assertFalse(ChatService.familyLink(TestChannels.one("scope: local")));
    }

    @Test void givenChannelChoiceOff_whenAPlayerHidChannels_thenTheChannelDefaultsApply() {
        Map<String, Channel> channels = TestChannels.parse("channels:\n  auto:\n    leavable: true\n    auto-join: true\n  optin:\n    leavable: true\n    auto-join: false\n");
        Channel auto = channels.get("auto"), optIn = channels.get("optin");
        Profile profile = new Profile(UUID.randomUUID());
        profile.listen(auto, false);
        profile.listen(optIn, true);
        assertFalse(ChatService.listens(settings(), profile, auto));
        assertTrue(ChatService.listens(settings(), profile, optIn));
        assertTrue(ChatService.listens(settings(Settings.Feature.CHANNEL_CHOICE), profile, auto));
        assertFalse(ChatService.listens(settings(Settings.Feature.CHANNEL_CHOICE), profile, optIn));
        assertFalse(profile.listening(auto), "the player's choice is kept");
    }

    @Test void givenLinksOff_whenAPlayerWithoutPermissionPostsALink_thenItIsNeitherCensoredNorClickable() throws Exception {
        for (boolean on : List.of(true, false)) {
            Settings settings = on ? settings(Settings.Feature.SPAM, Settings.Feature.FILTER) : settings(Settings.Feature.SPAM, Settings.Feature.FILTER, Settings.Feature.LINKS);
            ChatPlugin plugin = plugin(settings);
            Player sender = mock(Player.class);
            when(sender.getName()).thenReturn("Alex");
            Object screened = plugin.service().screen(sender, "visit example.com now", EnumSet.noneOf(PlayerText.Style.class), "global", "Alex");
            assertEquals(on ? "visit [link removed] now" : "visit example.com now", screened, "links " + on);

            ChatService service = mock(ChatService.class);
            when(service.settings()).thenReturn(settings);
            Lang lang = mock(Lang.class);
            when(lang.component(anyString(), anyString(), any(Object[].class))).thenReturn(Component.text("hover"));
            when(service.lang()).thenReturn(lang);
            Delivery delivery = new Delivery(service, sender, null, TestChannels.one("scope: global"), "see example.com", "see example.com",
                    Set.of(), true, Showcase.Snapshot.NONE, List.of(), Map.of());
            boolean clickable = delivery.body("en", null).toString().contains("open_url");
            assertEquals(on, clickable, "clickable " + on);
        }
    }

    @Test void givenHoverCardsOrNameFormatsOff_whenANameIsShown_thenItIsPlain() {
        PlayerState state = new PlayerState(UUID.randomUUID(), "Alex", Component.text("Alex"), UUID.randomUUID(), "world", 0, 0, 0, List.of(),
                null, null, null, null, null, null, "admin", "[A] ", "", 0);
        Player sender = mock(Player.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        for (Settings.Feature off : List.of(Settings.Feature.HOVER_CARDS, Settings.Feature.NAME_FORMATS)) {
            ChatService service = mock(ChatService.class);
            when(service.settings()).thenReturn(settings(off));
            when(service.hoverCard(anyString(), any())).thenReturn(Component.text("card"));
            Delivery delivery = new Delivery(service, sender, state, TestChannels.one("scope: global"), "hi", "hi", Set.of(), false, Showcase.Snapshot.NONE, List.of(), Map.of());
            Component name = delivery.name("en");
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(name);
            if (off == Settings.Feature.HOVER_CARDS) assertNull(name.hoverEvent(), "no hover card");
            else assertEquals("Alex", plain, "plain name without prefix");
        }
    }

    @Test void givenSlowModeOff_whenASlowModeWasSet_thenItDoesNotApply() throws Exception {
        ChatPlugin on = plugin(settings());
        on.service().slowMode(null, 30);
        assertEquals(30, on.service().slowMode("global"));
        ChatPlugin off = plugin(settings(Settings.Feature.SLOW_MODE));
        off.service().slowMode(null, 30);
        assertEquals(0, off.service().slowMode("global"));
    }

    @Test void givenRelayOff_whenAChannelIsListed_thenNothingStaysInTheServerChat() {
        Channel global = settings().channel("global");
        assertTrue(settings().relayed(global));
        assertFalse(settings(Settings.Feature.RELAY).relayed(global));
    }

    @Test void givenPaidChannelsOrQuickPrefixesOff_whenRead_thenTheSwitchesAreOff() {
        assertFalse(settings(Settings.Feature.PAID_CHANNELS).enabled(Settings.Feature.PAID_CHANNELS));
        assertFalse(settings(Settings.Feature.QUICK_PREFIXES).enabled(Settings.Feature.QUICK_PREFIXES));
        assertTrue(settings(Settings.Feature.QUICK_PREFIXES).enabled(Settings.Feature.PAID_CHANNELS));
    }

    // ------------------------------------------------------------------ commands

    @Test void givenAModerationOrPlayerFeatureOff_whenItsActionRuns_thenOffMessageAndNothingElse() throws Exception {
        Map<Settings.Feature, java.util.function.BiConsumer<ChatPlugin, Player>> actions = new LinkedHashMap<>();
        actions.put(Settings.Feature.CLEAR_CHAT, ChatPlugin::clearChat);
        actions.put(Settings.Feature.SLOW_MODE, (plugin, player) -> plugin.slowMode(player, null, 10));
        actions.put(Settings.Feature.SOCIAL_SPY, ChatPlugin::toggleSpy);
        actions.put(Settings.Feature.CHAT_MENU, ChatPlugin::openSettings);
        actions.put(Settings.Feature.CHANNEL_CHOICE, (plugin, player) -> plugin.listen(player, settings().channel("global"), false));
        for (var entry : actions.entrySet()) {
            ChatPlugin plugin = plugin(settings(entry.getKey()));
            Player player = mock(Player.class);
            entry.getValue().accept(plugin, player);
            verify(lang(plugin)).send(player, "feature-off");
            verify(player, never()).hasPermission(anyString());
        }
    }

    @Test void givenSwitchesOff_whenTheCommandTreesAreBuilt_thenTheirCommandsAreHidden() throws Exception {
        ChatPlugin plugin = plugin(settings());
        Map<String, CommandSpec> trees = byName(Commands.specs(plugin, alias -> false));
        assertTrue(trees.values().stream().noneMatch(CommandSpec::hidden));
        assertTrue(trees.values().stream().allMatch(root -> root.visibleTo(null)));
        assertTrue(trees.get("vchat").children().values().stream().allMatch(sub -> sub.visibleTo(null)));
        // the same built trees follow the switches: no second registration needed
        plugin.service().configure(settings(Settings.Feature.PRIVATE_MESSAGES, Settings.Feature.CHANNEL_CHOICE, Settings.Feature.REPORTS));
        assertFalse(trees.get("msg").visibleTo(null) || trees.get("reply").visibleTo(null));
        assertFalse(trees.get("channel").children().get("join").visibleTo(null));
        assertFalse(trees.get("vchat").children().get("report").visibleTo(null));
        assertTrue(trees.get("mute").visibleTo(null));
        plugin.service().configure(settings(Settings.Feature.CHAT));
        for (CommandSpec root : trees.values()) assertFalse(root.visibleTo(null), root.name());
        assertTrue(trees.get("vchat").children().get("reload").visibleTo(null), "reload stays");
        plugin.service().configure(settings());
        assertTrue(trees.values().stream().allMatch(root -> root.visibleTo(null)));
        assertTrue(trees.get("channel").children().get("join").visibleTo(null));
    }

    @Test void givenASwitchChanged_whenReloaded_thenTheCommandListIsSentAgainAndNothingIsRegistered() throws Exception {
        ChatPlugin plugin = plugin(settings());
        API core = mock(API.class, RETURNS_DEEP_STUBS);
        var commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(core.command()).thenReturn(commands);
        field(plugin, "core", core);
        field(plugin, "aliasCommands", new HashSet<String>());
        doReturn(Logger.getLogger("test")).when(plugin).getLogger();
        doReturn("VystormChat").when(plugin).getName();
        doAnswer(call -> { plugin.service().configure(settings(Settings.Feature.REPORTS, Settings.Feature.CHAT_MENU)); return null; }).when(plugin).load();
        plugin.reload();
        verify(commands).refresh();
        verify(commands, never()).register(any(), any(), any());
        assertFalse(Commands.specs(plugin, alias -> false).stream().filter(s -> s.name().equals("vchat")).findFirst().orElseThrow()
                .children().get("report").visibleTo(null));
    }

    private static Map<String, CommandSpec> byName(List<CommandSpec> specs) {
        Map<String, CommandSpec> out = new HashMap<>();
        for (CommandSpec spec : specs) out.put(spec.name(), spec);
        return out;
    }

    @Test void givenTutorialOff_whenThePluginStarts_thenNoTutorialIsRegistered() throws Exception {
        for (boolean enabled : List.of(true, false)) {
            ChatPlugin plugin = mock(ChatPlugin.class);
            API core = mock(API.class);
            field(plugin, "core", core);
            YamlConfiguration config = new YamlConfiguration();
            config.set("tutorial.enabled", enabled);
            when(plugin.getConfig()).thenReturn(config);
            doCallRealMethod().when(plugin).registerTutorial();
            plugin.registerTutorial();
            verify(core, times(enabled ? 1 : 0)).registerTutorial(plugin);
        }
    }

    // ------------------------------------------------------------------ placeholders

    @Test void givenRegisteredValues_whenResolvedOffTheServerThread_thenTheyAnswerAndAreEmptyWhenTheirFeatureIsOff() {
        OfflinePlayer alex = PlaceholderTesting.viewer("Alex");
        Profile profile = new Profile(alex.getUniqueId());
        profile.focus("local");
        profile.ignored.add(UUID.randomUUID());
        PlayerState state = new PlayerState(alex.getUniqueId(), "Alex", Component.text("Alex"), UUID.randomUUID(), "world", 0, 0, 0, List.of(),
                "p", "Builders", null, null, null, null, "vip", "[VIP] ", "", 0);
        Settings[] current = {settings()};
        PlaceholderTesting testing = PlaceholderTesting.detached();
        ChatPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("VystormChat"), new ChatPlaceholders.Source() {
            @Override public Settings settings() { return current[0]; }
            @Override public Profile profile(UUID player) { return player.equals(alex.getUniqueId()) ? profile : null; }
            @Override public PlayerState state(UUID player) { return player.equals(alex.getUniqueId()) ? state : null; }
            @Override public MuteStore.Mute mute(UUID player, long now) { return new MuteStore.Mute(player, "Alex", now, 0, "", "Mod"); }
            @Override public UUID partner(UUID player) { return PrivateMessages.CONSOLE; }
        }, key -> key);
        String text = "%vystorm_chat_channel%|{chat:muted}|{chat:mute_remaining}|{chat:party_name}|{chat:group}|{chat:ignored_count}|{chat:spy}|{chat:reply_partner}";
        assertEquals("local|true|permanent|Builders|vip|1|false|Console", testing.resolveOffThread(alex, text));
        current[0] = settings(Settings.Feature.MUTES, Settings.Feature.FAMILY_LINKS, Settings.Feature.IGNORE, Settings.Feature.PRIVATE_MESSAGES);
        assertEquals("local||||vip||false|", testing.resolve(alex, text));
        current[0] = settings(Settings.Feature.CHAT);
        assertEquals("|||||||", testing.resolve(alex, text));
    }

}
