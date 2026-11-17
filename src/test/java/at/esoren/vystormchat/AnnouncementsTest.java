package at.esoren.vystormchat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnnouncementsTest {
    @Test
    void givenIntervals_whenChecked_thenDueOnlyAfterTheirMinutes() {
        var every10 = new Announcements.Announcement(1, "Vote for us!", 10, "", true);
        assertTrue(Announcements.due(every10, null, 0), "never sent");
        assertFalse(Announcements.due(every10, 0L, 599_999));
        assertTrue(Announcements.due(every10, 0L, 600_000));
        assertFalse(Announcements.due(new Announcements.Announcement(2, "x", 10, "", false), null, 0), "paused");
    }

    @Test
    void givenOutOfRangeValues_whenCreated_thenClamped() {
        assertEquals(1, new Announcements.Announcement(1, "x", 0, null, true).minutes());
        assertEquals(10_080, new Announcements.Announcement(1, "x", 99_999, null, true).minutes());
        assertEquals("", new Announcements.Announcement(1, "x", 5, null, true).world());
    }
}
