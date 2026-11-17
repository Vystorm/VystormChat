package at.esoren.vystormchat;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

/**
 * Text players type. Without a formatting permission it stays plain text (tags show as typed). With permissions,
 * players may use a safe subset of MiniMessage: colours, hex colours, bold/italic/underline/strikethrough,
 * obfuscated, gradients/rainbow. Click, hover, insertion, fonts, selectors, translations, NBT etc. are never parsed,
 * so a player can never create commands or fake hovers. Legacy {@code &} codes are converted only with
 * {@link Style#LEGACY}.
 */
final class PlayerText {
    enum Style {
        COLOR("vystormchat.format.color"),
        HEX("vystormchat.format.hex"),
        DECORATION("vystormchat.format.decoration"),
        MAGIC("vystormchat.format.magic"),
        GRADIENT("vystormchat.format.gradient"),
        LEGACY("vystormchat.format.legacy");

        final String permission;

        Style(String permission) {
            this.permission = permission;
        }

        static Set<Style> of(Predicate<String> permission) {
            Set<Style> styles = EnumSet.noneOf(Style.class);
            for (Style style : values()) if (permission.test(style.permission)) styles.add(style);
            return styles;
        }
    }

    private static final Pattern HEX_TAG = Pattern.compile("(?i)<(?=/?(?:#[0-9a-f]{6}|(?:color|colour|c):#))");
    private static final Pattern LEGACY = Pattern.compile("(?i)[&§](#[0-9a-f]{6}|[0-9a-fk-or])");

    private PlayerText() {}

    /** True when the player may format at all (then text is parsed as MiniMessage subset). */
    static boolean formats(Set<Style> styles) {
        return styles.contains(Style.COLOR) || styles.contains(Style.HEX) || styles.contains(Style.DECORATION)
                || styles.contains(Style.MAGIC) || styles.contains(Style.GRADIENT);
    }

    static MiniMessage miniMessage(Set<Style> styles) {
        TagResolver.Builder tags = TagResolver.builder();
        if (styles.contains(Style.COLOR) || styles.contains(Style.HEX)) tags.resolver(StandardTags.color());
        if (styles.contains(Style.DECORATION)) {
            tags.resolver(StandardTags.decorations(TextDecoration.BOLD));
            tags.resolver(StandardTags.decorations(TextDecoration.ITALIC));
            tags.resolver(StandardTags.decorations(TextDecoration.UNDERLINED));
            tags.resolver(StandardTags.decorations(TextDecoration.STRIKETHROUGH));
        }
        if (styles.contains(Style.MAGIC)) tags.resolver(StandardTags.decorations(TextDecoration.OBFUSCATED));
        if (styles.contains(Style.GRADIENT)) {
            tags.resolver(StandardTags.gradient());
            tags.resolver(StandardTags.rainbow());
        }
        if (formats(styles)) tags.resolver(StandardTags.reset());
        return MiniMessage.builder().tags(tags.build()).strict(false).build();
    }

    /** Prepares raw text for parsing: legacy codes converted (when allowed), forbidden hex tags escaped. */
    static String prepare(String raw, Set<Style> styles) {
        String text = raw;
        if (styles.contains(Style.LEGACY)) text = legacyToMini(text);
        if (!styles.contains(Style.HEX)) text = HEX_TAG.matcher(text).replaceAll("\\\\<");
        if (styles.contains(Style.HEX) && !styles.contains(Style.COLOR)) {
            // only hex: escape named colour tags by parsing named ones as text
            text = text.replaceAll("(?i)<(?=/?(?:black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|grey|dark_gray|dark_grey|blue|green|aqua|red|light_purple|yellow|white)>)", "\\\\<");
        }
        return text;
    }

    /** {@code &c} → {@code <red>}, {@code &l} → {@code <bold>}, {@code &r} → {@code <reset>}, {@code &#ff0000} → {@code <#ff0000>}. */
    static String legacyToMini(String text) {
        Matcher matcher = LEGACY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String code = matcher.group(1).toLowerCase(Locale.ROOT);
            String tag = code.startsWith("#") ? "<" + code + ">" : switch (code.charAt(0)) {
                case '0' -> "<black>";
                case '1' -> "<dark_blue>";
                case '2' -> "<dark_green>";
                case '3' -> "<dark_aqua>";
                case '4' -> "<dark_red>";
                case '5' -> "<dark_purple>";
                case '6' -> "<gold>";
                case '7' -> "<gray>";
                case '8' -> "<dark_gray>";
                case '9' -> "<blue>";
                case 'a' -> "<green>";
                case 'b' -> "<aqua>";
                case 'c' -> "<red>";
                case 'd' -> "<light_purple>";
                case 'e' -> "<yellow>";
                case 'f' -> "<white>";
                case 'k' -> "<obfuscated>";
                case 'l' -> "<bold>";
                case 'm' -> "<strikethrough>";
                case 'n' -> "<underlined>";
                case 'o' -> "<italic>";
                default -> "<reset>";
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(tag));
        }
        return matcher.appendTail(out).toString();
    }

    /** The message as a component (plain text without formatting permissions). */
    static Component parse(String raw, Set<Style> styles) {
        if (!formats(styles)) return Component.text(raw);
        return miniMessage(styles).deserialize(prepare(raw, styles));
    }

    /** The text without formatting, for filters, mentions and logs. */
    static String plain(String raw, Set<Style> styles) {
        if (!formats(styles)) return raw;
        return miniMessage(styles).stripTags(prepare(raw, styles)).replace("\\<", "<");
    }
}
