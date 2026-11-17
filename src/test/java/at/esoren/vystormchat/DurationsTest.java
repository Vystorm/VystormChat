package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DurationsTest {
    @Test
    void parsesUnitsAndCombinations() {
        assertEquals(30_000, Durations.parse("30s"));
        assertEquals(600_000, Durations.parse("10m"));
        assertEquals(7_200_000, Durations.parse("2H"));
        assertEquals(604_800_000L, Durations.parse("7d"));
        assertEquals(604_800_000L, Durations.parse("1w"));
        assertEquals(5_400_000, Durations.parse("1h30m"));
        assertEquals(300_000, Durations.parse("5"), "plain number = minutes");
    }

    @Test
    void rejectsGarbage() {
        assertEquals(-1, Durations.parse(""));
        assertEquals(-1, Durations.parse(null));
        assertEquals(-1, Durations.parse("abc"));
        assertEquals(-1, Durations.parse("10x"));
        assertEquals(-1, Durations.parse("h10"));
        assertEquals(-1, Durations.parse("10m reason"));
        assertEquals(-1, Durations.parse("0s"));
        assertEquals(-1, Durations.parse("99999999999"));
        assertEquals(-1, Durations.parse("999999999w"));
    }

    @Test
    void formatsShort() {
        assertEquals("12s", Durations.format(12_000));
        assertEquals("1s", Durations.format(1));
        assertEquals("5m 3s", Durations.format(303_000));
        assertEquals("2h", Durations.format(7_200_000));
        assertEquals("1d 2h", Durations.format(93_600_000));
        assertEquals("0s", Durations.format(-5));
    }

    @Test
    void playtime() {
        assertEquals("0m", Durations.playtime(0));
        assertEquals("5m", Durations.playtime(20 * 60 * 5));
        assertEquals("2h 3m", Durations.playtime(20L * 60 * 123));
        assertEquals("3d 4h", Durations.playtime(20L * 3600 * 76));
    }

    @Test
    void muteTimeRejectsMistypedTimesInsteadOfMutingForever() {
        assertEquals(1_800_000, Durations.muteTime("30m"));
        assertEquals(Durations.PERMANENT, Durations.muteTime("perm"));
        assertEquals(Durations.PERMANENT, Durations.muteTime("Permanent"));
        assertEquals(-1, Durations.muteTime("30min"), "a mistyped time must be rejected, not become permanent");
        assertEquals(-1, Durations.muteTime("2hours"));
        assertEquals(-1, Durations.muteTime("0"));
        assertEquals(Durations.NO_TIME, Durations.muteTime("spamming"), "a word starts the reason of a mute without time");
    }
}
