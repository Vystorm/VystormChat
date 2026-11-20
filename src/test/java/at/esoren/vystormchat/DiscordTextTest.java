package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Text between Minecraft and Discord (1.18.0): escaping, mention neutralising, length cut, Discord markup as plain text. */
class DiscordTextTest {
    @Test void givenPlayerText_whenSentToDiscord_thenMarkdownShowsAsTyped() {
        assertEquals("\\*\\*not bold\\*\\* and \\_x\\_ \\~\\~y\\~\\~ \\`z\\` \\|\\|s\\|\\| \\[a\\](b)", DiscordText.escapeMarkdown("**not bold** and _x_ ~~y~~ `z` ||s|| [a](b)"));
        assertEquals("\\# heading", DiscordText.escapeMarkdown("# heading"), "a heading marker only at the line start");
        assertEquals("a # b - c > d", DiscordText.escapeMarkdown("a # b - c > d"));
        assertEquals("\\> quote", DiscordText.escapeMarkdown("> quote"));
    }

    @Test void givenMassMentions_whenSentToDiscord_thenTheyAreBroken() {
        String out = DiscordText.content("@everyone and @HERE look <@123> <@&456>", false);
        assertFalse(out.contains("@everyone") || out.contains("@HERE"), out);
        assertTrue(out.contains("@​everyone") && out.contains("@​HERE"), out);
        assertFalse(out.contains("<@123>") || out.contains("<@&456>"), out);
        String users = DiscordText.content("hi <@123> <@&456>", true);
        assertTrue(users.contains("<@123>"), "user mentions stay when allowed");
        assertFalse(users.contains("<@&456>"), "roles never");
    }

    @Test void givenALongText_whenCut_thenDiscordsLimitHoldsAndCodePointsStayWhole() {
        String long3000 = "a".repeat(3000);
        String cut = DiscordText.content(long3000, false);
        assertEquals(DiscordText.MAX_CONTENT, cut.codePointCount(0, cut.length()));
        assertTrue(cut.endsWith("…"));
        String emoji = "😀".repeat(2500);
        String cutEmoji = DiscordText.cut(emoji, 2000);
        assertEquals(2000, cutEmoji.codePointCount(0, cutEmoji.length()));
        assertFalse(Character.isHighSurrogate(cutEmoji.charAt(cutEmoji.length() - 2)) && cutEmoji.charAt(cutEmoji.length() - 1) == '…');
        assertEquals("short", DiscordText.cut("short", 2000));
    }

    @Test void givenANameWithForbiddenWords_whenUsedAsWebhookName_thenDiscordAcceptsIt() {
        assertEquals("Disc0rdFan", DiscordText.webhookName("DiscordFan"));
        assertEquals("clyd3", DiscordText.webhookName("clyde"));
        assertEquals("Player", DiscordText.webhookName("  "));
        assertEquals(80, DiscordText.webhookName("x".repeat(100)).length());
    }

    @Test void givenATemplate_whenFilled_thenUuidAndNameAreInserted() {
        UUID id = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertEquals("https://mc-heads.net/avatar/069a79f4-44e9-4726-a5be-fca90e38aaf5/128", DiscordText.fill(DiscordConfig.DEFAULT_AVATAR, id, "Notch", true));
        assertEquals("069a79f444e94726a5befca90e38aaf5 .Bed+Rock", DiscordText.fill("{uuid-plain} {name}", id, ".Bed Rock", true));
        assertEquals(".Bed Rock", DiscordText.fill("{name}", id, ".Bed Rock", false));
    }

    @Test void givenDiscordMarkup_whenShownInMinecraft_thenItIsPlainText() {
        Map<String, String> users = Map.of("111", "Carl");
        assertEquals("@Carl hi :pog: bold italic under strike spoiler code @role #channel @user",
                DiscordText.fromDiscord("<@111> hi <a:pog:222> **bold** *italic* __under__ ~~strike~~ ||spoiler|| `code` <@&5> <#6> <@!999>", users));
        assertEquals("look (https://example.com)", DiscordText.fromDiscord("[look](https://example.com)", users));
        assertEquals("line one line two", DiscordText.fromDiscord("line one\nline two", users));
        assertEquals("quoted", DiscordText.fromDiscord("> quoted", users));
        assertEquals("heading", DiscordText.fromDiscord("# heading", users));
        assertEquals("snake_case_word stays", DiscordText.fromDiscord("snake_case_word stays", users));
        assertEquals("2023-11-14 22:13 UTC", DiscordText.fromDiscord("<t:1700000000:R>", users));
        assertEquals("int x = 1;", DiscordText.fromDiscord("```java\nint x = 1;\n```", users));
        assertEquals("*literal*", DiscordText.fromDiscord("\\*literal\\*", users));
        assertEquals("<red>stays text</red>", DiscordText.fromDiscord("<red>stays text</red>", users), "tags are inserted unparsed later");
    }

    @Test void givenAMember_whenNamed_thenNicknameWins() {
        assertEquals("Nick", DiscordText.displayName("Nick", "Global", "user"));
        assertEquals("Global", DiscordText.displayName(null, "Global", "user"));
        assertEquals("user", DiscordText.displayName(" ", null, "user"));
    }
}
