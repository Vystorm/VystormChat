package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The outgoing queue against a local fake Discord (1.18.0): rate limits from the headers, 429 with retry_after (route
 * and global), 401/403/404 stop a route, the queue cap with the "dropped" log line, the token only in the header.
 */
class DiscordRestTest {
    private DiscordFakes.Server server;
    private final DiscordFakes.LogCatcher log = new DiscordFakes.LogCatcher();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Runnable> parked = new ArrayList<>();
    private boolean park;

    @BeforeEach void start() throws Exception { server = new DiscordFakes.Server(); }

    @AfterEach void stop() {
        server.close();
        assertFalse(log.all().contains(DiscordFakes.TOKEN), "token in the log: " + log.all());
        assertFalse(log.all().contains("fakewebhooktoken"), "webhook URL in the log: " + log.all());
    }

    private DiscordRest rest(int cap) {
        return new DiscordRest(DiscordRest.jdk(Duration.ofSeconds(5)), clock::get, task -> { if (park) parked.add(task); else task.run(); },
                log.logger, cap, () -> DiscordFakes.TOKEN);
    }

    private DiscordRest.Job webhook(String name, String content) {
        return new DiscordRest.Job("webhook:" + name, "webhook of channel " + name, URI.create(server.webhook(name)), false,
                "{\"content\":\"" + content + "\",\"allowed_mentions\":{\"parse\":[]}}", 0);
    }

    @Test void givenAWebhookJob_whenSent_thenTheFakeDiscordGetsTheJsonWithoutAToken() {
        DiscordRest rest = rest(10);
        assertTrue(rest.submit(webhook("global", "hello")));
        assertEquals(1, server.requests.size());
        DiscordFakes.Server.Request request = server.requests.get(0);
        assertEquals("POST", request.method());
        assertEquals("/api/webhooks/1234567/global-fakewebhooktoken", request.path());
        assertEquals("hello", request.json().get("content").getAsString());
        assertNull(request.headers().get("authorization"), "webhooks need no token");
        assertTrue(request.headers().get("user-agent").startsWith("DiscordBot ("));
        assertEquals(1, rest.sent());
    }

    @Test void givenABotJob_whenSent_thenTheTokenIsOnlyInTheAuthorizationHeader() {
        DiscordRest rest = rest(10);
        rest.submit(new DiscordRest.Job("channel:111", "bot channel of channel global", URI.create(server.base + "/api/v10/channels/111/messages"), true,
                "{\"content\":\"hi\"}", 0));
        assertEquals("Bot " + DiscordFakes.TOKEN, server.requests.get(0).headers().get("authorization"));
        assertFalse(server.requests.get(0).body().contains(DiscordFakes.TOKEN));
    }

    @Test void given429WithRetryAfter_whenTheTimeHasNotPassed_thenNothingIsSentAndAfterwardsTheMessageArrives() {
        DiscordRest rest = rest(10);
        server.reply(429, Map.of("Retry-After", "2"), "{\"message\":\"You are being rate limited.\",\"retry_after\":1.5,\"global\":false}");
        rest.submit(webhook("global", "one"));
        assertEquals(1, server.requests.size());
        assertEquals(1, rest.queued(), "the message waits");
        assertTrue(rest.lastError().contains("rate limited for 1500 ms"), rest.lastError());
        clock.addAndGet(1000);
        rest.pulse();
        assertEquals(1, server.requests.size(), "no request before retry_after (never a ban loop)");
        clock.addAndGet(600);
        rest.pulse();
        assertEquals(2, server.requests.size());
        assertEquals("one", server.requests.get(1).json().get("content").getAsString());
        assertEquals(0, rest.queued());
        assertEquals(1, rest.sent());
    }

