package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.schedule.TaskGroup;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Discord bridge (1.18.0) with a local fake Discord: which channels pass, what a webhook post contains, events,
 * reports, the bot's incoming messages (loops, mapping, filters, permissions), switches that need other switches,
 * the token sources, and that neither the token nor a webhook URL reaches the log, the status or the config editor.
 */
class DiscordBridgeTest {
    @TempDir Path folder;
    private DiscordFakes.Server server;
    private DiscordFakes.Host host;
    private final DiscordFakes.Gateway gateway = new DiscordFakes.Gateway();
    private final AtomicLong clock = new AtomicLong(10_000_000L);
    private DiscordBridge bridge;
    private static final UUID ALEX = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @BeforeEach void start() throws Exception {
        server = new DiscordFakes.Server();
        host = new DiscordFakes.Host(folder);
        bridge = DiscordFakes.bridge(host, server, gateway, clock);
    }

    @AfterEach void stop() {
        server.close();
        String log = host.log.all();
        assertFalse(log.contains(DiscordFakes.TOKEN), "token in the log: " + log);
        assertFalse(log.contains("fakewebhooktoken"), "webhook URL in the log: " + log);
        DiscordBridge.Status status = bridge.status();
        assertFalse(status.toString().contains(DiscordFakes.TOKEN) || status.toString().contains("fakewebhooktoken"), status.toString());
    }

    private Settings settings(Object... changes) { return DiscordFakes.settings(server.webhook("global"), changes); }

    private JsonObject lastPost() { return server.requests.get(server.requests.size() - 1).json(); }

    // ------------------------------------------------------------------ Minecraft -> Discord

    @Test void givenABridgedChannel_whenAPlayerWrites_thenTheWebhookGetsNameHeadAndNoPings() {
        bridge.configure(settings());
        Settings s = settings();
        bridge.chat(s.channel("global"), ALEX, "Alex_The_Great", "@everyone look **here** <@123>");
        assertEquals(1, server.requests.size());
        assertEquals("/api/webhooks/1234567/global-fakewebhooktoken", server.requests.get(0).path());
        JsonObject post = lastPost();
        assertEquals("Alex_The_Great", post.get("username").getAsString());
        assertEquals("https://mc-heads.net/avatar/" + ALEX + "/128", post.get("avatar_url").getAsString());
        assertEquals(0, post.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size(), "nothing pings");
        String content = post.get("content").getAsString();
        assertEquals("@​everyone look \\*\\*here\\*\\* <​@123>", content);
    }

