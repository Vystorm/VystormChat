package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core._coreMethods.dataStorage.DataStorageInterface;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config or data file with a YAML error is never saved over (1.17.1: config.yml was rewritten with three keys). */
class BrokenYamlTest {
    private static final String BROKEN = "# my channels\nchannels:\n  global:\n    format: x\nthis line has no colon\nmax-length: 200\n";
    @TempDir Path dir;
    /** Bukkit's loadConfiguration logs through Bukkit.getLogger(), which needs a server. */
    private org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit;

    @BeforeEach void bukkitLogger() {
        bukkit = mockStatic(org.bukkit.Bukkit.class);
        bukkit.when(org.bukkit.Bukkit::getLogger).thenReturn(Logger.getLogger("Bukkit"));
    }

    @AfterEach void closeBukkit() { bukkit.close(); }

    /** A plugin whose getConfig/reloadConfig/saveConfig behave like Bukkit's: a broken file loads as an empty config. */
    private ChatPlugin plugin() throws Exception {
        ChatPlugin plugin = mock(ChatPlugin.class, CALLS_REAL_METHODS);
        File configFile = dir.resolve("config.yml").toFile();
        AtomicReference<FileConfiguration> config = new AtomicReference<>(new YamlConfiguration());
        doReturn(dir.toFile()).when(plugin).getDataFolder();
        doReturn(Logger.getLogger("BrokenYamlTest")).when(plugin).getLogger();
        doAnswer(call -> config.get()).when(plugin).getConfig();
        doAnswer(call -> { config.set(bukkitLoad(configFile)); return null; }).when(plugin).reloadConfig();
        doAnswer(call -> { config.get().save(configFile); return null; }).when(plugin).saveConfig();
        return plugin;
    }

    /** Bukkit's YamlConfiguration.loadConfiguration without a server: a parse error only gives an empty config. */
    static YamlConfiguration bukkitLoad(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file); } catch (Exception logged) { /* Bukkit logs it and returns what it has */ }
        return yaml;
    }

    @Test void givenABrokenConfig_whenTheStartReadsIt_thenTheFileIsByteIdenticalAndNothingIsSaved() throws Exception {
        Path config = Files.writeString(dir.resolve("config.yml"), BROKEN);
        ChatPlugin plugin = plugin();
        assertFalse(plugin.readConfig());
        verify(plugin, never()).saveConfig();
        assertArrayEquals(BROKEN.getBytes(), Files.readAllBytes(config));
    }

    @Test void givenABrokenConfig_whenReloaded_thenTheFileStaysAndTheProblemNamesLineAndColumn() throws Exception {
        Path config = Files.writeString(dir.resolve("config.yml"), BROKEN);
        ChatPlugin plugin = plugin();
        Field loaded = ChatPlugin.class.getDeclaredField("configLoaded");
        loaded.setAccessible(true);
        loaded.set(plugin, true);
        plugin.load();
        assertArrayEquals(BROKEN.getBytes(), Files.readAllBytes(config));
        assertEquals("line 6, column 1: could not find expected ':'", plugin.configProblem());
    }

    @Test void givenAValidConfigWithoutKeywordAlerts_whenRead_thenTheDefaultsAreAddedAndTheRestKept() throws Exception {
        Path config = Files.writeString(dir.resolve("config.yml"), "max-length: 123\n");
        assertTrue(plugin().readConfig());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(config.toFile());
        assertEquals(123, saved.getInt("max-length"));
        assertTrue(saved.getBoolean("keyword-alerts.enabled"));
    }

    @Test void givenABrokenAnnouncementsFile_whenLoadedAndLaterChanged_thenItIsCopiedAsideBeforeTheWrite() throws Exception {
        String broken = "announcements:\n  1:\n    text: [Vote\n";
        Path file = Files.writeString(dir.resolve("announcements.yml"), broken);
        ChatPlugin plugin = plugin();
        API core = mock(API.class);
        DataStorageInterface data = mock(DataStorageInterface.class);
        when(core.data()).thenReturn(data);
        when(data.files()).thenReturn(AtomicFilesInterface.DEFAULT);
        doReturn(core).when(plugin).core();
        Announcements announcements = new Announcements(plugin, file);
        assertArrayEquals(broken.getBytes(), Files.readAllBytes(file), "start path");
        assertTrue(announcements.put(new Announcements.Announcement(1, "Vote for us!", 10, "", true)));
        File[] copies = dir.toFile().listFiles((d, name) -> name.startsWith("announcements.yml.broken-"));
        assertEquals(1, copies.length);
        assertArrayEquals(broken.getBytes(), Files.readAllBytes(copies[0].toPath()));
    }
}
