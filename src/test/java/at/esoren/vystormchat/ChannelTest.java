package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChannelTest {
    @Test
    void defaultsForAMinimalChannel() {
        Channel channel = TestChannels.one("scope: local");
        assertEquals("test", channel.id());
        assertEquals("Test", channel.displayName());
        assertEquals(Channel.Scope.LOCAL, channel.scope());
        assertEquals(100, channel.radius());
        assertTrue(channel.leavable());
        assertTrue(channel.autoJoin());
        assertFalse(channel.spy());
        assertEquals("", channel.quickPrefix());
        assertEquals(0, channel.cooldownSeconds());
        assertEquals(0.0, channel.cost());
        assertEquals("global", channel.clientTab());
        assertTrue(channel.readable(permission -> false), "no permission set = everyone");
    }

    @Test
    void readsEverything() {
        Channel channel = TestChannels.one("""
                display-name: Handel
                scope: region
                quick-prefix: "$"
                aliases: [T, /tc, "bad alias"]
                read-permission: a.read
                write-permission: a.write
                cooldown: -5
                cost: 2.5
                worlds: [World, nether]
                region-kinds: [City]
                client-tab: weird
                forward-command: /partychat
                core-trade: true
                """);
        assertEquals("Handel", channel.displayName());
        assertEquals(Channel.Scope.REGION, channel.scope());
        assertEquals(List.of("t", "tc"), channel.aliases());
        assertEquals(0, channel.cooldownSeconds(), "negative cooldown clamps to 0");
        assertEquals(2.5, channel.cost());
        assertEquals(Set.of("world", "nether"), channel.worlds());
        assertTrue(channel.inWorld("WORLD"));
        assertFalse(channel.inWorld("the_end"));
        assertEquals(Set.of("city"), channel.regionKinds());
        assertEquals("none", channel.clientTab(), "unknown tabs are switched off");
        assertEquals("partychat", channel.forwardCommand());
        assertTrue(channel.coreTrade());
        assertFalse(channel.readable(Set.of("a.write")::contains));
        assertTrue(channel.writable(Set.of("a.write")::contains));
    }

    @Test
    void brokenChannelsAreSkippedAndDuplicatesIgnored() {
        Map<String, Channel> channels = TestChannels.parse("""
                channels:
                  good:
                    quick-prefix: "!"
                    aliases: [g]
                  "bad name":
                    scope: global
                  wrong:
                    scope: galaxy
                  second:
                    quick-prefix: "!"
                    aliases: [g, s]
                  notamap: 5
                """);
        assertEquals(List.of("good", "second"), List.copyOf(channels.keySet()));
        assertEquals("", channels.get("second").quickPrefix(), "a prefix used twice only works for the first channel");
        assertEquals(List.of("s"), channels.get("second").aliases());
    }

    @Test
    void quickPrefixPicksTheLongestAndIgnoresPunctuationOnly() {
        Map<String, Channel> channels = TestChannels.parse("""
                channels:
                  global:
                    quick-prefix: "!"
                  staff:
                    quick-prefix: "!!"
                  trade:
                    quick-prefix: "$"
                """);
        Channel.Prefixed global = Channel.quickPrefix(channels.values(), "!hello all");
        assertEquals("global", global.channel().id());
        assertEquals("hello all", global.text());
        Channel.Prefixed staff = Channel.quickPrefix(channels.values(), "!! team only");
        assertEquals("staff", staff.channel().id());
        assertEquals("team only", staff.text());
        assertNull(Channel.quickPrefix(channels.values(), "!"));
        assertNull(Channel.quickPrefix(channels.values(), "!!!!"), "just exclamation marks are a normal message");
        assertNull(Channel.quickPrefix(channels.values(), "$ "));
        assertNull(Channel.quickPrefix(channels.values(), "hello!"));
        assertEquals("trade", Channel.quickPrefix(channels.values(), "$selling dirt").channel().id());
    }

    @Test
    void scopeParsing() {
        assertEquals(Channel.Scope.PARTY, Channel.Scope.parse(" Party "));
        assertNull(Channel.Scope.parse("nope"));
        assertNull(Channel.Scope.parse(null));
        assertTrue(Channel.Scope.CITY.group());
        assertFalse(Channel.Scope.LOCAL.group());
    }
}