    @Test void givenAnExhaustedBucket_whenTheNextMessageComes_thenItWaitsForTheResetWhileOtherRoutesGoOn() {
        DiscordRest rest = rest(10);
        server.reply(204, Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset-After", "2.0", "X-RateLimit-Bucket", "abc"), "");
        rest.submit(webhook("global", "first"));
        rest.submit(webhook("global", "second"));
        rest.submit(webhook("trade", "other route"));
        assertEquals(2, server.requests.size(), "first and the other route; second waits");
        assertEquals("other route", server.requests.get(1).json().get("content").getAsString());
        clock.addAndGet(1999);
        rest.pulse();
        assertEquals(2, server.requests.size());
        clock.addAndGet(2);
        rest.pulse();
        assertEquals(3, server.requests.size());
        assertEquals("second", server.requests.get(2).json().get("content").getAsString());
    }

    @Test void givenAGlobal429_whenOtherRoutesHaveMessages_thenEverythingWaits() {
        DiscordRest rest = rest(10);
        server.reply(429, Map.of("X-RateLimit-Global", "true"), "{\"retry_after\":3,\"global\":true}");
        rest.submit(webhook("global", "one"));
        rest.submit(webhook("trade", "two"));
        assertEquals(1, server.requests.size());
        clock.addAndGet(3000);
        rest.pulse();
        assertEquals(3, server.requests.size());
    }

    @Test void givenRepeated429s_whenTheRetryLimitIsReached_thenTheMessageIsDroppedAndLogged() {
        DiscordRest rest = rest(10);
        for (int i = 0; i < DiscordRest.MAX_429_RETRIES; i++) server.reply(429, Map.of(), "{\"retry_after\":0.2}");
        rest.submit(webhook("global", "stubborn"));
        for (int i = 0; i < 10; i++) { clock.addAndGet(300); rest.pulse(); }
        assertEquals(DiscordRest.MAX_429_RETRIES, server.requests.size(), "a bounded number of tries");
        assertEquals(0, rest.queued());
        assertEquals(1, rest.dropped());
        assertTrue(log.all().contains("gave up one message for the webhook of channel global"), log.all());
    }

    @Test void givenADeletedWebhook_when404_thenTheRouteStopsUntilReset() {
        DiscordRest rest = rest(10);
        server.reply(404, Map.of(), "{\"message\":\"Unknown Webhook\",\"code\":10015}");
        rest.submit(webhook("global", "one"));
        assertFalse(rest.submit(webhook("global", "two")), "a dead route takes nothing");
        assertEquals(1, server.requests.size());
        assertTrue(rest.dead("webhook:global"));
        assertTrue(log.all().contains("webhook of channel global answered 404"), log.all());
        rest.reset();
        assertTrue(rest.submit(webhook("global", "after reload")));
        assertEquals(2, server.requests.size());
    }

    @Test void givenAFullQueue_whenMoreMessagesCome_thenTheyAreDroppedAndTheLogSaysHowMany() {
        park = true;
        DiscordRest rest = rest(10);
        for (int i = 0; i < 15; i++) rest.submit(webhook("global", "m" + i));
        assertEquals(10, rest.queued());
        assertEquals(5, rest.dropped());
        rest.pulse();
        assertTrue(log.all().contains("dropped 5 messages because the queue was full (10 waiting)"), log.all());
        park = false;
        parked.forEach(Runnable::run);
        assertEquals(10, server.requests.size(), "the queued ten are sent in order");
        assertEquals("m0", server.requests.get(0).json().get("content").getAsString());
        assertEquals("m9", server.requests.get(9).json().get("content").getAsString());
        rest.pulse();
        assertEquals(1, log.lines.stream().filter(line -> line.contains("dropped")).count(), "logged once");
    }

    @Test void givenNoServer_whenSending_thenRetriedThenDroppedWithoutTheUrlInTheError() {
        DiscordRest rest = rest(10);
        server.close();
        rest.submit(webhook("global", "lost"));
        for (int i = 0; i < 6; i++) { clock.addAndGet(20_000); rest.pulse(); }
        assertEquals(0, rest.queued());
        assertEquals(1, rest.dropped());
        assertFalse(rest.lastError().contains("http"), rest.lastError());
    }

    @Test void givenManyRefusedRequests_whenTheLimitIsReached_thenEverythingPausesTenMinutes() {
        DiscordRest rest = rest(500);
        for (int i = 0; i < DiscordRest.INVALID_LIMIT; i++) server.reply(403, Map.of(), "{}");
        for (int i = 0; i < DiscordRest.INVALID_LIMIT; i++) rest.submit(webhook("route" + i, "x"));
        assertEquals(DiscordRest.INVALID_LIMIT, server.requests.size());
        rest.submit(webhook("fresh", "waits"));
        assertEquals(DiscordRest.INVALID_LIMIT, server.requests.size(), "paused");
        clock.addAndGet(DiscordRest.INVALID_WINDOW);
        rest.pulse();
        assertEquals(DiscordRest.INVALID_LIMIT + 1, server.requests.size());
    }

    @Test void givenRetryAfterFormats_whenRead_thenMilliseconds() {
        assertEquals(1500, DiscordRest.retryAfterMillis(new DiscordRest.Response(429, Map.of(), "{\"retry_after\":1.5}")));
        assertEquals(3000, DiscordRest.retryAfterMillis(new DiscordRest.Response(429, Map.of("retry-after", "3"), "")));
        assertEquals(5000, DiscordRest.retryAfterMillis(new DiscordRest.Response(429, Map.of(), "")));
        assertEquals(3_600_000L, DiscordRest.retryAfterMillis(new DiscordRest.Response(429, Map.of(), "{\"retry_after\":999999}")), "capped");
    }
}
