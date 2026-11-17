package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class EmojisTest {
    @Test
    void codesBecomeSymbolsInAnyCase() {
        assertEquals("I ❤ this ★", Emojis.apply("I :heart: this :STAR:", Emojis.DEFAULTS));
        assertEquals("❤❤", Emojis.apply(":heart::heart:", Emojis.DEFAULTS));
    }

    @Test
    void unknownCodesAndNormalColonsStay() {
        assertEquals("meet at 12:30: ok :nope:", Emojis.apply("meet at 12:30: ok :nope:", Emojis.DEFAULTS));
        assertEquals("no colon", Emojis.apply("no colon", Emojis.DEFAULTS));
        assertEquals(":heart:", Emojis.apply(":heart:", Map.of()));
    }

    @Test
    void codesComeFromTheConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("emojis.codes.Wave", "~");
        config.set("emojis.codes.empty", "");
        config.set("emojis.codes.bad code", "!");
        Map<String, String> codes = Emojis.read(config.getConfigurationSection("emojis.codes"));
        assertEquals(Map.of("wave", "~"), codes);
        assertEquals("hi ~", Emojis.apply("hi :wave:", codes));
        assertSame(Emojis.DEFAULTS, Emojis.read(null));
        Settings shipped = new Settings(YamlConfiguration.loadConfiguration(new java.io.File("src/main/resources/config.yml")), java.util.logging.Logger.getLogger("test"));
        assertTrue(shipped.emojisEnabled);
        assertEquals(Emojis.DEFAULTS, shipped.emojis, "shipped config matches the built-in codes");
    }
}
