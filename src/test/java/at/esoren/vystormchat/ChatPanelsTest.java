package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Panels;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatPanelsTest {
    private final ChatPlugin plugin = mock(ChatPlugin.class);
    private final Panels core = mock(Panels.class);
    private final Player player = mock(Player.class);
    private final ChatService service = mock(ChatService.class);
    private final ProfileStore profiles = mock(ProfileStore.class);
    private final MuteStore mutes = mock(MuteStore.class);
    private final Reports reports = mock(Reports.class);
    private final ReportStore store = mock(ReportStore.class);
    private final Showcase showcase = mock(Showcase.class);
    private final ChatMenu menu = mock(ChatMenu.class);
    private final Map<String, Boolean> permissions = new HashMap<>();
    private final UUID playerId = UUID.randomUUID();
    private final Profile profile = new Profile(playerId);
    private ChatPanels panels;
    private Lang lang;
    private Settings settings;

    @BeforeEach void setup() throws Exception {
        lang = ChatPanelTest.language();
        when(plugin.lang()).thenReturn(lang);
        var hub = TestCore.api();
        when(plugin.core()).thenReturn(hub);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ChatPanelsTest"));
        when(plugin.menu()).thenReturn(menu);
        when(plugin.service()).thenReturn(service);
        when(plugin.profiles()).thenReturn(profiles);
        when(plugin.mutes()).thenReturn(mutes);
        when(plugin.states()).thenReturn(mock(States.class));
        when(plugin.hooks()).thenReturn(mock(Hooks.class));
        when(plugin.messages()).thenReturn(mock(PrivateMessages.class));
        when(plugin.reports()).thenReturn(reports);
        when(reports.store()).thenReturn(store);
        when(store.list(anyLong())).thenReturn(List.of());
        when(plugin.showcase()).thenReturn(showcase);
        when(profiles.get(playerId)).thenReturn(profile);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.locale()).thenReturn(Locale.US);
        when(player.hasPermission(anyString())).thenAnswer(call -> permissions.getOrDefault(call.getArgument(0), true));
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            settings = new Settings(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)), Logger.getLogger("ChatPanelsTest"));
        }
        when(service.settings()).thenReturn(settings);
        when(plugin.focus(player)).thenReturn(settings.defaultChannel());
        panels = new ChatPanels(plugin, core);
    }

    private JsonObject render(String name, String arg) {
        return PanelTesting.render(new ChatPanel().render(ChatPanelTest.context(player, lang, "en"), panels.data(player, name, arg), panels.actions(name)));
    }
    private ReportStore.Report report(UUID id, boolean closed) {
        return new ReportStore.Report(id, UUID.randomUUID(), "Reporter", UUID.randomUUID(), "Target", "Unverified description", System.currentTimeMillis(), closed, List.of());
    }
    private void deny(String permission) { permissions.put("vystormchat." + permission, false); }

    @Test void givenChatOverview_whenRendered_thenChannelsControlsAndPermittedNavigationExist() {
        JsonObject doc = render("chat", "");
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("chat-tabs", "entries", "listen", "message", "spy", "settings", "submit", "ignored", "moderation")), doc.toString());
        assertFalse(doc.toString().contains("Your party"), "group channels require membership");
        deny("msg"); deny("spy"); deny("ignore"); deny("moderate");
        var ids = PanelTesting.ids(render("chat", ""));
        assertFalse(ids.contains("message") || ids.contains("spy") || ids.contains("ignored") || ids.contains("moderation"));
    }

    @Test void givenIgnoredList_whenEmpty_thenAddAndBackRemainAvailable() {
        var ids = PanelTesting.ids(render("ignored", ""));
        assertTrue(ids.containsAll(List.of("entries", "add-ignore", "chat")));
        assertFalse(ids.contains("unignore"));
    }

    @Test void givenModeration_whenRendered_thenClearConfirmsAndSlowModeIsNumeric() {
        when(service.slowMode(null)).thenReturn(10);
        JsonObject doc = render("moderation", "");
        assertNotNull(PanelTesting.find(doc, "clear").get("confirm"));
        assertEquals(10, PanelTesting.find(doc, "slow").get("value").getAsInt());
        deny("clearchat"); deny("slowmode"); deny("mute");
        var ids = PanelTesting.ids(render("moderation", ""));
        assertFalse(ids.contains("clear") || ids.contains("slow") || ids.contains("mutes") || ids.contains("mute-player"));
        assertTrue(ids.contains("reports"));
    }

    @Test void givenActiveMute_whenRendered_thenReasonActorDurationAndConfirmedUnmuteAreShown() {
        UUID target = UUID.randomUUID();
        when(mutes.list(anyLong())).thenReturn(List.of(new MuteStore.Mute(target, "Muted", 0, 0, "Spam", "Moderator")));
        JsonObject doc = render("mutes", "");
        assertTrue(doc.toString().contains("Spam") && doc.toString().contains("Moderator") && doc.toString().contains("until unmuted"));
        assertNotNull(PanelTesting.find(doc, "unmute").get("confirm"));
    }

    @Test void givenReports_whenRendered_thenAllegationAndCaseNavigationAreVisible() {
        UUID id = UUID.randomUUID();
        when(store.list(anyLong())).thenReturn(List.of(report(id, false)));
        JsonObject doc = render("reports", "");
        assertNotNull(PanelTesting.find(doc, "case"));
        assertTrue(doc.toString().contains("unverified allegation") && doc.toString().contains("Reporter") && doc.toString().contains("Unverified description"));
    }

    @Test void givenCase_whenRendered_thenSummaryHistoryAndAllowedActionsExist() {
        UUID id = UUID.randomUUID();
        when(store.get(id)).thenReturn(report(id, true));
        JsonObject doc = render("report", id.toString());
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("summary", "entries", "note", "status", "case-mute", "case-unmute", "reports")));
        assertTrue(doc.toString().contains("Reopen report") && doc.toString().contains("Current chat restriction"));
        deny("mute");
        assertNull(PanelTesting.find(render("report", id.toString()), "case-mute"));
        assertNull(PanelTesting.find(render("report", id.toString()), "case-unmute"));
    }

    @Test void givenShowcase_whenRendered_thenItemCountsAndOriginalReadOnlyViewAreAvailable() {
        UUID id = UUID.randomUUID();
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.DIAMOND);
        when(stack.getAmount()).thenReturn(3);
        ItemStack[] contents = new ItemStack[54];
        contents[0] = stack;
        when(showcase.stored(id)).thenReturn(new Showcase.Stored("Owner", contents, System.currentTimeMillis()));
        // PlatformCompat's Bukkit item name fallback needs a registry, so provide its portable name here.
        try (var compat = mockStatic(Vystorm.vystorm_core.compat.PlatformCompat.class, CALLS_REAL_METHODS)) {
            compat.when(() -> Vystorm.vystorm_core.compat.PlatformCompat.displayName(stack)).thenReturn(Component.text("Diamond"));
            JsonObject doc = render("showcase", id.toString());
            assertTrue(doc.toString().contains("Diamond") && doc.toString().contains("Inventory slot 1") && doc.toString().contains("Read-only snapshot"));
            assertNotNull(PanelTesting.find(doc, "inventory-view"));
            assertFalse(PanelTesting.ids(doc).contains("unignore") || PanelTesting.ids(doc).contains("unmute"));
        }
    }

    @Test void givenMissingCaseOrExpiredShowcase_whenRendered_thenLocalizedEmptyScreenHasNoActions() {
        JsonObject missing = render("report", "not-a-uuid");
        assertTrue(missing.toString().contains("Report not found"));
        assertNull(PanelTesting.find(missing, "note"));
        JsonObject expired = render("showcase", UUID.randomUUID().toString());
        assertNull(PanelTesting.find(expired, "inventory-view"));
        assertFalse(expired.toString().contains("showcase-expired"));
    }

    @Test void givenRevokedPermissions_whenActionRuns_thenServiceIsNotCalled() {
        var actions = panels.actions("moderation");
        deny("clearchat");
        assertFalse(actions.run(player, "clear", "", null).success());
        verify(plugin, never()).clearChat(any());
        deny("moderate");
        assertFalse(actions.run(player, "slow", "", 30d).success());
        verify(plugin, never()).slowMode(any(), any(), anyInt());
        deny("msg");
        assertFalse(panels.actions("chat").run(player, "message", "", null).success());
        verify(menu, never()).messageDialog(any(), anyString());
    }

    @Test void givenRemovedChannelOrLostGroup_whenActionRuns_thenStaleSelectionCannotChangeFocus() {
        assertFalse(panels.actions("chat").run(player, "focus", "gone", null).success());
        assertFalse(panels.actions("chat").run(player, "focus", "party", null).success());
        verify(plugin, never()).focus(any(), any());
        assertTrue(panels.actions("chat").run(player, "focus", "local", null).success());
        verify(plugin).focus(player, settings.channel("local"));
    }

    @Test void givenStaleIgnoreOrMute_whenActionRuns_thenItDoesNotToggleOrUnmuteAnotherState() {
        String id = UUID.randomUUID().toString();
        assertFalse(panels.actions("ignored").run(player, "unignore", id, null).success());
        assertFalse(panels.actions("mutes").run(player, "unmute", id, null).success());
        verify(plugin, never()).toggleIgnore(any(), any());
        verify(plugin, never()).unmute(any(), any());
    }

    @Test void givenInvalidSlowMode_whenChanged_thenOnlyWholeSecondsWithinBoundsAreAccepted() {
        for (double value : new double[]{-1, 3601, 2.5, Double.NaN, Double.POSITIVE_INFINITY})
            assertFalse(panels.actions("moderation").run(player, "slow", "", value).success());
        verify(plugin, never()).slowMode(any(), any(), anyInt());
        assertTrue(panels.actions("moderation").run(player, "slow", "", 60d).success());
        verify(plugin).slowMode(player, null, 60);
    }

    @Test void givenReportAction_whenAllowed_thenExistingDurableWorkflowHandlesIt() {
        UUID id = UUID.randomUUID();
        when(store.get(id)).thenReturn(report(id, false));
        assertTrue(panels.actions("report").run(player, "note", id.toString(), null).success());
        verify(reports).edit(player, id, "note", 0);
        assertTrue(panels.actions("report").run(player, "case-mute", id.toString(), null).success());
        verify(reports).muteDialog(player, id);
        deny("mute");
        assertFalse(panels.actions("report").run(player, "case-unmute", id.toString(), null).success());
        verify(reports, never()).confirmAction(any(), any(), anyLong(), anyBoolean());
    }

    @Test void givenNoOpenResultOrOldCoreError_whenOpening_thenCallerCanUseChestFallback() {
        when(core.open(player, "chat", "")).thenReturn(PanelOpenResult.NONE);
        assertFalse(panels.open(player, "chat", ""));
        when(core.open(player, "chat", "")).thenThrow(new IllegalStateException("panel failure"));
        assertFalse(panels.open(player, "chat", ""));
        when(core.open(player, "ignored", "")).thenReturn(PanelOpenResult.FALLBACK);
        assertTrue(panels.open(player, "ignored", ""));
    }

    @Test void givenRegisteredPanels_whenCoreFallsBack_thenOriginalChestReceivesTheRequestedCase() {
        Map<String, PanelSpec> specs = new HashMap<>();
        doAnswer(call -> { specs.put(call.getArgument(0), call.getArgument(1)); return null; }).when(core).register(anyString(), any());
        panels.register();
        assertEquals(ChatPanels.NAMES.size(), specs.size());
        assertEquals(1, specs.values().stream().filter(s -> s.menuCategory() != null).count());
        UUID id = UUID.randomUUID();
        when(core.open(player, "report", id.toString())).thenReturn(PanelOpenResult.FALLBACK);
        panels.open(player, "report", id.toString());
        specs.get("report").fallback().accept(player);
        verify(reports).detailChest(player, id, 0);
        specs.get("chat").fallback().accept(player);
        verify(menu).openChest(player);
        deny("moderate");
        assertFalse(specs.get("report").access().test(player));
        specs.get("report").fallback().accept(player);
        verify(reports, times(1)).detailChest(player, id, 0);
    }

    @Test void givenPartialRegistrationFailure_whenRegistering_thenEarlierPanelsAreRemoved() {
        doThrow(new IllegalStateException("panel failure")).when(core).register(eq("mutes"), any());
        assertThrows(IllegalStateException.class, panels::register);
        verify(core).unregister("chat");
        verify(core).unregister("ignored");
        verify(core).unregister("moderation");
        verify(core).unregister("mutes");
    }

    @Test void givenReportHistoryPage_whenCoreFallsBack_thenChestKeepsThatPage() {
        Map<String, PanelSpec> specs = new HashMap<>();
        doAnswer(call -> { specs.put(call.getArgument(0), call.getArgument(1)); return null; }).when(core).register(anyString(), any());
        panels.register();
        UUID id = UUID.randomUUID();
        String arg = id + ":2";
        when(core.open(player, "report", arg)).thenReturn(PanelOpenResult.FALLBACK);
        assertTrue(panels.open(player, "report", arg));
        specs.get("report").fallback().accept(player);
        verify(reports).detailChest(player, id, 2);
        when(store.get(id)).thenReturn(report(id, false));
        assertNotNull(PanelTesting.find(render("report", arg), "note"));
        panels.forget(playerId);
        specs.get("report").fallback().accept(player);
        verify(reports, times(1)).detailChest(any(), any(), anyInt());
    }

    @Test void givenDeniedScreen_whenOpened_thenNoPanelOrFallbackIsRequested() {
        deny("mute");
        assertTrue(panels.open(player, "mutes", ""));
        verifyNoInteractions(core);
        verify(menu, never()).mutesChest(any());
    }
}
