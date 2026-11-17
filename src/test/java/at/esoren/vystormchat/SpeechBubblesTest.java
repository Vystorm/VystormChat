package at.esoren.vystormchat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpeechBubblesTest {
    @Test
    void givenShortText_whenWrapped_thenOneLine() {
        assertEquals(List.of("hello there"), SpeechBubbles.wrap("  hello   there ", 24, 3));
    }

    @Test
    void givenLongText_whenWrapped_thenLinesAtSpacesAndCut() {
        List<String> lines = SpeechBubbles.wrap("the quick brown fox jumps over the lazy dog again and again", 12, 3);
        assertEquals(3, lines.size());
        assertTrue(lines.stream().allMatch(l -> l.length() <= 13));
        assertTrue(lines.getLast().endsWith("…"));
        assertEquals("the quick", lines.getFirst());
    }

    @Test
    void givenVeryLongWord_whenWrapped_thenItIsSplit() {
        List<String> lines = SpeechBubbles.wrap("aaaaaaaaaaaaaaaaaaaaaaaaa", 10, 3);
        assertEquals(List.of("aaaaaaaaaa", "aaaaaaaaaa", "aaaaa"), lines);
    }
}
