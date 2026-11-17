package at.esoren.vystormchat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Emoji shortcodes: {@code :heart:} in a chat or private message becomes the symbol from {@code emojis.codes}
 * (any letter case). Unknown codes and colons in normal text stay as written.
 */
final class Emojis {
    private static final Pattern CODE = Pattern.compile(":([a-z0-9_+-]{1,32}):", Pattern.CASE_INSENSITIVE);

    static final Map<String, String> DEFAULTS = defaults();

    private Emojis() {}

    private static Map<String, String> defaults() {
        Map<String, String> map = new LinkedHashMap<>();
        String[][] pairs = {{"heart", "❤"}, {"star", "★"}, {"smile", "☺"}, {"sad", "☹"}, {"check", "✔"}, {"x", "✘"},
                {"sun", "☀"}, {"cloud", "☁"}, {"umbrella", "☂"}, {"snow", "❄"}, {"music", "♪"}, {"skull", "☠"},
                {"arrow", "➜"}, {"flower", "✿"}, {"coffee", "☕"}, {"warn", "⚠"}, {"peace", "☮"}, {"crown", "♔"}};
        for (String[] pair : pairs) map.put(pair[0], pair[1]);
        return java.util.Collections.unmodifiableMap(map);
    }

    /** Codes from the config section ({@code name: symbol}); names lower case, blank entries skipped. */
    static Map<String, String> read(ConfigurationSection section) {
        if (section == null) return DEFAULTS;
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String symbol = section.getString(key, "");
            String name = key.toLowerCase(Locale.ROOT).replace(":", "");
            if (!symbol.isEmpty() && CODE.matcher(":" + name + ":").matches()) map.put(name, symbol);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    static String apply(String text, Map<String, String> codes) {
        if (text == null || codes.isEmpty() || text.indexOf(':') < 0) return text;
        Matcher matcher = CODE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String symbol = codes.get(matcher.group(1).toLowerCase(Locale.ROOT));
            matcher.appendReplacement(out, Matcher.quoteReplacement(symbol == null ? matcher.group() : symbol));
        }
        return matcher.appendTail(out).toString();
    }
}
