package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Anti-spam, word filter, link filter and mentions. */
class ModerationTest {
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    private static SpamGuard guard(boolean capsBlock) {
        SpamGuard guard = new SpamGuard();
        guard.configure(new SpamGuard.Settings(true, 30_000, 4, 5_000, 8, 70, capsBlock, 4));
        return guard;
    }

    @Test
    void repeatIsBlockedIgnoringCaseAndPunctuation() {
        SpamGuard guard = guard(false);
        assertEquals(SpamGuard.Verdict.OK, guard.check(A, "Hello there", 0).verdict());
        assertEquals(SpamGuard.Verdict.REPEAT, guard.check(A, "hello there!!", 1_000).verdict());
        assertEquals(SpamGuard.Verdict.OK, guard.check(B, "hello there", 1_000).verdict(), "other players are independent");
        assertEquals(SpamGuard.Verdict.OK, guard.check(A, "hello there", 40_000).verdict(), "after the repeat time it is fine again");
    }

    @Test
    void privateConversationsHaveTheirOwnHistory() {
        SpamGuard guard = guard(false);
        assertEquals(SpamGuard.Verdict.OK, guard.check(A + ":msg:Steve", "hi", 0).verdict());
        assertEquals(SpamGuard.Verdict.OK, guard.check(A + ":msg:Alex", "hi", 10).verdict(), "the same greeting to someone else is fine");
        assertEquals(SpamGuard.Verdict.OK, guard.check(A, "hi", 20).verdict());
        assertEquals(SpamGuard.Verdict.REPEAT, guard.check(A + ":msg:Steve", "hi", 30).verdict());
        guard.forget(A);
        assertEquals(SpamGuard.Verdict.OK, guard.check(A + ":msg:Steve", "hi", 40).verdict(), "forget clears every key of the player");
    }

    @Test
    void floodLimit() {
        SpamGuard guard = guard(false);
        for (int i = 0; i < 4; i++) assertEquals(SpamGuard.Verdict.OK, guard.check(A, "message " + i, i * 100L).verdict());
        assertEquals(SpamGuard.Verdict.FLOOD, guard.check(A, "one more", 500).verdict());
        assertEquals(SpamGuard.Verdict.OK, guard.check(A, "later", 5_200).verdict(), "the window moved on");
    }

    @Test
    void blockedMessagesDoNotCountAsSent() {
        SpamGuard guard = guard(false);
        guard.check(A, "same", 0);
        assertTrue(guard.check(A, "same", 10).blocked());
        assertTrue(guard.check(A, "same", 20).blocked());
        assertEquals(SpamGuard.Verdict.OK, guard.check(A, "new", 30).verdict());
    }

    @Test
    void capsAndRepeatedLetters() {
        SpamGuard guard = guard(false);
        SpamGuard.Result result = guard.check(A, "WHY IS NOBODY ONLINE", 0);
        assertEquals(SpamGuard.Verdict.OK, result.verdict());
        assertEquals("Why is nobody online", result.text());
        assertEquals("OK GG", guard.check(A, "OK GG", 10_000).text(), "short messages may be upper case");
        assertEquals("heeeey", guard.check(A, "heeeeeeeeey", 20_000).text());
        assertEquals(SpamGuard.Verdict.CAPS, guard(true).check(A, "STOP SHOUTING NOW", 0).verdict());
        assertEquals(SpamGuard.Verdict.OK, new SpamGuard().check(A, "OFF BY DEFAULT EVERYTHING GOES", 0).verdict());
    }

    @Test
    void helpers() {
        assertEquals("helloworld", SpamGuard.normalize("Hello, World!"));
        assertEquals("aaa", SpamGuard.collapse("aaaaaa", 3));
        assertTrue(SpamGuard.shouting("ABCDEFGH", 8, 70));
        assertFalse(SpamGuard.shouting("ABCDEFG", 8, 70));
        assertFalse(SpamGuard.shouting("ABCDEFGH", 8, 100));
    }

    @Test
    void wordFilterFindsWholeWordsAndLookAlikes() {
        WordFilter filter = WordFilter.of(List.of("bad", "Ass"), List.of(), "*", null);
        assertEquals("this is *** stuff", filter.apply("this is bad stuff").text());
        assertEquals("this is ***** stuff", filter.apply("this is b.a.d stuff").text());
        assertEquals("****", filter.apply("B4AD").text());
        assertTrue(filter.apply("b4d").matched());
        assertTrue(filter.apply("baaad").matched());
        assertFalse(filter.apply("badge class bass").matched(), "inside other words is fine");
        assertEquals("a *** b", filter.apply("a @ss b").text());
    }

    @Test
    void wordFilterRegexAndReplacementText() {
        WordFilter filter = WordFilter.of(List.of(), List.of("disc(or)?d\\.gg/\\w+", "([)"), "[removed]", null);
        WordFilter.Result result = filter.apply("join discord.gg/abc now");
        assertTrue(result.matched());
        assertEquals("join [removed] now", result.text());
        assertTrue(WordFilter.NONE.empty());
        assertFalse(WordFilter.NONE.apply("anything").matched());
    }

    @Test
    void linksAreFound() {
        assertEquals(List.of("https://example.com/a?b=1"), texts("see https://example.com/a?b=1."));
        assertEquals(List.of("www.test.org"), texts("go to www.test.org!"));
        assertEquals(List.of("play.server.de"), texts("ip: play.server.de"));
        assertEquals(List.of("192.168.0.1:25565"), texts("join 192.168.0.1:25565"));
        assertEquals(List.of(), texts("hi.you ok, e.g. this, 1.21.8 update, version 1.2.3"));
        assertEquals(List.of(), texts("mail me at a@b"));
    }

    private static List<String> texts(String text) { return Links.find(text).stream().map(Links.Found::text).toList(); }

    @Test
    void linkHostsAndAllowedDomains() {
        assertEquals("vystorm.net", Links.host("https://www.vystorm.net/wiki"));
        assertEquals("shop.vystorm.net", Links.host("shop.vystorm.net:8080/x"));
        assertTrue(Links.allowed("https://shop.vystorm.net/x", Set.of("vystorm.net")));
        assertFalse(Links.allowed("https://evilvystorm.net", Set.of("vystorm.net")));
        assertEquals("https://example.com", Links.url("example.com"));
        assertEquals("http://example.com", Links.url("http://example.com"));
        assertEquals("visit [x] or vystorm.net", Links.censor("visit spam.com or vystorm.net", Set.of("vystorm.net"), "[x]"));
        assertNull(Links.censor("nothing here", Set.of(), "[x]"));
    }

    @Test
    void mentions() {
        Map<String, UUID> online = Map.of("steve", A, "alex", B, "me", UUID.randomUUID());
        List<Mentions.Hit> hits = Mentions.find("hey @Steve and alex, ok me", online, true, false);
        assertEquals(Set.of(A, B), Mentions.players(hits));
        assertEquals(Set.of(A), Mentions.players(Mentions.find("hey @Steve and alex", online, false, false)), "plain names off");
        assertEquals(Set.of(), Mentions.players(Mentions.find("steves alexander email@steve", online, true, false)));
        assertEquals(Set.of(), Mentions.players(Mentions.find("me and you", online, true, false)), "short plain names never ping");
        assertTrue(Mentions.everyone(Mentions.find("@everyone look", online, true, true)));
        assertFalse(Mentions.everyone(Mentions.find("@everyone look", online, true, false)));
        assertFalse(Mentions.everyone(Mentions.find("@here", online, true, false)));
    }
}
