package at.esoren.vystormchat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Outgoing Discord requests (webhook posts and bot messages) through one bounded queue. Sending runs off the server
 * thread ({@code async}, Core's task group in the plugin); a request that has to wait for a rate limit stays in the
 * queue and is picked up again by {@link #pulse()} once its time has come, so no thread sleeps.
 *
 * <p>Rate limits: every route keeps the bucket Discord reports ({@code X-RateLimit-Remaining} and
 * {@code X-RateLimit-Reset-After}); a 429 blocks its route, or every route when Discord says it is global, for the
 * {@code retry_after} time. 401, 403 and 404 stop a route until the next reload, so a deleted webhook or a wrong token
 * never turns into a stream of invalid requests (Discord bans IPs after 10,000 of those in ten minutes; this queue stops
 * everything for ten minutes after 100).
 *
 * <p>Nothing secret is logged: jobs carry a readable label ("webhook of channel global"), never the URL.
 */
final class DiscordRest {
    /** One HTTP call; tests pass a client for a local fake server. */
    interface Http {
        Response send(String method, URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException;
    }

    /** Header names in lower case. */
    record Response(int status, Map<String, String> headers, String body) {}

    /**
     * One request. {@code route} groups requests that share a Discord rate limit (one webhook, one channel);
     * {@code label} is what the log and the status command may show.
     */
    record Job(String route, String label, URI uri, boolean bot, String body, int attempts) {
        Job retry() { return new Job(route, label, uri, bot, body, attempts + 1); }
    }

    static final int MAX_429_RETRIES = 5;
    static final int MAX_ERROR_RETRIES = 3;
    static final int INVALID_LIMIT = 100;
    static final long INVALID_WINDOW = 600_000L;
    /** Discord asks for "DiscordBot (url, version)"; the plugin sets its version at start. */
    static volatile String userAgent = "DiscordBot (https://vystorm.net, 1) VystormChat";

    private final Http http;
    private final LongSupplier clock;
    private final Consumer<Runnable> async;
    private final Logger log;
    private final Supplier<String> token;
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Map<String, Long> blockedUntil = new HashMap<>();
    private final Set<String> deadRoutes = new HashSet<>();
    private final ArrayDeque<Long> invalid = new ArrayDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile int cap;
    private long globalUntil;
    private long droppedTotal, droppedSinceLog, sent;
    private long lastDropLog = Long.MIN_VALUE / 2;
    private volatile String lastError = "";
    private volatile boolean closed;

    DiscordRest(Http http, LongSupplier clock, Consumer<Runnable> async, Logger log, int cap, Supplier<String> token) {
        this.http = http;
        this.clock = clock;
        this.async = async;
        this.log = log;
        this.cap = Math.max(1, cap);
        this.token = token;
    }

    void cap(int cap) { this.cap = Math.max(1, cap); }

    /** Forgets stopped routes (after a reload the admin may have fixed the webhook or the token). */
    synchronized void reset() { deadRoutes.clear(); }

    /** Queues a request; false (and counted as dropped) when the queue is full or closed. */
    boolean submit(Job job) {
        synchronized (this) {
            if (closed) return false;
            if (deadRoutes.contains(job.route())) return false;
            if (queue.size() >= cap) {
                droppedTotal++;
                droppedSinceLog++;
                return false;
            }
            queue.addLast(job);
        }
        kick();
        return true;
    }

    /** Called about twice a second: sends what may be sent now and logs dropped messages at most once a minute. */
    void pulse() {
        long now = clock.getAsLong();
        long dropped = 0;
        synchronized (this) {
            if (droppedSinceLog > 0 && now - lastDropLog >= 60_000L) {
                dropped = droppedSinceLog;
                droppedSinceLog = 0;
                lastDropLog = now;
            }
        }
        if (dropped > 0) log.warning("Discord: dropped " + dropped + " messages because the queue was full (" + cap + " waiting). Discord answers slower than the chat writes.");
        kick();
    }

    private void kick() {
        if (closed || queued() == 0) return;
        if (draining.compareAndSet(false, true)) {
            try { async.accept(this::drain); }
            catch (RuntimeException stopped) { draining.set(false); }
        }
    }

    /** Sends every job whose route is free now, in queue order per route. */
    void drain() {
        try {
            while (!closed) {
                Job job = next(clock.getAsLong());
                if (job == null) return;
                handle(job, call(job));
            }
        } finally {
            draining.set(false);
        }
    }

    private synchronized Job next(long now) {
        if (now < globalUntil) return null;
        Set<String> skipped = new HashSet<>();
        for (Iterator<Job> it = queue.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (skipped.contains(job.route())) continue;
            Long until = blockedUntil.get(job.route());
            if (until != null && now < until) { skipped.add(job.route()); continue; }
            it.remove();
            return job;
        }
        return null;
    }

    private Response call(Job job) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", userAgent);
        if (job.bot()) {
            String secret = token.get();
            if (secret == null || secret.isEmpty()) return new Response(-2, Map.of(), "");
            headers.put("Authorization", "Bot " + secret);
        }
        try {
            return http.send("POST", job.uri(), headers, job.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Response(-1, Map.of(), "interrupted");
        } catch (IOException | RuntimeException failure) {
            return new Response(-1, Map.of(), failure.getClass().getSimpleName());
        }
    }

    private void handle(Job job, Response response) {
        long now = clock.getAsLong();
        int status = response.status();
        String warning = null;
        synchronized (this) {
            bucket(job.route(), response.headers(), now);
            if (status >= 200 && status < 300) { sent++; return; }
            if (status == -2) { lastError = job.label() + ": no bot token"; return; }
            if (status == 429) {
                countInvalid(now);
                long wait = retryAfterMillis(response);
                boolean global = "true".equalsIgnoreCase(response.headers().getOrDefault("x-ratelimit-global", ""))
                        || bodyFlag(response.body(), "global");
                if (global) globalUntil = Math.max(globalUntil, now + wait);
                else blockedUntil.put(job.route(), Math.max(blockedUntil.getOrDefault(job.route(), 0L), now + wait));
                lastError = job.label() + ": rate limited for " + wait + " ms" + (global ? " (global)" : "");
                if (job.attempts() + 1 >= MAX_429_RETRIES) { droppedTotal++; warning = "Discord: gave up one message for the " + job.label() + " after " + MAX_429_RETRIES + " rate limits."; }
                else queue.addFirst(job.retry());
            } else if (status == 401 || status == 403 || status == 404) {
                countInvalid(now);
                deadRoutes.add(job.route());
                queue.removeIf(waiting -> waiting.route().equals(job.route()));
                lastError = job.label() + ": Discord answered " + status + (status == 404 ? " (deleted or wrong id)" : " (no access)");
                warning = "Discord: the " + job.label() + " answered " + status + "; no more messages go there until /vchat reload. "
                        + (job.bot() ? "Check the bot token, the channel id and the bot's rights in that channel." : "Check the webhook URL in config.yml.");
            } else if (status >= 400 && status < 500) {
                droppedTotal++;
                lastError = job.label() + ": Discord refused one message (" + status + ")";
                warning = "Discord: the " + job.label() + " refused one message (" + status + ").";
            } else {
                lastError = job.label() + ": " + (status < 0 ? "no connection (" + safe(response.body()) + ")" : "Discord error " + status);
                if (job.attempts() + 1 >= MAX_ERROR_RETRIES) { droppedTotal++; warning = "Discord: gave up one message for the " + job.label() + ": " + lastError; }
                else {
                    blockedUntil.put(job.route(), now + 2000L * (1L << job.attempts()));
                    queue.addFirst(job.retry());
                }
            }
        }
        if (warning != null) log.warning(warning);
    }

    /** Remaining 0: the route waits until Discord's reset. */
    private void bucket(String route, Map<String, String> headers, long now) {
        String remaining = headers.get("x-ratelimit-remaining");
        String resetAfter = headers.get("x-ratelimit-reset-after");
        if (remaining == null || resetAfter == null) return;
        try {
            if (Integer.parseInt(remaining.trim()) <= 0)
                blockedUntil.put(route, Math.max(blockedUntil.getOrDefault(route, 0L), now + seconds(resetAfter)));
        } catch (NumberFormatException ignored) { }
    }

    private void countInvalid(long now) {
        invalid.addLast(now);
        while (!invalid.isEmpty() && now - invalid.peekFirst() > INVALID_WINDOW) invalid.removeFirst();
        if (invalid.size() >= INVALID_LIMIT) {
            globalUntil = Math.max(globalUntil, now + INVALID_WINDOW);
            invalid.clear();
            log.warning("Discord: " + INVALID_LIMIT + " refused requests within ten minutes; sending pauses for ten minutes so Discord does not block this server.");
        }
    }

    static long retryAfterMillis(Response response) {
        String body = response.body();
        if (body != null && body.startsWith("{")) {
            try {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("retry_after")) return clampWait((long) Math.ceil(json.get("retry_after").getAsDouble() * 1000.0));
            } catch (RuntimeException notJson) { /* header below */ }
        }
        String header = response.headers().get("retry-after");
        if (header == null) header = response.headers().get("x-ratelimit-reset-after");
        return header == null ? 5000L : clampWait(seconds(header));
    }

    private static long clampWait(long millis) { return Math.clamp(millis, 100L, 3_600_000L); }

    private static long seconds(String value) {
        try { return (long) Math.ceil(Double.parseDouble(value.trim()) * 1000.0); }
        catch (NumberFormatException wrong) { return 5000L; }
    }

    private static boolean bodyFlag(String body, String key) {
        if (body == null || !body.startsWith("{")) return false;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return json.has(key) && json.get(key).getAsBoolean();
        } catch (RuntimeException notJson) { return false; }
    }

    /** Exception names only; never a URL. */
    private static String safe(String text) {
        return text == null ? "" : text.replaceAll("https?://\\S+", "<url>");
    }

    synchronized int queued() { return queue.size(); }

    synchronized long dropped() { return droppedTotal; }

    synchronized long sent() { return sent; }

    String lastError() { return lastError; }

    synchronized boolean dead(String route) { return deadRoutes.contains(route); }

    /** Stops sending; waiting requests are forgotten. */
    synchronized void close() {
        closed = true;
        queue.clear();
    }

    boolean closed() { return closed; }

    /** Sends one job directly on this thread with a short timeout (the "server stopped" line while the plugin shuts down). */
    void sendNow(Job job) {
        Response response = call(job);
        if (response.status() < 200 || response.status() >= 300) log.fine("Discord: the last message (" + job.label() + ") was not sent: " + response.status());
    }

    // ------------------------------------------------------------------ the JDK client

    /** {@link Http} through {@code java.net.http.HttpClient}; nothing of it runs on the server thread. */
    static Http jdk(Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return (method, uri, headers, body) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(request::header);
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> lower = new HashMap<>();
            response.headers().map().forEach((name, values) -> { if (!values.isEmpty()) lower.put(name.toLowerCase(Locale.ROOT), values.get(0)); });
            return new Response(response.statusCode(), lower, response.body());
        };
    }
}
