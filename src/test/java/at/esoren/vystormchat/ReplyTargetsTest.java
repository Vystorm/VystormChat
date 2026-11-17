package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplyTargetsTest {
    private final ReplyTargets replies = new ReplyTargets();
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), charlie = UUID.randomUUID();

    @Test
    void unlockedPartnersFollowBothIncomingAndOutgoingMessages() {
        replies.delivered(alice, bob);
        assertEquals(bob, replies.partner(alice));
        assertEquals(alice, replies.partner(bob));
        replies.delivered(charlie, alice);
        assertEquals(charlie, replies.partner(alice));
        replies.delivered(alice, bob);
        assertEquals(bob, replies.partner(alice));
    }

    @Test
    void lockedPartnerSurvivesIncomingAndOutgoingMessages() {
        replies.delivered(alice, bob);
        assertTrue(replies.lock(alice));
        replies.delivered(charlie, alice);
        assertEquals(bob, replies.partner(alice));
        assertEquals(alice, replies.partner(charlie));
        replies.delivered(alice, charlie);
        assertEquals(bob, replies.partner(alice));
        assertEquals(alice, replies.partner(charlie));
    }

    @Test
    void unlockKeepsPartnerUntilNextSuccessfulDelivery() {
        replies.delivered(alice, bob);
        replies.lock(alice);
        replies.delivered(charlie, alice);
        assertTrue(replies.unlock(alice));
        assertEquals(bob, replies.partner(alice));
        replies.delivered(charlie, alice);
        assertEquals(charlie, replies.partner(alice));
        assertFalse(replies.unlock(alice));
    }

    @Test
    void cannotLockWithoutAConversation() {
        assertFalse(replies.lock(alice));
        replies.delivered(bob, alice);
        replies.delivered(charlie, alice);
        assertEquals(charlie, replies.partner(alice));
    }

    @Test
    void logoutClearsOwnLockButDoesNotRedirectOtherPlayers() {
        replies.delivered(alice, bob);
        replies.lock(alice);
        replies.lock(bob);
        replies.forget(alice);
        assertNull(replies.partner(alice));
        assertFalse(replies.unlock(alice));
        assertEquals(alice, replies.partner(bob));
        replies.delivered(charlie, bob);
        assertEquals(alice, replies.partner(bob));
        replies.delivered(charlie, alice);
        assertEquals(charlie, replies.partner(alice));
    }

    @Test
    void consoleCanBeLockedLikeAPlayer() {
        replies.delivered(PrivateMessages.CONSOLE, alice);
        assertTrue(replies.lock(alice));
        replies.delivered(bob, alice);
        assertEquals(PrivateMessages.CONSOLE, replies.partner(alice));
    }
}
