package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.schedule.TaskGroup;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeywordAlertsTest {
    @TempDir Path folder;

    @Test
    void phrasesAreLiteralCaseInsensitiveAndLimitedInSavedOrder() {
        assertEquals("dia", KeywordAlerts.match("DIAMONDS for sale", List.of("dia"), 10));
        assertEquals("[rare]", KeywordAlerts.match("Selling [RARE] items", List.of("[rare]"), 10));
        assertNull(KeywordAlerts.match("anything", List.of(".*"), 10));
        assertEquals("钻石", KeywordAlerts.match("出售钻石", List.of("钻石"), 10));
        assertNull(KeywordAlerts.match("second", List.of("first", "second"), 1));
        assertEquals("buy iron", KeywordAlerts.match("I BUY IRON today", List.of("buy iron"), 10));
    }

    @Test
    void profileValidatesDuplicatesLimitsAndRemoval() {
        Profile profile = new Profile(UUID.randomUUID());
        assertFalse(profile.addKeyword("x", 2));
        assertFalse(profile.addKeyword("x".repeat(49), 2));
        assertFalse(profile.addKeyword("ab\u0000cd", 2));
        assertTrue(profile.addKeyword("  Buy   iron  ", 2));
        assertFalse(profile.addKeyword("buy iron", 2));
        assertTrue(profile.addKeyword("gold", 2));
        assertFalse(profile.addKeyword("diamonds", 2));
        assertEquals(List.of("Buy iron", "gold"), profile.keywords());
        assertTrue(profile.removeKeyword(" BUY IRON "));
        assertFalse(profile.removeKeyword("missing"));
        assertTrue(profile.addKeyword("diamonds", 2));
        assertThrows(UnsupportedOperationException.class, () -> profile.keywords().add("oops"));
    }

    @Test
    void phrasesAndPauseSurviveAtomicStorage() throws Exception {
        UUID id = UUID.randomUUID();
        ProfileStore store = new ProfileStore(folder, Logger.getLogger("test"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        Profile profile = store.load(id).get();
        profile.addKeyword("iron ingot", 10);
        profile.keywordAlerts(false);
        store.unload(id);
        store.close();
        ProfileStore again = new ProfileStore(folder, Logger.getLogger("test"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        try {
            Profile loaded = again.load(id).get();
            assertEquals(List.of("iron ingot"), loaded.keywords());
            assertFalse(loaded.keywordAlerts());
            assertFalse(loaded.takeDirty());
        } finally { again.close(); }
        Profile old = Profile.fromYaml(id, "focus: trade\n");
        assertTrue(old.keywords().isEmpty());
        assertTrue(old.keywordAlerts());
    }

    @Test
    void malformedSavedEntriesAreBoundedAndDeduplicated() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        var words = new java.util.ArrayList<>(List.of("a", "valid", "VALID", "x".repeat(49)));
        for (int i = 0; i < 50; i++) words.add("word" + i);
        yaml.set("keywords", words);
        Profile profile = Profile.fromYaml(UUID.randomUUID(), yaml.saveToString());
        assertEquals(20, profile.keywords().size());
        assertEquals("valid", profile.keywords().getFirst());
        assertFalse(profile.takeDirty());
    }

    @Test
    void cooldownIsPerRecipientAndQuitClearsIt() {
        KeywordAlerts alerts = new KeywordAlerts();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(alerts.claim(a, 100, 1000));
        assertFalse(alerts.claim(a, 1099, 1000));
        assertTrue(alerts.claim(b, 101, 1000));
        assertTrue(alerts.claim(a, 1100, 1000));
        alerts.forget(a);
        assertTrue(alerts.claim(a, 1101, 1000));
        assertTrue(alerts.claim(a, 1101, 0));
    }

    @Test
    void configMigrationPreservesValuesAndIgnoresAttachedDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.addDefault("keyword-alerts.max-keywords", 10);
        config.set("keyword-alerts.enabled", false);
        config.set("channels.custom.scope", "world");
        assertTrue(Settings.addKeywordDefaults(config));
        assertTrue(config.contains("keyword-alerts.max-keywords", true));
        assertFalse(config.getBoolean("keyword-alerts.enabled"));
        assertEquals("world", config.getString("channels.custom.scope"));
        assertFalse(Settings.addKeywordDefaults(config));
        config.set("keyword-alerts.max-keywords", 999);
        config.set("keyword-alerts.cooldown-seconds", -1);
        Settings settings = new Settings(config, Logger.getLogger("test"));
        assertEquals(20, settings.keywordLimit);
        assertEquals(0, settings.keywordCooldownMillis);
        assertFalse(settings.keywordsEnabled);
    }

    @Test
    void deliveryOnlyNotifiesNormalRecipientsAndRespectsControls() {
        ChatPlugin plugin = mock(ChatPlugin.class);
        ChatService service = mock(ChatService.class);
        ProfileStore profiles = mock(ProfileStore.class);
        TaskGroup tasks = mock(TaskGroup.class);
        Lang lang = mock(Lang.class);
        when(plugin.service()).thenReturn(service);
        when(plugin.profiles()).thenReturn(profiles);
        when(plugin.tasks()).thenReturn(tasks);
        when(plugin.lang()).thenReturn(lang);
        Settings settings = new Settings(new YamlConfiguration(), Logger.getLogger("test"));
        when(service.settings()).thenReturn(settings);
        doAnswer(i -> { i.<Runnable>getArgument(0).run(); return null; }).when(tasks).now(any(Runnable.class));
        Player sender = mock(Player.class), normal = mock(Player.class), spy = mock(Player.class), absent = mock(Player.class);
        for (Player player : List.of(sender, normal, spy, absent)) {
            UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id);
            when(player.hasPermission("vystormchat.keywords")).thenReturn(true);
            Profile profile = new Profile(id);
            profile.addKeyword("iron", 10);
            profile.mentionSound(false);
            when(profiles.cached(id)).thenReturn(profile);
        }
        when(sender.getName()).thenReturn("Seller");
        Channel channel = TestChannels.one("scope: global");
        Map<UUID, Visibility.Reach> reach = Map.of(sender.getUniqueId(), Visibility.Reach.NORMAL,
                normal.getUniqueId(), Visibility.Reach.NORMAL, spy.getUniqueId(), Visibility.Reach.SPY);
        Delivery delivery = new Delivery(service, sender, null, channel, "iron", "iron", Set.of(), false,
                Showcase.Snapshot.NONE, List.of(), reach);
        KeywordAlerts alerts = new KeywordAlerts();
        try (var bukkit = mockStatic(Bukkit.class)) {
            for (Player player : List.of(sender, normal, spy, absent))
                bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            alerts.after(plugin, delivery);
            verify(lang).actionBar(eq(normal), eq("keyword-hit"), any(Object[].class));
            verifyNoMoreInteractions(lang);
            clearInvocations(lang);
            alerts.after(plugin, delivery);
            verifyNoInteractions(lang);
            alerts.forget(normal.getUniqueId());
            profiles.cached(normal.getUniqueId()).keywordAlerts(false);
            alerts.after(plugin, delivery);
            verifyNoInteractions(lang);
            profiles.cached(normal.getUniqueId()).keywordAlerts(true);
            when(normal.hasPermission("vystormchat.keywords")).thenReturn(false);
            alerts.after(plugin, delivery);
            verifyNoInteractions(lang);
            when(normal.hasPermission("vystormchat.keywords")).thenReturn(true);
            Delivery mention = new Delivery(service, sender, null, channel, "iron", "iron", Set.of(), false,
                    Showcase.Snapshot.NONE, List.of(new Mentions.Hit(0, 4, normal.getUniqueId(), false)), reach);
            alerts.after(plugin, mention);
            verifyNoInteractions(lang);
            YamlConfiguration disabled = new YamlConfiguration();
            disabled.set("keyword-alerts.enabled", false);
            when(service.settings()).thenReturn(new Settings(disabled, Logger.getLogger("test")));
            alerts.after(plugin, delivery);
            verifyNoInteractions(lang);
        }
    }
}
