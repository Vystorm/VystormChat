package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The shipped config.yml and the language files. */
class ConfigAndLangTest {
    private static YamlConfiguration lang(String name) { return YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + name)); }

    private static Settings shipped() {
        return new Settings(YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")), Logger.getLogger("test"));
    }

    @Test
    void shippedConfigLoadsEveryChannel() {
        Settings settings = shipped();
        assertEquals(List.of("global", "local", "world", "trade", "staff", "party", "city", "faction"), List.copyOf(settings.channels.keySet()));
        assertEquals("global", settings.defaultChannel().id());
        assertEquals("trade", settings.coreTrade().id());
        assertEquals("partychat", settings.channel("party").forwardCommand());
        assertEquals("vystorm_core.chat.trade.send", settings.channel("trade").writePermission());
        assertEquals(Channel.Scope.LOCAL, settings.channel("LOCAL").scope());
        assertFalse(settings.channel("world").autoJoin());
        assertTrue(settings.spam.enabled());
        assertFalse(settings.filter.empty());
        assertEquals(Set.of("vystorm.net", "minecraft.wiki"), settings.allowedDomains);
        assertTrue(settings.mutedCommands.contains("vtrade"));
        assertEquals("HH:mm:ss", settings.timestampFormat);
        assertFalse(settings.regionLookup, "no region channel in the default config");
        assertEquals(2, settings.formats.nameFormats().size());
    }

    @Test
    void everyConfigKeyHasADefaultAndNoOnOffKeys() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (String key : config.getKeys(true)) {
            String last = key.substring(key.lastIndexOf('.') + 1);
            assertFalse(Set.of("on", "off", "yes", "no", "true", "false").contains(last), "bare YAML boolean key: " + key);
            assertNotNull(config.get(key), key);
        }
    }

    @Test
    void missingForwardCommandsFallBackToOwnDelivery() {
        Settings settings = shipped();
        settings.dropMissingForwards(command -> false, null);
        assertEquals("", settings.channel("party").forwardCommand());
        assertEquals(Channel.Scope.PARTY, settings.channel("party").scope());
        Settings withParties = shipped();
        withParties.dropMissingForwards("partychat"::equals, null);
        assertEquals("partychat", withParties.channel("party").forwardCommand());
    }

    @Test
    void brokenValuesFallBack() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("default-channel: nope\ntimestamp-format: \"bad {pattern\"\nchannels:\n  only:\n    scope: galaxy\n");
        Settings settings = new Settings(config, Logger.getLogger("test"));
        assertEquals("global", settings.defaultChannel().id(), "a plain global channel when none is valid");
        assertEquals("HH:mm:ss", settings.timestampFormat);
    }

    @Test
    void englishAndGermanHaveTheSameKeys() {
        assertEquals(new TreeSet<>(lang("en.yml").getKeys(true)), new TreeSet<>(lang("de.yml").getKeys(true)));
    }

    @Test
    void chineseHasTheEnglishKeysAndPlaceholders() {
        YamlConfiguration en = lang("en.yml"), zh = lang("zh_cn.yml");
        assertEquals(new TreeSet<>(en.getKeys(true)), new TreeSet<>(zh.getKeys(true)));
        Pattern placeholder = Pattern.compile("\\{[a-z0-9_-]+}");
        for (String file : new String[] {"de.yml", "zh_cn.yml"}) {
            YamlConfiguration other = lang(file);
            for (String key : en.getKeys(true)) {
                if (en.isConfigurationSection(key)) continue;
                Set<String> a = new TreeSet<>(), b = new TreeSet<>();
                placeholder.matcher(en.getString(key, "")).results().forEach(m -> a.add(m.group()));
                placeholder.matcher(other.getString(key, "")).results().forEach(m -> b.add(m.group()));
                assertEquals(a, b, file + " " + key);
            }
        }
    }

    /** Only real MiniMessage tags; no {@code <player>}-style placeholders in texts or usage lines. */
    @Test
    void noRawArgumentTags() {
        Pattern tag = Pattern.compile("<(/?)([a-z_#0-9]+)[^>]*>");
        Set<String> allowed = Set.of("gray", "gold", "yellow", "green", "red", "white", "aqua", "dark_gray", "light_purple", "bold");
        for (String file : new String[] {"en.yml", "de.yml", "zh_cn.yml"}) {
            YamlConfiguration lang = lang(file);
            for (String key : lang.getKeys(true)) {
                if (lang.isConfigurationSection(key)) continue;
                Matcher matcher = tag.matcher(lang.getString(key, ""));
                while (matcher.find()) assertTrue(allowed.contains(matcher.group(2)), file + " " + key + ": unexpected tag " + matcher.group());
            }
        }
        for (String usage : YamlConfiguration.loadConfiguration(new File("src/main/resources/plugin.yml")).getValues(true).values().stream()
                .filter(String.class::isInstance).map(String.class::cast).toList())
            assertFalse(usage.matches("(?s).*<[a-z]+>.*"), "plugin.yml: " + usage);
    }

    /** Every lang key the code uses exists in en.yml. */
    @Test
    void everyKeyUsedInTheCodeExists() throws IOException {
        YamlConfiguration en = lang("en.yml");
        Pattern call = Pattern.compile("(?:Denied\\(|(?<!Placeholder\\.)(?:send|component|plain|lines|actionBar|ref|has|item)\\((?:[\\w.()]+, )?)\"([a-z][a-z0-9.-]*)\"");
        Pattern second = Pattern.compile("Menu\\.item\\([^;]*?\"([a-z][a-z0-9.-]*)\", \"([a-z][a-z0-9.-]*)\"");
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher matcher = call.matcher(source);
                while (matcher.find()) used.add(matcher.group(1));
                Matcher pair = second.matcher(source);
                while (pair.find()) { used.add(pair.group(1)); used.add(pair.group(2)); }
            }
        }
        used.removeIf(key -> key.endsWith("-") || key.endsWith(".") || key.startsWith("vystormchat") || key.startsWith("settings.show.") || key.equals("chat") || key.startsWith("channel-names"));
        for (String scope : List.of("global", "world", "local", "region", "party", "city", "faction")) used.add("menu.scope-" + scope);
        for (String scope : List.of("party", "city", "faction", "region")) used.add("channel-no-" + scope);
        for (String verdict : List.of("repeat", "flood", "caps")) used.add("spam-" + verdict);
        for (String line : List.of("name", "rank", "world", "playtime", "party", "city", "faction", "click")) used.add("hover-card." + line);
        assertTrue(used.size() > 100, "the scan found the keys: " + used.size());
        for (String key : used) assertTrue(en.contains(key) && !en.isConfigurationSection(key), "missing lang key: " + key);
    }

    @Test
    void shippedChannelsHaveTranslatedSettingLabels() {
        YamlConfiguration en = lang("en.yml");
        for (String channel : shipped().channels.keySet()) assertTrue(en.contains("settings.show." + channel), channel);
    }

    @Test
    void givenShippedConfig_whenLoaded_thenEveryFeatureSwitchIsOn() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        Settings settings = shipped();
        for (String key : List.of("reports.enabled", "private-messages.enabled", "ignore.enabled", "mutes.enabled"))
            assertTrue(config.isBoolean(key) && config.getBoolean(key), key + " ships as true");
        for (Settings.Feature feature : Settings.Feature.values()) assertEquals(feature.defaultOn, settings.enabled(feature), feature.name());
    }

    @Test
    void givenOlderConfigWithoutSwitches_whenLoaded_thenFeaturesStayOn() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("private-messages:\n  sound: none\nmoderation:\n  clear-lines: 50\n");
        Settings settings = new Settings(config, Logger.getLogger("test"));
        for (Settings.Feature feature : Settings.Feature.values()) assertEquals(feature.defaultOn, settings.enabled(feature), feature.name());
    }

    @Test
    void givenOneSwitchOff_whenLoaded_thenOnlyThatFeatureIsOff() throws Exception {
        java.util.Map<String, Settings.Feature> keys = java.util.Map.of("reports.enabled", Settings.Feature.REPORTS,
                "private-messages.enabled", Settings.Feature.PRIVATE_MESSAGES, "ignore.enabled", Settings.Feature.IGNORE,
                "mutes.enabled", Settings.Feature.MUTES);
        for (var entry : keys.entrySet()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
            config.set(entry.getKey(), false);
            Settings settings = new Settings(config, Logger.getLogger("test"));
            for (Settings.Feature feature : Settings.Feature.values())
                assertEquals(feature.defaultOn && feature != entry.getValue(), settings.enabled(feature), entry.getKey() + " -> " + feature);
        }
    }

    @Test
    void givenFeatureOffText_whenRead_thenEveryLanguageHasIt() {
        for (String file : new String[] {"en.yml", "de.yml", "zh_cn.yml", "fr.yml", "it.yml", "pt_br.yml", "ru.yml"})
            assertFalse(lang(file).getString("feature-off", "").isBlank(), file);
        assertEquals("<red>Dieses Feature ist auf diesem Server ausgeschaltet.", lang("de.yml").getString("feature-off"));
    }
}
