package at.esoren.vystormchat;

import static at.esoren.vystormchat.ChatPanel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ChatPanelTest {
    static final List<String> LANGUAGES = List.of("de", "en", "fr", "it", "pt_br", "ru", "zh_cn");
    static Lang language() throws Exception {
        Map<String, String> files = new HashMap<>();
        for (String code : LANGUAGES) try (var in = ChatPanelTest.class.getResourceAsStream("/lang/" + code + ".yml")) {
            files.put(code, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return Lang.create("vystormchat", files);
    }
    static PanelContext context(Player p, Lang lang, String language) {
        return new PanelContext() {
            public Player player() { return p; }
            public String arg() { return ""; }
            public String text(String key, Object... args) { return lang.plain(language, key, args); }
            public Component component(String key, Object... args) { return lang.component(language, key, args); }
            public PanelSession session() { return null; }
        };
    }
    private final Player player = mock(Player.class);
    private final ChatPanel panel = new ChatPanel();
    private final Actions actions = new Actions() {
        public boolean allowed(Player p) { return true; }
        public Result run(Player p, String action, String target, Object value) { return Result.ok(); }
    };
    ChatPanelTest() { when(player.getUniqueId()).thenReturn(UUID.randomUUID()); }

    private static Control button(String id, String title, String confirmation) {
        return new Control(id, title, Material.BOOK, "target", Kind.BUTTON, null, false, confirmation);
    }
    private static List<Row> rows(int count) {
        return IntStream.range(0, count).mapToObj(i -> new Row("entry-" + i, "Player " + i, "Status " + i, Ui.item(Material.PAPER),
                List.of(new Field("Reason", "Report " + i)), List.of(button("case", "Open case", null)))).toList();
    }
    private static Data data(List<Row> rows) {
        return new Data("Player reports", "Select a report", Material.BOOK, true, rows, List.of(), List.of(), List.of(button("back", "Back", null)), false);
    }
    private JsonObject render(Data data) throws Exception {
        return PanelTesting.render(panel.render(context(player, language(), "en"), data, actions));
    }

    @Test void givenCollection_whenRendered_thenFirstEntryDetailsAndStableIdsAreShown() throws Exception {
        JsonObject doc = render(data(rows(3)));
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("entries", "detail-title", "detail-fields", "case", "back")));
        assertEquals("entry-0", PanelTesting.find(doc, "entries").get("selected").getAsString());
        assertEquals("Player 0", PanelTesting.plain(PanelTesting.find(doc, "detail-title").get("text")));
        assertTrue(PanelTesting.find(doc, "detail-fields").toString().contains("Report 0"));
    }

    @Test void givenSelection_whenRefreshed_thenOnlyChosenEntryHasDetails() throws Exception {
        panel.select(player.getUniqueId(), "entry-2");
        JsonObject doc = render(data(rows(3)));
        assertEquals("Player 2", PanelTesting.plain(PanelTesting.find(doc, "detail-title").get("text")));
        assertFalse(PanelTesting.find(doc, "detail-fields").toString().contains("Report 0"));
    }

    @Test void givenMoreThanOnePage_whenLastPageSelected_thenOnlyRemainingRowsRender() throws Exception {
        panel.page(player.getUniqueId(), 2);
        JsonObject doc = render(data(rows(19)));
        assertEquals(3, PanelTesting.find(doc, "entries").getAsJsonArray("items").size());
        assertEquals("entry-16", PanelTesting.find(doc, "entries").get("selected").getAsString());
        assertEquals("Page 3 of 3", PanelTesting.plain(PanelTesting.find(doc, "page").get("text")));
        assertTrue(PanelTesting.find(doc, "next").get("disabled").getAsBoolean());
        assertFalse(PanelTesting.find(doc, "previous").get("disabled").getAsBoolean());
    }

    @Test void givenRemovedRows_whenRefreshed_thenPageAndSelectionAreClamped() throws Exception {
        panel.page(player.getUniqueId(), Integer.MAX_VALUE);
        panel.select(player.getUniqueId(), "gone");
        JsonObject doc = render(data(rows(1)));
        assertEquals("entry-0", PanelTesting.find(doc, "entries").get("selected").getAsString());
        assertNull(PanelTesting.find(doc, "next"));
        assertNull(PanelTesting.find(render(data(List.of())), "detail-title"));
    }

    @Test void givenTwoPlayers_whenOneSelects_thenOtherKeepsDefaultAndQuitClearsState() throws Exception {
        panel.page(UUID.randomUUID(), 1);
        panel.select(UUID.randomUUID(), "entry-2");
        assertEquals("entry-0", PanelTesting.find(render(data(rows(3))), "entries").get("selected").getAsString());
        panel.page(player.getUniqueId(), 1);
        panel.select(player.getUniqueId(), "entry-9");
        panel.forget(player.getUniqueId());
        assertEquals("entry-0", PanelTesting.find(render(data(rows(12))), "entries").get("selected").getAsString());
    }

    @Test void givenSettingsAndModeration_whenRendered_thenTogglesNumberFieldsAndConfirmationAreUsed() throws Exception {
        Data data = new Data("Moderation", "Manage chat", Material.IRON_BARS, false, List.of(), List.of(), List.of(
                new Control("spy", "Social spy", Material.ENDER_EYE, "", Kind.TOGGLE, true, false, null),
                new Control("slow", "Slow mode", Material.CLOCK, "", Kind.NUMBER, 30, false, null),
                button("clear", "Clear chat", "Clear the chat for everyone?")), List.of(), false);
        JsonObject doc = render(data);
        assertEquals("toggle", PanelTesting.find(doc, "spy").get("type").getAsString());
        assertTrue(PanelTesting.find(doc, "spy").get("value").getAsBoolean());
        assertEquals("number", PanelTesting.find(doc, "slow").get("type").getAsString());
        assertEquals(30, PanelTesting.find(doc, "slow").get("value").getAsInt());
        assertEquals("Clear the chat for everyone?", PanelTesting.plain(PanelTesting.find(doc, "clear").getAsJsonObject("confirm").get("text")));
    }

    @Test void givenMainMenu_whenRendered_thenChannelAndPersonalTabsExist() throws Exception {
        Data d = new Data("Chat", "Channels and settings", Material.WRITABLE_BOOK, true, rows(1), List.of(),
                List.of(button("settings", "Chat settings", null)), List.of(), true);
        JsonObject doc = render(d);
        assertNotNull(PanelTesting.find(doc, "chat-tabs"));
        assertTrue(doc.toString().contains("Channels") && doc.toString().contains("Chat settings"));
    }

    @Test void givenAllLanguages_whenPagedPanelRendered_thenTextsResolveInEveryBundle() throws Exception {
        Lang lang = language();
        for (String code : LANGUAGES) {
            JsonObject doc = PanelTesting.render(panel.render(context(player, lang, code), data(rows(9)), actions));
            assertEquals(lang.plain(code, "panel.page", "page", 1, "pages", 2), PanelTesting.plain(PanelTesting.find(doc, "page").get("text")));
            assertFalse(doc.toString().contains("panel.empty") || doc.toString().contains("reports.previous"), code);
        }
    }
}
