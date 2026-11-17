package at.esoren.vystormchat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Admin formats (MiniMessage, full tag set) with named placeholders and optional PlaceholderAPI {@code %tokens%}.
 * PlaceholderAPI results are inserted as plain text components, so their text can never inject tags; only results
 * of placeholders the admin trusts keep colours and tags.
 */
final class Formats {
    /** A name format for players with {@code permission} (empty = everyone). */
    record NameFormat(String id, String permission, String format) {}

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern PAPI = Pattern.compile("%([A-Za-z0-9_]+(?:_[^%\\s]+)?)%");
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder().character('§').hexColors().build();

    private final List<NameFormat> nameFormats;

    Formats(List<NameFormat> nameFormats) { this.nameFormats = List.copyOf(nameFormats); }

    static Formats parse(ConfigurationSection section) {
        List<NameFormat> list = new ArrayList<>();
        if (section != null) for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) continue;
            list.add(new NameFormat(key, entry.getString("permission", "").trim(), entry.getString("format", "<name>")));
        }
        if (list.isEmpty()) list.add(new NameFormat("default", "", "<prefix><player><suffix>"));
        return new Formats(list);
    }

    /** The first name format (config order) whose permission the player has. */
    NameFormat nameFormat(Predicate<String> permission) {
        for (NameFormat format : nameFormats) if (format.permission().isEmpty() || permission.test(format.permission())) return format;
        return nameFormats.getLast();
    }

    List<NameFormat> nameFormats() { return nameFormats; }

    /** Prefix/suffix/PlaceholderAPI text in any of the usual forms: {@code §}/{@code &} codes or MiniMessage. */
    static Component loose(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        if (text.indexOf('§') >= 0) return SECTION.deserialize(text);
        if (text.indexOf('&') >= 0 && text.matches("(?s).*&[0-9a-fk-orA-FK-OR#].*")) return AMPERSAND.deserialize(text);
        if (text.indexOf('<') >= 0) {
            try { return MINI.deserialize(text); } catch (RuntimeException notMini) { return Component.text(text); }
        }
        return Component.text(text);
    }

    /** A colour by name ("gold") or hex ("#ffaa00"); null when unknown or empty. */
    static TextColor color(String text) {
        if (text == null || text.isBlank()) return null;
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("#")) return TextColor.fromHexString(value);
        return NamedTextColor.NAMES.value(value);
    }

    /**
     * Parses an admin format. {@code %tokens%} are resolved with {@code papi} (null = left as written) and inserted
     * as components; {@code resolvers} provide the named tags.
     */
    static Component render(String format, Function<String, String> papi, TagResolver... resolvers) {
        return render(format, papi, token -> false, resolvers);
    }

    /** As {@link #render(String, Function, TagResolver...)}; {@code trusted} tokens keep colours and tags. */
    static Component render(String format, Function<String, String> papi, Predicate<String> trusted, TagResolver... resolvers) {
        String text = format;
        List<TagResolver> all = new ArrayList<>(List.of(resolvers));
        if (papi != null && text.indexOf('%') >= 0) {
            Matcher matcher = PAPI.matcher(text);
            StringBuilder out = new StringBuilder();
            int index = 0;
            while (matcher.find()) {
                String value;
                try { value = papi.apply(matcher.group()); } catch (RuntimeException | LinkageError failed) { value = matcher.group(); }
                String tag = "papi_" + index++;
                all.add(Placeholder.component(tag, placeholderValue(value, trusted.test(matcher.group()))));
                matcher.appendReplacement(out, Matcher.quoteReplacement("<" + tag + ">"));
            }
            text = matcher.appendTail(out).toString();
        }
        try {
            return MINI.deserialize(text, TagResolver.resolver(all));
        } catch (RuntimeException broken) {
            return Component.text(text);
        }
    }

    /**
     * A PlaceholderAPI result: trusted ones like prefixes ({@link #loose}), all others as plain text without colour
     * codes, so text players choose (nicknames, town names) can never add clicks, hovers, colours or other tags.
     */
    static Component placeholderValue(String value, boolean trusted) {
        if (trusted) return loose(value);
        if (value == null || value.isEmpty()) return Component.empty();
        String text = value;
        if (text.indexOf('§') >= 0) text = PLAIN.serialize(SECTION.deserialize(text));
        if (text.indexOf('&') >= 0 && text.matches("(?s).*&[0-9a-fk-orA-FK-OR#].*")) text = PLAIN.serialize(AMPERSAND.deserialize(text));
        return Component.text(text);
    }

    /** Tags every format may use for a player: player, display_name, prefix, suffix, group, world. */
    static TagResolver playerTags(PlayerState player) {
        return TagResolver.resolver(
                Placeholder.unparsed("player", player.name()),
                Placeholder.component("display_name", player.displayName()),
                Placeholder.component("prefix", loose(player.prefix())),
                Placeholder.component("suffix", loose(player.suffix())),
                Placeholder.unparsed("group", player.group()),
                Placeholder.unparsed("world", player.worldName() == null ? "" : player.worldName()));
    }
}
