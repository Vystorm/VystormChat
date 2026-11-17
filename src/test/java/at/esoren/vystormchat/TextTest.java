package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Player text (restricted formatting) and admin formats. */
class TextTest {
    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    @Test
    void withoutPermissionTagsStayText() {
        Component text = PlayerText.parse("<red>hi <click:run_command:/op me>x</click>", EnumSet.noneOf(PlayerText.Style.class));
        assertEquals("<red>hi <click:run_command:/op me>x</click>", plain(text));
    }

    @Test
    void colourPermissionAllowsColoursButNeverClicksOrHovers() {
        Set<PlayerText.Style> styles = EnumSet.of(PlayerText.Style.COLOR);
        Component text = PlayerText.parse("<red>hi</red> <click:run_command:'/op me'>x</click><hover:show_text:'fake'>y</hover>", styles);
        assertTrue(plain(text).contains("<click:run_command:'/op me'>"), "click stays literal text: " + plain(text));
        assertTrue(plain(text).contains("<hover:show_text:'fake'>"));
        assertFalse(contains(text, c -> c.clickEvent() != null));
        assertTrue(contains(text, c -> NamedTextColor.RED.equals(c.color())));
    }

    @Test
    void hexOnlyWithHexPermissionAndDecorationsSeparately() {
        Component noHex = PlayerText.parse("<#ff0000>x", EnumSet.of(PlayerText.Style.COLOR));
        assertEquals("<#ff0000>x", plain(noHex));
        Component hex = PlayerText.parse("<#ff0000>x", EnumSet.of(PlayerText.Style.HEX));
        assertEquals("x", plain(hex));
        assertTrue(contains(hex, c -> TextColor.color(0xff0000).equals(c.color())));
        Component bold = PlayerText.parse("<bold>b</bold><obfuscated>o</obfuscated>", EnumSet.of(PlayerText.Style.DECORATION));
        assertEquals("b<obfuscated>o</obfuscated>", plain(bold), "magic needs its own permission");
        assertTrue(contains(bold, c -> c.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE));
    }

    @Test
    void legacyCodesOnlyWithPermission() {
        assertEquals("<red>a<bold>b<reset>c<#00ff00>d", PlayerText.legacyToMini("&ca&lb&rc&#00FF00d"));
        Component legacy = PlayerText.parse("&cred", EnumSet.of(PlayerText.Style.COLOR, PlayerText.Style.LEGACY));
        assertEquals("red", plain(legacy));
        Component noLegacy = PlayerText.parse("&cred", EnumSet.of(PlayerText.Style.COLOR));
        assertEquals("&cred", plain(noLegacy));
    }

    @Test
    void plainTextForFilters() {
        Set<PlayerText.Style> styles = EnumSet.of(PlayerText.Style.COLOR);
        assertEquals("bad word", PlayerText.plain("<red>b</red>ad word", styles));
        assertEquals("<red>x", PlayerText.plain("<red>x", EnumSet.noneOf(PlayerText.Style.class)));
        assertTrue(PlayerText.formats(styles));
        assertFalse(PlayerText.formats(EnumSet.of(PlayerText.Style.LEGACY)));
    }

    @Test
    void stylesFromPermissions() {
        assertEquals(EnumSet.of(PlayerText.Style.COLOR, PlayerText.Style.GRADIENT),
                PlayerText.Style.of(Set.of("vystormchat.format.color", "vystormchat.format.gradient")::contains));
    }

    @Test
    void formatsRenderPlaceholdersAndPlaceholderApiSafely() {
        Component line = Formats.render("<gray>[<channel>]</gray> <name>: <message> %vault_rank%", token -> "<red>&lADMIN",
                Placeholder.unparsed("channel", "G"), Placeholder.unparsed("name", "Steve"), Placeholder.component("message", Component.text("<b>hi")));
        assertEquals("[G] Steve: <b>hi <red>ADMIN", plain(line), "PlaceholderAPI text is a component, its tags are not parsed");
        Component untouched = Formats.render("%a% <message>", null, Placeholder.unparsed("message", "x"));
        assertEquals("%a% x", plain(untouched));
        assertEquals("<oops", plain(Formats.render("<oops", null)));
    }

    @Test
    void placeholderApiResultsArePlainTextUnlessTrusted() {
        String nick = "<click:run_command:'/op me'><hover:show_text:'x'><red>Nick</red></hover></click>";
        Component untrusted = Formats.render("<name> %nick_name% %other%", token -> token.equals("%other%") ? "&cRed§lBold" : nick,
                Placeholder.unparsed("name", "Steve"));
        assertEquals("Steve " + nick + " RedBold", plain(untrusted), "tags shown as written, colour codes removed");
        assertFalse(styled(untrusted), "no click, hover or colour from a player-controlled placeholder");

        Component trusted = Formats.render("%nick_name%", token -> nick, token -> Settings.placeholderKey(token).equals("nick_name"));
        assertEquals("Nick", plain(trusted));
        assertTrue(styled(trusted), "an admin-trusted placeholder keeps its tags");
    }

    private static boolean styled(Component component) {
        if (component.clickEvent() != null || component.hoverEvent() != null || component.color() != null) return true;
        for (Component child : component.children()) if (styled(child)) return true;
        return false;
    }

    @Test
    void looseTextForPrefixes() {
        assertEquals("[Admin] ", plain(Formats.loose("&c[Admin] ")));
        assertEquals("[Admin]", plain(Formats.loose("§4[Admin]")));
        assertEquals("[Mod]", plain(Formats.loose("<blue>[Mod]")));
        assertEquals("Tom & Jerry", plain(Formats.loose("Tom & Jerry")));
        assertEquals("", plain(Formats.loose(null)));
        assertEquals(NamedTextColor.GOLD, Formats.color("Gold"));
        assertEquals(TextColor.color(0x112233), Formats.color("#112233"));
        assertNull(Formats.color("nope"));
    }

    @Test
    void nameFormatsUseTheFirstMatchingPermission() {
        Formats formats = new Formats(List.of(new Formats.NameFormat("admin", "x.admin", "A"), new Formats.NameFormat("vip", "x.vip", "V"),
                new Formats.NameFormat("default", "", "D")));
        assertEquals("A", formats.nameFormat(Set.of("x.admin", "x.vip")::contains).format());
        assertEquals("V", formats.nameFormat(Set.of("x.vip")::contains).format());
        assertEquals("D", formats.nameFormat(permission -> false).format());
        assertEquals("<prefix><player><suffix>", Formats.parse(null).nameFormats().getFirst().format());
    }

    @Test
    void playerTags() {
        PlayerState state = new PlayerState(java.util.UUID.randomUUID(), "Steve", null, null, "world", 0, 0, 0, null, null, null, null, null, null, null,
                "admin", "&c[A] ", "", 0);
        assertEquals("[A] Steve admin world", plain(Formats.render("<prefix><player> <group> <world>", null, Formats.playerTags(state))));
    }

    private static boolean contains(Component component, java.util.function.Predicate<Component> test) {
        if (test.test(component)) return true;
        for (Component child : component.children()) if (contains(child, test)) return true;
        return false;
    }
}
