package at.esoren.vystormchat;

import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Builds channels from YAML text, the same way the plugin reads config.yml. */
final class TestChannels {
    private TestChannels() {}

    static Map<String, Channel> parse(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try { config.loadFromString(yaml); } catch (InvalidConfigurationException broken) { throw new IllegalArgumentException(broken); }
        return Channel.parseAll(config.getConfigurationSection("channels"), null);
    }

    static Channel one(String body) {
        return parse("channels:\n  test:\n" + body.indent(4)).get("test");
    }
}
