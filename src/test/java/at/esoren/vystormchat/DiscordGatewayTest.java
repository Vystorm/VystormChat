package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The gateway client against an in-memory Discord (1.18.0): hello, identify with the two intents, heartbeat and ack,
 * zombie detection, resume at the resume URL, reconnect requests, invalid sessions, fatal close codes, growing waits,
 * clean close. The token appears only in identify and resume payloads, never in the log or the error text.
 */
class DiscordGatewayTest {
    private final DiscordFakes.Gateway discord = new DiscordFakes.Gateway();
    private final DiscordFakes.LogCatcher log = new DiscordFakes.LogCatcher();
    private final AtomicLong clock = new AtomicLong(5_000_000L);
    private final List<JsonObject> messages = new CopyOnWriteArrayList<>();
    private final DiscordGateway gateway = new DiscordGateway(discord, clock::get, log.logger, DiscordFakes.TOKEN,
            URI.create("wss://gateway.invalid"), messages::add, () -> 0.5);

    @AfterEach void noSecrets() {
        assertFalse(log.all().contains(DiscordFakes.TOKEN), log.all());
        assertFalse(gateway.lastError().contains(DiscordFakes.TOKEN));
    }

    private DiscordFakes.Gateway.Connection connectReady() {
        gateway.start();
        DiscordFakes.Gateway.Connection first = discord.last();
        first.hello(40_000);
        first.ready(1, "session-1", "wss://resume.invalid", "999");
        return first;
    }

    @Test void givenHello_whenConnecting_thenIdentifyWithGuildMessagesAndMessageContent() {
        gateway.start();
        DiscordFakes.Gateway.Connection connection = discord.last();
        assertEquals("wss://gateway.invalid/?v=10&encoding=json", connection.uri.toString());
        assertEquals(DiscordGateway.State.CONNECTING, gateway.state());
        connection.hello(41_250);
        JsonObject identify = connection.lastSent();
        assertEquals(2, identify.get("op").getAsInt());
        JsonObject d = identify.getAsJsonObject("d");
        assertEquals(DiscordFakes.TOKEN, d.get("token").getAsString());
        assertEquals(512 | 32768, d.get("intents").getAsInt(), "GUILD_MESSAGES + MESSAGE_CONTENT, nothing else");
        assertTrue(d.getAsJsonObject("properties").has("os"));
        connection.ready(1, "session-1", "wss://resume.invalid", "999");
        assertEquals(DiscordGateway.State.READY, gateway.state());
        assertEquals("999", gateway.selfId());
        assertEquals("session-1", gateway.sessionId());
    }

    @Test void givenTheInterval_whenPulsed_thenHeartbeatsCarryTheLastSequence() {
        DiscordFakes.Gateway.Connection connection = connectReady();
        clock.addAndGet(19_999);
        gateway.pulse();
        assertEquals(List.of(2), connection.ops(), "first beat after interval * jitter (0.5)");
        clock.addAndGet(1);
        gateway.pulse();
        assertEquals(1, connection.lastSent().get("op").getAsInt());
        assertEquals(1, connection.lastSent().get("d").getAsInt(), "sequence of READY");
        connection.events.text("{\"op\":11}");
        connection.dispatch("MESSAGE_CREATE", 2, "{\"id\":\"1\",\"content\":\"hi\"}");
        assertEquals(1, messages.size());
        clock.addAndGet(40_000);
        gateway.pulse();
        assertEquals(2, connection.lastSent().get("d").getAsInt());
        connection.events.text("{\"op\":1,\"d\":null}");
        assertEquals(1, connection.lastSent().get("op").getAsInt(), "Discord asked for a beat: sent at once");
    }

    @Test void givenNoAck_whenTheNextBeatIsDue_thenReconnectAndResumeAtTheResumeUrl() {
        DiscordFakes.Gateway.Connection first = connectReady();
        clock.addAndGet(20_000);
        gateway.pulse(); // beat, no ack follows
        clock.addAndGet(40_000);
        gateway.pulse();
        assertEquals(4000, first.closeCode, "zombie connection closed with a non-1000 code (session stays resumable)");
        assertEquals(DiscordGateway.State.WAITING, gateway.state());
        assertEquals(1, discord.connections.size());
        clock.addAndGet(1000 + 125);
        gateway.pulse();
        assertEquals(2, discord.connections.size());
        DiscordFakes.Gateway.Connection second = discord.last();
        assertEquals("wss://resume.invalid/?v=10&encoding=json", second.uri.toString());
        second.hello(40_000);
        JsonObject resume = second.lastSent();
        assertEquals(6, resume.get("op").getAsInt());
        assertEquals("session-1", resume.getAsJsonObject("d").get("session_id").getAsString());
        assertEquals(1, resume.getAsJsonObject("d").get("seq").getAsInt());
        assertEquals(DiscordFakes.TOKEN, resume.getAsJsonObject("d").get("token").getAsString());
        assertEquals(DiscordGateway.State.RESUMING, gateway.state());
        second.dispatch("RESUMED", 2, "{}");
        assertEquals(DiscordGateway.State.READY, gateway.state());
        assertEquals(0, gateway.attempts());
        first.events.text("{\"op\":0,\"t\":\"MESSAGE_CREATE\",\"s\":9,\"d\":{\"content\":\"late\"}}");
        assertTrue(messages.isEmpty(), "events of the old connection are ignored");
    }