    @Test void givenUserMentionsOn_whenAPlayerWrites_thenOnlyUsersMayPing() {
        Settings s = settings("discord.user-mentions.enabled", true);
        bridge.configure(s);
        bridge.chat(s.channel("global"), ALEX, "Alex", "hi <@123> @here");
        JsonObject post = lastPost();
        assertEquals("users", post.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").get(0).getAsString());
        assertEquals(1, post.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        assertTrue(post.get("content").getAsString().contains("<@123>"));
        assertFalse(post.get("content").getAsString().contains("@here"));
    }

    @Test void givenALongMessage_whenSent_thenItIsCutToDiscordsLimit() {
        Settings s = settings();
        bridge.configure(s);
        bridge.chat(s.channel("global"), ALEX, "Alex", "y".repeat(2500));
        assertEquals(DiscordText.MAX_CONTENT, lastPost().get("content").getAsString().length());
    }

    @Test void givenChannelsThatMustNotLeave_whenPlayersWrite_thenNothingIsPosted() {
        Settings s = settings("discord.channels.local.webhook", server.webhook("local"),
                "discord.channels.staff.webhook", server.webhook("staff"),
                "discord.channels.party.webhook", server.webhook("party"));
        bridge.configure(s);
        bridge.chat(s.channel("local"), ALEX, "Alex", "near");
        bridge.chat(s.channel("staff"), ALEX, "Alex", "secret plans");
        bridge.chat(s.channel("party"), ALEX, "Alex", "party");
        bridge.chat(s.channel("world"), ALEX, "Alex", "not mapped");
        assertEquals(0, server.requests.size());
        String log = host.log.all();
        assertTrue(log.contains("discord.channels.local: only channels with scope global"), log);
        assertTrue(log.contains("discord.channels.staff: only some players may read this channel"), log);
        assertTrue(log.contains("discord.channels.party:"), log);
    }

    @Test void givenARestrictedChannelWithAllowRestricted_whenWritten_thenItIsPosted() {
        Settings s = settings("discord.channels.staff.webhook", server.webhook("staff"), "discord.channels.staff.allow-restricted", true,
                "discord.channels.trade.webhook", server.webhook("trade"));
        bridge.configure(s);
        bridge.chat(s.channel("staff"), ALEX, "Alex", "for staff discord");
        bridge.chat(s.channel("trade"), ALEX, "Alex", "selling iron");
        assertEquals(2, server.requests.size(), "trade: its read permission is everyone's by default");
    }

    @Test void givenTheBridgeOff_whenAPlayerWrites_thenNothingLeaves() {
        Settings s = settings("discord.enabled", false);
        bridge.configure(s);
        bridge.chat(s.channel("global"), ALEX, "Alex", "hello");
        bridge.event(Settings.Feature.DISCORD_JOIN, "discord.events.join", "player", "Alex");
        bridge.report("Alex", "Bob", "spam");
        assertEquals(0, server.requests.size());
    }

    @Test void givenTheChatPipeline_whenAMutedPlayerOrAFilteredWordComes_thenDiscordGetsNothingOrTheCensoredText() throws Exception {
        Settings s = settings();
        ChatPlugin plugin = mock(ChatPlugin.class, CALLS_REAL_METHODS);
        ChatService service = new ChatService(plugin);
        service.configure(s);
        field(plugin, "service", service);
        field(plugin, "lang", mock(Lang.class));
        field(plugin, "chatLog", mock(ChatLog.class));
        field(plugin, "profiles", mock(ProfileStore.class));
        field(plugin, "hooks", mock(Hooks.class));
        TaskGroup tasks = mock(TaskGroup.class);
        field(plugin, "tasks", tasks);
        field(plugin, "discord", bridge);
        bridge.configure(s);
        MuteStore mutes = mock(MuteStore.class);
        field(plugin, "mutes", mutes);
        States states = mock(States.class);
        field(plugin, "states", states);
        Player sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(ALEX);
        when(sender.getName()).thenReturn("Alex");
        when(sender.hasPermission(anyString())).thenAnswer(call -> !call.<String>getArgument(0).startsWith("vystormchat.bypass"));
        when(states.get(ALEX)).thenReturn(PlayerState.basic(ALEX, "Alex", UUID.randomUUID(), "world", 0, 0, 0));
        when(mutes.active(any(UUID.class), any(Long.class))).thenReturn(new MuteStore.Mute(ALEX, "Alex", 0, 0, "", "Mod"));

        ChatService.Outcome muted = service.prepare(sender, s.channel("global"), "I am muted", ChatService.Source.CHAT, List.of());
        assertInstanceOf(ChatService.Denied.class, muted);
        assertEquals(0, server.requests.size(), "a muted player has no delivery, so nothing reaches Discord");

        when(mutes.active(any(UUID.class), any(Long.class))).thenReturn(null);
        ChatService.Outcome outcome = service.prepare(sender, s.channel("global"), "what the shit", ChatService.Source.CHAT, List.of());
        service.after(((ChatService.Deliver) outcome).delivery());
        assertEquals(1, server.requests.size());
        assertEquals("what the \\*\\*\\*\\*", lastPost().get("content").getAsString(), "the censored text, escaped");

        Settings block = settings("moderation.filter.action", "block");
        service.configure(block);
        bridge.configure(block);
        ChatService.Outcome blocked = service.prepare(sender, block.channel("global"), "what the shit again", ChatService.Source.CHAT, List.of());
        assertInstanceOf(ChatService.Denied.class, blocked);
        assertEquals(1, server.requests.size(), "a blocked message is not sent");
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = ChatPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ------------------------------------------------------------------ events, reports, test line

    @Test void givenEventSwitches_whenEventsHappen_thenOnlySwitchedOnOnesArePostedToTheEventsWebhook() {
        Settings s = settings("discord.events.join.enabled", true, "discord.events.webhook", server.webhook("events"));
        bridge.configure(s);
        bridge.event(Settings.Feature.DISCORD_JOIN, "discord.events.join", "player", DiscordText.escapeMarkdown("Mr_Cool"));
        bridge.event(Settings.Feature.DISCORD_LEAVE, "discord.events.leave", "player", "Mr_Cool");
        assertEquals(1, server.requests.size());
        assertEquals("/api/webhooks/1234567/events-fakewebhooktoken", server.requests.get(0).path());
        assertEquals("**Mr\\_Cool** joined the server", lastPost().get("content").getAsString());
        assertFalse(lastPost().has("username"), "the webhook's own name");
    }

    @Test void givenNoEventsWebhook_whenAnEventHappens_thenTheDefaultChannelsWebhookIsUsed() {
        Settings s = settings("discord.events.server-start.enabled", true);
        bridge.configure(s);
        bridge.started();
        bridge.started();
        assertEquals(1, server.requests.size(), "once");
        assertEquals("/api/webhooks/1234567/global-fakewebhooktoken", server.requests.get(0).path());
        assertEquals("The server has started", lastPost().get("content").getAsString());
    }

    @Test void givenServerStop_whenClosing_thenTheLineIsSentBeforeEverythingCloses() {
        Settings s = settings("discord.events.server-stop.enabled", true);
        bridge.configure(s);
        bridge.close();
        assertEquals(1, server.requests.size());
        assertEquals("The server has stopped", lastPost().get("content").getAsString());
        bridge.chat(s.channel("global"), ALEX, "Alex", "after close");
        assertEquals(1, server.requests.size(), "closed");
    }

    @Test void givenReportsToDiscord_whenAReportIsSaved_thenTheStaffWebhookGetsIt() {
        Settings s = settings("discord.reports.enabled", true, "discord.reports.webhook", server.webhook("staff"));
        bridge.configure(s);
        bridge.report("Alex", "Bob_", "spams @everyone");
        assertEquals("/api/webhooks/1234567/staff-fakewebhooktoken", server.requests.get(0).path());
        assertEquals("**Report** from Alex about **Bob\\_**: spams @​everyone", lastPost().get("content").getAsString());
        Settings reportsOff = settings("discord.reports.enabled", true, "discord.reports.webhook", server.webhook("staff"), "reports.enabled", false);
        bridge.configure(reportsOff);
        bridge.report("Alex", "Bob", "x");
        assertEquals(1, server.requests.size(), "discord.reports.enabled needs reports.enabled");
    }

    @Test void givenTestCommand_whenAChannelIsBridgedOrNot_thenQueuedOrTheReason() {
        Settings s = settings();
        bridge.configure(s);
        assertNull(bridge.test("global", "Admin"));
        assertEquals("Test message from Minecraft (Global, sent by Admin)", lastPost().get("content").getAsString());
        assertEquals("discord.test-unmapped", bridge.test("local", "Admin"));
        assertEquals("channel-unknown", bridge.test("nope", "Admin"));
    }

    // ------------------------------------------------------------------ switches that need other switches

    @Test void givenParentSwitchesOff_whenAChildIsOn_thenItIsInactiveAndTheMissingSwitchIsNamed() {
        Settings s = DiscordFakes.settings("", "discord.enabled", false, "discord.bot.enabled", true, "discord.bot.player-count.enabled", true);
        assertTrue(s.enabled(Settings.Feature.DISCORD_BOT));
        assertFalse(s.active(Settings.Feature.DISCORD_BOT));
        assertEquals(Settings.Feature.DISCORD, s.missing(Settings.Feature.DISCORD_BOT));
        assertEquals(Settings.Feature.DISCORD, s.missing(Settings.Feature.DISCORD_PLAYER_COUNT), "the deepest missing switch first");
        Settings botOff = settings("discord.bot.player-count.enabled", true);
        assertEquals(Settings.Feature.DISCORD_BOT, botOff.missing(Settings.Feature.DISCORD_PLAYER_COUNT));
        assertEquals(Settings.Feature.REPORTS, settings("reports.enabled", false).missing(Settings.Feature.DISCORD_REPORTS));
        bridge.configure(s);
        assertTrue(host.log.all().contains("discord.bot.enabled is on but needs discord.enabled"), host.log.all());
        assertTrue(gateway.connections.isEmpty(), "no bot without the bridge switch");
        for (Settings.Feature feature : Settings.Feature.values())
            if (feature.name().startsWith("DISCORD")) assertFalse(feature.defaultOn, feature + " ships off");
    }

    @Test void givenEveryLanguage_whenRead_thenTheDiscordTextsAndConfigRowsExist() {
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (String[] row : ChatPlugin.DISCORD_ROWS) {
                assertFalse(lang.getString("discord.config." + row[1], "").isBlank(), code + " " + row[1]);
                assertFalse(lang.getString("discord.config." + row[1] + "-desc", "").isBlank(), code + " " + row[1] + "-desc");
            }
            for (String key : List.of("events.join", "events.leave", "events.first-join", "events.death", "events.death-plain", "events.advancement",
                    "events.server-start", "events.server-stop", "file", "reply", "presence", "report", "test-line", "status-header"))
                assertFalse(lang.getString("discord." + key, "").isBlank(), code + " discord." + key);
            assertFalse(lang.getString("commands.vchat.sub.discord.description", "").isBlank(), code);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (String[] row : ChatPlugin.DISCORD_ROWS) assertTrue(config.contains(row[0]), row[0]);
    }

    // ------------------------------------------------------------------ secrets

    @Test void givenSecrets_whenTheConfigEditorAsks_thenTokenAndWebhooksAreHiddenAndTheOtherRowsShown() {
        for (String path : List.of("discord.bot-token", "discord.channels.global.webhook", "discord.events.webhook", "discord.reports.webhook"))
            assertTrue(ConfigEditor.secret(path), path + " must be secret in Core's editor");
        for (String[] row : ChatPlugin.DISCORD_ROWS) assertFalse(ConfigEditor.secret(row[0]), row[0] + " is a normal row");
        assertFalse(ConfigEditor.secret("discord.channels.global.discord-id"));
        Settings s = settings("discord.bot-token", DiscordFakes.TOKEN, "discord.events.webhook", server.webhook("events"));
        assertFalse(s.discord.toString().contains(DiscordFakes.TOKEN) || s.discord.toString().contains("fakewebhooktoken"), s.discord.toString());
    }

    @Test void givenTokenSources_whenRead_thenEnvironmentBeforeFileBeforeConfig() throws Exception {
        Settings s = settings("discord.bot.enabled", true, "discord.bot-token", "Bot " + DiscordFakes.TOKEN + "-config");
        bridge.configure(s);
        assertEquals(DiscordBridge.TokenSource.CONFIG, bridge.tokenSource());
        Files.writeString(folder.resolve(DiscordBridge.TOKEN_FILE), "# the bot token\n" + DiscordFakes.TOKEN + "-file\n");
        bridge.configure(s);
        assertEquals(DiscordBridge.TokenSource.FILE, bridge.tokenSource());
        host.env.put(DiscordBridge.TOKEN_ENV, DiscordFakes.TOKEN);
        bridge.configure(s);
        assertEquals(DiscordBridge.TokenSource.ENVIRONMENT, bridge.tokenSource());
        gateway.last().hello(40_000);
        assertEquals(DiscordFakes.TOKEN, gateway.last().lastSent().getAsJsonObject("d").get("token").getAsString(), "the environment token, without \"Bot \"");
        assertEquals(3, gateway.connections.size(), "a new token reconnects");
        assertEquals("environment", bridge.status().token().name().toLowerCase(java.util.Locale.ROOT).substring(0, 11));
    }

    @Test void givenTheBotOnWithoutAToken_whenConfigured_thenTheStatusSaysSoAndNothingConnects() {
        bridge.configure(settings("discord.bot.enabled", true));
        assertTrue(gateway.connections.isEmpty());
        assertEquals("no token", bridge.status().bot());
        assertTrue(host.log.all().contains(DiscordBridge.TOKEN_FILE));
    }

    @Test void givenAFailingWebhook_whenStatusIsShown_thenTheErrorNamesTheChannelButNotTheUrl() {
        Settings s = settings();
        bridge.configure(s);
        server.reply(401, Map.of(), "{\"message\":\"Invalid Webhook Token\"}");
        bridge.chat(s.channel("global"), ALEX, "Alex", "hello");
        DiscordBridge.Status status = bridge.status();
        assertTrue(status.error().contains("webhook of channel global"), status.error());
        assertFalse(status.error().contains("http"), status.error());
        assertTrue(status.webhooks().startsWith("global"), status.webhooks());
    }

    @Test void givenDiscordSrv_whenTheBridgeIsOn_thenOneWarningAboutDoublePosts() {
        host.plugins.add("DiscordSRV");
        bridge.configure(settings());
        bridge.configure(settings());
        assertEquals(1, host.log.lines.stream().filter(line -> line.contains("DiscordSRV")).count(), host.log.all());
        assertTrue(host.log.all().contains("relay.only-channels"));
    }

    // ------------------------------------------------------------------ Discord -> Minecraft

    private static JsonObject message(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private static String plain(net.kyori.adventure.text.Component line) { return PlainTextComponentSerializer.plainText().serialize(line); }

    private static final String BOB = "\"author\":{\"id\":\"424242\",\"username\":\"bob\",\"global_name\":\"Bob\"}";

    @Test void givenTheBot_whenAMappedChannelGetsAMessage_thenItShowsInTheChatChannelWithTheFormat() {
        Settings s = settings("discord.bot.enabled", true, "discord.bot-token", DiscordFakes.TOKEN);
        bridge.configure(s);
        DiscordFakes.Gateway.Connection connection = gateway.last();
        connection.hello(40_000);
        connection.ready(1, "s", "wss://resume.invalid", "999");
        connection.dispatch("MESSAGE_CREATE", 2, "{\"type\":0,\"channel_id\":\"111111\"," + BOB + ",\"member\":{\"nick\":\"Bobby\"},\"content\":\"hello <red>there\"}");
        assertEquals(1, host.shown.size());
        DiscordFakes.Host.Shown shown = host.shown.get(0);
        assertEquals("global", shown.channel().id());
        assertEquals("[Discord] Bobby: hello <red>there", plain(shown.line()), "Discord text is never parsed as tags");
        assertEquals("[Discord] Bobby: hello <red>there", shown.log());
    }

    @Test void givenOwnWebhookPostsAndBots_whenTheyArrive_thenTheyAreIgnored() {
        Settings s = settings("discord.bot.enabled", true);
        String self = "999";
        assertNull(bridge.accept(s, message("{\"channel_id\":\"111111\",\"webhook_id\":\"1234567\"," + BOB + ",\"content\":\"echo of our own post\"}"), self, 0));
        assertNull(bridge.accept(s, message("{\"channel_id\":\"111111\",\"author\":{\"id\":\"7\",\"username\":\"otherbot\",\"bot\":true},\"content\":\"bot\"}"), self, 0));
        assertNull(bridge.accept(s, message("{\"channel_id\":\"111111\",\"author\":{\"id\":\"999\",\"username\":\"Bridge\"},\"content\":\"me\"}"), self, 0));
        assertNull(bridge.accept(s, message("{\"channel_id\":\"111111\",\"type\":7," + BOB + ",\"content\":\"\"}"), self, 0), "join notices");
        assertNotNull(bridge.accept(s, message("{\"channel_id\":\"111111\"," + BOB + ",\"content\":\"a person\"}"), self, 0));
    }

    @Test void givenMapping_whenMessagesComeFromOtherChannelsOrBlockedUsers_thenNothingShows() {
        Settings s = settings("discord.bot.enabled", true, "discord.blocked-users", List.of("424242"));
        assertNull(bridge.accept(s, message("{\"channel_id\":\"222222\",\"author\":{\"id\":\"5\",\"username\":\"x\"},\"content\":\"elsewhere\"}"), "999", 0));
        assertNull(bridge.accept(s, message("{\"channel_id\":\"111111\"," + BOB + ",\"content\":\"blocked\"}"), "999", 0));
        assertNull(bridge.accept(settings("discord.bot.enabled", false), message("{\"channel_id\":\"111111\",\"author\":{\"id\":\"5\",\"username\":\"x\"},\"content\":\"bot off\"}"), "999", 0),
                "incoming messages need discord.bot.enabled");
        Settings staff = settings("discord.bot.enabled", true, "discord.channels.global.discord-id", "", "discord.channels.staff.discord-id", "333333");
        assertNull(bridge.accept(staff, message("{\"channel_id\":\"333333\",\"author\":{\"id\":\"5\",\"username\":\"x\"},\"content\":\"into staff\"}"), "999", 0),
                "a restricted channel needs allow-restricted");
    }

    @Test void givenFiltersAndLinks_whenADiscordMessageComes_thenTheSameRulesAsForPlayersApply() {
        Settings s = settings("discord.bot.enabled", true);
        assertEquals("what the ****", bridge.accept(s, message("{\"channel_id\":\"111111\"," + BOB + ",\"content\":\"what the shit\"}"), "999", 0).text());
        assertEquals("visit [link removed]", bridge.accept(s, message("{\"channel_id\":\"111111\",\"author\":{\"id\":\"43\",\"username\":\"c\"},\"content\":\"visit example.com\"}"), "999", 0).text());
        Settings block = settings("discord.bot.enabled", true, "moderation.filter.action", "block");
        assertNull(bridge.accept(block, message("{\"channel_id\":\"111111\",\"author\":{\"id\":\"44\",\"username\":\"d\"},\"content\":\"shit\"}"), "999", 0));
    }

    @Test void givenTheSameMessageTwice_whenSentQuickly_thenAntiSpamDropsTheSecond() {
        Settings s = settings("discord.bot.enabled", true);
        bridge.configure(s);
        String json = "{\"channel_id\":\"111111\"," + BOB + ",\"content\":\"buy my stuff\"}";
        assertNotNull(bridge.accept(s, message(json), "999", 1000));
        assertNull(bridge.accept(s, message(json), "999", 2000));
    }

    @Test void givenAttachmentsRepliesAndMentions_whenShown_thenPlainNotesAndNamesAndLinksOnlyWhenAllowed() {
        Settings s = settings("discord.bot.enabled", true);
        String json = "{\"type\":19,\"channel_id\":\"111111\"," + BOB + ",\"content\":\"<@77> look **this**\","
                + "\"mentions\":[{\"id\":\"77\",\"username\":\"carl\",\"global_name\":\"Carl\"}],"
                + "\"referenced_message\":{\"author\":{\"id\":\"78\",\"username\":\"dana\"},\"content\":\"?\"},"
                + "\"attachments\":[{\"id\":\"1\",\"filename\":\"a.png\",\"url\":\"https://cdn.discordapp.com/attachments/1/2/a.png\"}]}";
        assertEquals("(reply to dana) @Carl look this [file]", bridge.accept(s, message(json), "999", 0).text());
        Settings allowed = settings("discord.bot.enabled", true, "links.allowed-domains", List.of("cdn.discordapp.com"));
        assertEquals("(reply to dana) @Carl look this [file] https://cdn.discordapp.com/attachments/1/2/a.png",
                new DiscordBridge(host, DiscordRest.jdk(java.time.Duration.ofSeconds(1)), gateway, clock::get, null, null).accept(allowed, message(json), "999", 0).text());
    }

    @Test void givenPlayers_whenADiscordLineIsShown_thenReadPermissionWorldAndHiddenChannelsCount() {
        Settings s = settings();
        Channel global = s.channel("global"), staff = s.channel("staff");
        assertTrue(DiscordEvents.receives(s, global, perm -> false, "world", null));
        assertFalse(DiscordEvents.receives(s, staff, perm -> false, "world", null), "staff needs its read permission");
        assertTrue(DiscordEvents.receives(s, staff, "vystormchat.channel.staff"::equals, "world", null));
        Profile hidden = new Profile(ALEX);
        hidden.listen(global, false);
        assertFalse(DiscordEvents.receives(s, global, perm -> true, "world", hidden), "a player who hid the channel");
        Channel nether = TestChannels.one("scope: global\nworlds: [world_nether]");
        assertFalse(DiscordEvents.receives(s, nether, perm -> true, "world", null));
    }

    // ------------------------------------------------------------------ bot status

    @Test void givenPlayerCount_whenTheBotIsReady_thenTheStatusShowsItAndUpdatesAtMostEvery30Seconds() {
        Settings s = settings("discord.bot.enabled", true, "discord.bot-token", DiscordFakes.TOKEN, "discord.bot.player-count.enabled", true);
        bridge.configure(s);
        DiscordFakes.Gateway.Connection connection = gateway.last();
        connection.hello(40_000);
        connection.ready(1, "s", "wss://resume.invalid", "999");
        bridge.pulse();
        assertEquals(3, connection.lastSent().get("op").getAsInt());
        assertEquals("3 of 20 players online", connection.lastSent().getAsJsonObject("d").getAsJsonArray("activities").get(0).getAsJsonObject().get("state").getAsString());
        // heartbeats may come in between (random jitter); only status updates (op 3) are counted
        java.util.function.Supplier<List<String>> updates = () -> connection.sent.stream().filter(text -> text.startsWith("{\"op\":3")).toList();
        assertEquals(1, updates.get().size());
        host.online = 4;
        clock.addAndGet(10_000);
        bridge.pulse();
        assertEquals(1, updates.get().size(), "not within 30 seconds");
        clock.addAndGet(20_000);
        bridge.pulse();
        assertEquals(2, updates.get().size());
        assertTrue(updates.get().get(1).contains("4 of 20"), updates.get().toString());
    }

    @Test void givenTheBot_whenSwitchedOffOnReload_thenTheGatewayClosesCleanly() {
        bridge.configure(settings("discord.bot.enabled", true, "discord.bot-token", DiscordFakes.TOKEN));
        DiscordFakes.Gateway.Connection connection = gateway.last();
        bridge.configure(settings());
        assertEquals(1000, connection.closeCode);
        assertNull(bridge.gateway());
    }

    @Test void givenABotOnlyChannel_whenAPlayerWrites_thenTheBotPostsWithTheNameInBold() {
        Settings s = DiscordFakes.settings("", "discord.bot.enabled", true, "discord.bot-token", DiscordFakes.TOKEN);
        bridge.configure(s);
        bridge.chat(s.channel("global"), ALEX, "Alex_", "hi");
        assertEquals("/api/v10/channels/111111/messages", server.requests.get(0).path());
        assertEquals("Bot " + DiscordFakes.TOKEN, server.requests.get(0).headers().get("authorization"));
        assertEquals("**Alex\\_**: hi", lastPost().get("content").getAsString());
    }
}
