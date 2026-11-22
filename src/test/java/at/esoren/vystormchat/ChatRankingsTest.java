package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderRanking;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The ranking chat:messages in Core's placeholders (Core 0.67.0). */
class ChatRankingsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final Plugin CHAT = PlaceholderTesting.plugin("VystormChat");
    private static final Logger LOG = Logger.getLogger("ChatRankingsTest");
    private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes()), SAM = UUID.nameUUIDFromBytes("sam".getBytes()),
            KIM = UUID.nameUUIDFromBytes("kim".getBytes());

    @TempDir Path folder;

    /** The channels of the bundled config.yml. */
    private static Map<String, Channel> bundled() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        return Channel.parseAll(config.getConfigurationSection("channels"), null);
    }

    private MessageCounts counts() {
        return new MessageCounts(folder.resolve("message-counts.yml"), LOG, Runnable::run, AtomicFilesInterface.DEFAULT);
    }

    /** Sam 3 global + 1 trade (needs a read permission, does not count), Alex 2 local, Kim 2 world plus messages in staff, party, city and faction that do not count. */
    private MessageCounts fixture() {
        Map<String, Channel> channels = bundled();
        MessageCounts counts = counts();
        for (int i = 0; i < 3; i++) counts.count(SAM, "Sam", channels.get("global"));
        counts.count(SAM, "Sam", channels.get("trade"));
        for (int i = 0; i < 2; i++) counts.count(ALEX, "Alex", channels.get("local"));
        for (int i = 0; i < 2; i++) counts.count(KIM, "Kim", channels.get("world"));
        for (String closed : List.of("staff", "party", "city", "faction")) for (int i = 0; i < 10; i++) counts.count(KIM, "Kim", channels.get(closed));
        return counts;
    }

    private static Throwable declare(PlaceholderTesting placeholders, boolean coreHasRankings, boolean on, MessageCounts counts) {
        return ChatPlugin.rankings(coreHasRankings, CHAT, placeholders::placeholders, on, () -> counts, key -> "ref:" + key);
    }

    @Test void givenTheBundledChannels_whenAsked_thenOnlyChannelsEveryoneCanReadCount() {
        Map<String, Channel> channels = bundled();
        assertTrue(MessageCounts.counts(channels.get("global")));
        assertTrue(MessageCounts.counts(channels.get("local")));
        assertTrue(MessageCounts.counts(channels.get("world")));
        assertFalse(MessageCounts.counts(channels.get("trade")), "read permission (vystorm_core.chat.trade.receive)");
        assertFalse(MessageCounts.counts(channels.get("staff")), "read permission");
        assertFalse(MessageCounts.counts(channels.get("party")), "family link");
        assertFalse(MessageCounts.counts(channels.get("city")), "family link");
        assertFalse(MessageCounts.counts(channels.get("faction")), "family link");
        assertFalse(MessageCounts.counts(null));
        assertFalse(MessageCounts.counts(TestChannels.one("scope: global\nforward-command: partychat")), "forwarded to another plugin");
        assertFalse(MessageCounts.counts(TestChannels.one("scope: global\nread-permission: vip.read")), "a VIP channel");
    }

    @Test void givenCountedMessages_whenDeclared_thenTheWritersAreListedInOrder() {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(declare(placeholders, true, true, fixture()));
        assertEquals(List.of("chat:messages"), placeholders.rankings().stream().map(PlaceholderRanking::id).toList());

        List<RankingSource.Row> rows = placeholders.top("chat:messages", 10);
        assertEquals(List.of("Sam", "Alex", "Kim"), rows.stream().map(RankingSource.Row::name).toList(), "ties by name");
        assertEquals(List.of(SAM, ALEX, KIM), rows.stream().map(RankingSource.Row::player).toList());
        assertEquals(List.of(3.0, 2.0, 2.0), rows.stream().map(RankingSource.Row::value).toList(), "staff, trade and group messages never count");

        assertEquals("Sam", placeholders.resolve(null, "%vystorm_chat_top_messages_1_name%"));
        assertEquals("3", placeholders.resolve(null, "%vystorm_chat_top_messages_1_value%"));
        assertEquals("Kim", placeholders.resolve(null, "{chat:top_messages:3:name}"));
        assertEquals("", placeholders.resolve(null, "%vystorm_chat_top_messages_4_name%"));
        assertEquals("ref:rankings.messages.label", placeholders.ranking("chat:messages").orElseThrow().info().label());
        assertEquals("ref:rankings.messages.description", placeholders.ranking("chat:messages").orElseThrow().info().description());
        assertEquals(List.of(), placeholders.warnings());
    }

    @Test void givenARestart_whenTheFileIsReadAgain_thenOfflinePlayersAreListedAndNoMessageTextIsStored() throws Exception {
        MessageCounts counts = fixture();
        assertTrue(counts.dirty());
        counts.flush();   // the minute timer (io executor runs at once here)
        assertFalse(counts.dirty());
        String file = Files.readString(folder.resolve("message-counts.yml"));
        assertTrue(file.contains("messages: 3"));
        assertFalse(file.toLowerCase(Locale.ROOT).contains("global"), "only numbers and names, no channel or text");

        MessageCounts again = counts();
        again.load();
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        declare(placeholders, true, true, again);
        assertEquals(List.of(SAM, ALEX, KIM), placeholders.top("chat:messages", 10).stream().map(RankingSource.Row::player).toList(),
                "nobody is online: everyone comes from the file");
        assertEquals(3, again.messages(SAM));

        long before = Files.getLastModifiedTime(folder.resolve("message-counts.yml")).toMillis();
        again.flush();
        assertEquals(before, Files.getLastModifiedTime(folder.resolve("message-counts.yml")).toMillis(), "nothing changed: not written");
    }

    @Test void givenABrokenFile_whenLoaded_thenItIsKeptAsideAndNeverOverwritten() throws Exception {
        Path file = folder.resolve("message-counts.yml");
        Files.writeString(file, "players: [broken");
        MessageCounts counts = counts();
        counts.load();
        Map<String, Channel> channels = bundled();
        counts.count(SAM, "Sam", channels.get("global"));
        counts.saveNow();
        assertFalse(Files.exists(file), "moved aside, not written over");
        try (var files = Files.list(folder)) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().startsWith("message-counts.yml.corrupt-")).count());
        }
    }

    @Test void givenTheSwitchOff_whenDeclared_thenNothingIsOfferedAndATurnedOffRankingIsRemoved() {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(declare(placeholders, true, false, fixture()));
        assertTrue(placeholders.rankings().isEmpty());
        assertNull(declare(placeholders, true, true, fixture()));
        assertEquals(1, placeholders.rankings().size());
        assertNull(declare(placeholders, true, false, fixture()));   // /vchat reload with rankings.enabled: false
        assertTrue(placeholders.rankings().isEmpty());

        YamlConfiguration on = new YamlConfiguration();
        assertTrue(new Settings(on, LOG).enabled(Settings.Feature.RANKINGS), "on by default");
        YamlConfiguration off = new YamlConfiguration();
        off.set("rankings.enabled", false);
        assertFalse(new Settings(off, LOG).enabled(Settings.Feature.RANKINGS));
        YamlConfiguration chatOff = new YamlConfiguration();
        chatOff.set("chat.enabled", false);
        assertFalse(new Settings(chatOff, LOG).enabled(Settings.Feature.RANKINGS), "the master switch turns it off");
    }

    @Test void givenACoreWithoutRankings_whenApplied_thenNothingIsDeclaredAndNothingThrows() {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(ChatPlugin.rankings(false, CHAT, () -> { throw new AssertionError("an older Core must not be asked"); }, true, this::fixture, key -> key));
        assertTrue(placeholders.rankings().isEmpty());
        assertInstanceOf(NoClassDefFoundError.class,
                ChatPlugin.rankings(true, CHAT, () -> { throw new NoClassDefFoundError("RankingInfo"); }, true, this::fixture, key -> key));
        PlaceholderTesting taken = PlaceholderTesting.detached();
        taken.register(PlaceholderTesting.plugin("SomeoneElse"), "chat");
        assertInstanceOf(IllegalStateException.class, declare(taken, true, true, fixture()));
        PlaceholderTesting empty = PlaceholderTesting.detached();
        assertNull(declare(empty, true, true, null));
        assertEquals(List.of(), empty.top("chat:messages", 10));
    }

    @Test void givenEveryLanguage_whenRead_thenTheRankingHasALabelAndADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            assertFalse(lang.getString("rankings.messages.label", "").isBlank(), code);
            assertFalse(lang.getString("rankings.messages.description", "").isBlank(), code);
            assertFalse(lang.getString(Settings.Feature.RANKINGS.label(), "").isBlank(), code);
            assertFalse(lang.getString(Settings.Feature.RANKINGS.description(), "").isBlank(), code);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(config.getBoolean("rankings.enabled", false), "on by default");
        assertEquals("0.65.0", ChatPlugin.MIN_CORE, "the minimum Core version stays");
    }

    /** Only ChatRankings may name Core's ranking types: on an older Core a class that does fails to load. */
    @Test void givenTheSources_whenRead_thenOnlyChatRankingsNamesCoresRankingTypes() throws Exception {
        List<String> offending = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("ChatRankings.java")) continue;
                String text = Files.readString(file);
                for (String type : List.of("RankingInfo", "RankingSource", "PlaceholderRanking", "removeRanking"))
                    if (java.util.regex.Pattern.compile("\\b" + type + "\\b").matcher(text).find()) offending.add(file.getFileName() + ": " + type);
            }
        }
        assertEquals(List.of(), offending);
    }
}