    @Test void givenReconnectRequest_whenReceived_thenResume() {
        DiscordFakes.Gateway.Connection first = connectReady();
        first.events.text("{\"op\":7,\"d\":null}");
        assertEquals(DiscordGateway.State.WAITING, gateway.state());
        clock.addAndGet(2000);
        gateway.pulse();
        discord.last().hello(40_000);
        assertEquals(6, discord.last().lastSent().get("op").getAsInt());
    }

    @Test void givenAnInvalidSessionThatCannotResume_whenReconnecting_thenIdentifyFresh() {
        DiscordFakes.Gateway.Connection first = connectReady();
        first.events.text("{\"op\":9,\"d\":false}");
        assertNull(gateway.sessionId());
        clock.addAndGet(5000);
        gateway.pulse();
        DiscordFakes.Gateway.Connection second = discord.last();
        assertEquals("wss://gateway.invalid/?v=10&encoding=json", second.uri.toString(), "no resume URL for a new session");
        second.hello(40_000);
        assertEquals(2, second.lastSent().get("op").getAsInt());
        assertEquals(2, gateway.identifies());
    }

    @Test void givenAnExpiredSessionClose_whenReconnecting_thenIdentifyFresh() {
        DiscordFakes.Gateway.Connection first = connectReady();
        first.events.closed(4009, "Session timed out");
        clock.addAndGet(2000);
        gateway.pulse();
        discord.last().hello(40_000);
        assertEquals(2, discord.last().lastSent().get("op").getAsInt());
    }

    @Test void givenADroppedConnection_whenItKeepsFailing_thenTheWaitGrowsToAMinute() {
        connectReady();
        long[] expected = {1000, 2000, 4000, 8000, 16000, 32000, 60000, 60000};
        for (long wait : expected) {
            discord.last().events.closed(1006, "");
            long now = clock.get();
            long at = gateway.reconnectAt();
            assertEquals(wait + (long) (wait * 0.25 * 0.5), at - now, "wait " + wait);
            int before = discord.connections.size();
            clock.set(at - 1);
            gateway.pulse();
            assertEquals(before, discord.connections.size(), "not earlier");
            clock.set(at);
            gateway.pulse();
            assertEquals(before + 1, discord.connections.size());
        }
    }

    @Test void givenTheMessageContentIntentIsMissing_when4014_thenStopWithAHelpfulLineAndNoReconnect() {
        DiscordFakes.Gateway.Connection first = connectReady();
        first.events.closed(4014, "Disallowed intent(s).");
        assertEquals(DiscordGateway.State.STOPPED, gateway.state());
        assertTrue(log.all().contains("Message Content Intent"), log.all());
        for (int i = 0; i < 100; i++) { clock.addAndGet(60_000); gateway.pulse(); }
        assertEquals(1, discord.connections.size(), "no reconnect loop");
    }

    @Test void givenAWrongToken_when4004_thenStop() {
        gateway.start();
        discord.last().hello(40_000);
        discord.last().events.closed(4004, "Authentication failed.");
        assertEquals(DiscordGateway.State.STOPPED, gateway.state());
        clock.addAndGet(600_000);
        gateway.pulse();
        assertEquals(1, discord.connections.size());
        assertTrue(gateway.lastError().contains("4004"));
    }

    @Test void givenStop_whenTheSocketClosesAfterwards_thenCleanCloseAndNoReconnect() {
        DiscordFakes.Gateway.Connection first = connectReady();
        gateway.stop();
        assertEquals(1000, first.closeCode);
        first.events.closed(1000, "");
        clock.addAndGet(600_000);
        gateway.pulse();
        assertEquals(1, discord.connections.size());
        assertEquals(DiscordGateway.State.OFF, gateway.state());
    }

    @Test void givenAPresence_whenReady_thenStatusUpdateWithTheText() {
        DiscordFakes.Gateway.Connection first = connectReady();
        gateway.presence("3 of 20 players online");
        JsonObject update = first.lastSent();
        assertEquals(3, update.get("op").getAsInt());
        assertEquals("3 of 20 players online", update.getAsJsonObject("d").getAsJsonArray("activities").get(0).getAsJsonObject().get("state").getAsString());
    }
}
