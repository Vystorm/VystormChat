package at.esoren.vystormchat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * The bot's connection to the Discord gateway (API v10, JSON, no compression): hello, identify with the two intents
 * the bridge needs, heartbeat with ack check, resume after a drop, reconnect with growing waits, clean close.
 * Timing is driven by {@link #pulse()} (about twice a second, off the server thread), so nothing sleeps. The socket
 * itself is a {@link Transport}: the JDK WebSocket in the plugin, an in-memory fake in the tests.
 *
 * <p>The token is only ever put into the identify and resume payloads; no log line or status text contains it.
 */
final class DiscordGateway {
    /** Opens one connection; every callback of that connection goes to {@code events}. */
    interface Transport { void open(URI uri, Events events); }

    interface Socket {
        void send(String text);
        void close(int code, String reason);
    }

    interface Events {
        void opened(Socket socket);
        void text(String message);
        void closed(int code, String reason);
        void failed(Throwable error);
    }

    enum State { OFF, CONNECTING, IDENTIFYING, RESUMING, READY, WAITING, STOPPED }

    /** GUILD_MESSAGES (1 << 9) and the privileged MESSAGE_CONTENT (1 << 15). */
    static final int INTENTS = (1 << 9) | (1 << 15);
    static final URI DEFAULT_URL = URI.create("wss://gateway.discord.gg");
    static final long MAX_BACKOFF = 60_000L;

    private final Transport transport;
    private final LongSupplier clock;
    private final Logger log;
    private final String token;
    private final URI baseUrl;
    private final Consumer<JsonObject> messages;
    private final java.util.function.DoubleSupplier random;

    private State state = State.OFF;
    private Socket socket;
    private int generation;
    private long interval;
    private long nextBeat;
    private boolean acked = true;
    private Integer sequence;
    private String sessionId;
    private URI resumeUrl;
    private String selfId;
    private int attempts;
    private long reconnectAt;
    private boolean resumeNext;
    private boolean stopping;
    private volatile String lastError = "";
    private String presence;
    private long connectedSince;
    private int identifies, resumes;

    DiscordGateway(Transport transport, LongSupplier clock, Logger log, String token, URI baseUrl, Consumer<JsonObject> messages,
                   java.util.function.DoubleSupplier random) {
        this.transport = transport;
        this.clock = clock;
        this.log = log;
        this.token = token;
        this.baseUrl = baseUrl == null ? DEFAULT_URL : baseUrl;
        this.messages = messages;
        this.random = random;
    }

    synchronized void start() {
        if (state != State.OFF) return;
        stopping = false;
        connect(false);
    }

    /** Clean close (code 1000); no reconnect afterwards. */
    synchronized void stop() {
        stopping = true;
        state = State.OFF;
        generation++;
        if (socket != null) {
            try { socket.close(1000, "plugin disabled"); } catch (RuntimeException ignored) { }
            socket = null;
        }
    }

    private void connect(boolean resume) {
        generation++;
        int mine = generation;
        resumeNext = resume && sessionId != null;
        state = State.CONNECTING;
        URI base = resumeNext && resumeUrl != null ? resumeUrl : baseUrl;
        String text = base.toString();
        URI uri = URI.create(text + (text.contains("?") ? "&" : (text.endsWith("/") ? "?" : "/?")) + "v=10&encoding=json");
        try {
            transport.open(uri, new Events() {
                @Override public void opened(Socket opened) { DiscordGateway.this.opened(mine, opened); }
                @Override public void text(String message) { DiscordGateway.this.received(mine, message); }
                @Override public void closed(int code, String reason) { DiscordGateway.this.closed(mine, code, reason); }
                @Override public void failed(Throwable error) { DiscordGateway.this.failed(mine, error); }
            });
        } catch (RuntimeException failure) {
            lastError = "could not connect (" + failure.getClass().getSimpleName() + ")";
            retryLater(resume);
        }
    }

    private synchronized void opened(int mine, Socket opened) {
        if (mine != generation || stopping) { opened.close(1000, "old connection"); return; }
        socket = opened;
        connectedSince = clock.getAsLong();
    }

    private synchronized void received(int mine, String message) {
        if (mine != generation || stopping) return;
        JsonObject payload;
        try { payload = JsonParser.parseString(message).getAsJsonObject(); }
        catch (RuntimeException broken) { log.fine("Discord gateway: unreadable payload"); return; }
        JsonElement opValue = payload.get("op");
        int op = opValue != null && opValue.isJsonPrimitive() ? opValue.getAsInt() : -1;
        JsonElement s = payload.get("s");
        if (s != null && !s.isJsonNull()) sequence = s.getAsInt();
        JsonElement d = payload.get("d");
        long now = clock.getAsLong();
        switch (op) {
            case 10 -> { // hello
                interval = d.getAsJsonObject().get("heartbeat_interval").getAsLong();
                nextBeat = now + (long) (interval * random.getAsDouble());
                acked = true;
                if (resumeNext) {
                    state = State.RESUMING;
                    resumes++;
                    JsonObject resume = new JsonObject();
                    resume.addProperty("token", token);
                    resume.addProperty("session_id", sessionId);
                    if (sequence == null) resume.add("seq", JsonNull.INSTANCE); else resume.addProperty("seq", sequence);
                    send(6, resume);
                } else {
                    state = State.IDENTIFYING;
                    identifies++;
                    send(2, identify());
                }
            }
            case 11 -> acked = true;
            case 1 -> heartbeat(now);
            case 7 -> reconnect(true, "Discord asked for a reconnect");
            case 9 -> { // invalid session; d = may resume
                boolean resumable = d != null && d.isJsonPrimitive() && d.getAsBoolean();
                if (!resumable) { sessionId = null; sequence = null; resumeUrl = null; }
                closeSocket(4000, "invalid session");
                state = State.WAITING;
                reconnectAt = now + 1000L + (long) (4000L * random.getAsDouble());
                resumeNext = resumable;
            }
            case 0 -> {
                JsonElement t = payload.get("t");
                dispatch(t != null && t.isJsonPrimitive() ? t.getAsString() : "", d, now);
            }
            default -> { }
        }
    }

    private void dispatch(String type, JsonElement d, long now) {
        switch (type) {
            case "READY" -> {
                JsonObject ready = d.getAsJsonObject();
                sessionId = ready.get("session_id").getAsString();
                if (ready.has("resume_gateway_url")) resumeUrl = URI.create(ready.get("resume_gateway_url").getAsString());
                JsonObject user = ready.getAsJsonObject("user");
                if (user != null) selfId = user.get("id").getAsString();
                state = State.READY;
                attempts = 0;
                lastError = "";
                if (presence != null) send(3, presenceData(presence));
            }
            case "RESUMED" -> {
                state = State.READY;
                attempts = 0;
                lastError = "";
            }
            case "MESSAGE_CREATE" -> {
                if (d != null && d.isJsonObject()) {
                    try { messages.accept(d.getAsJsonObject()); }
                    catch (RuntimeException failure) { log.warning("Discord: a message from Discord could not be shown (" + failure.getClass().getSimpleName() + ")"); }
                }
            }
            default -> { }
        }
    }

    private JsonObject identify() {
        JsonObject data = new JsonObject();
        data.addProperty("token", token);
        data.addProperty("intents", INTENTS);
        JsonObject properties = new JsonObject();
        properties.addProperty("os", System.getProperty("os.name", "unknown").toLowerCase(java.util.Locale.ROOT));
        properties.addProperty("browser", "VystormChat");
        properties.addProperty("device", "VystormChat");
        data.add("properties", properties);
        if (presence != null) data.add("presence", presenceData(presence));
        return data;
    }

    private static JsonObject presenceData(String text) {
        JsonObject data = new JsonObject();
        data.add("since", JsonNull.INSTANCE);
        JsonArray activities = new JsonArray();
        JsonObject activity = new JsonObject();
        activity.addProperty("name", "Custom Status");
        activity.addProperty("type", 4);
        activity.addProperty("state", DiscordText.cut(text, 128));
        activities.add(activity);
        data.add("activities", activities);
        data.addProperty("status", "online");
        data.addProperty("afk", false);
        return data;
    }

    /** The bot's status line (player count); sent at once when connected, else with the next identify. */
    synchronized void presence(String text) {
        presence = text;
        if (state == State.READY) send(3, presenceData(text));
    }

    private void heartbeat(long now) {
        JsonObject beat = new JsonObject();
        beat.addProperty("op", 1);
        if (sequence == null) beat.add("d", JsonNull.INSTANCE); else beat.addProperty("d", sequence);
        sendRaw(beat.toString());
        acked = false;
        nextBeat = now + interval;
    }

    /** Heartbeats, a missing ack (zombie connection) and the reconnect wait. */
    synchronized void pulse() {
        if (stopping) return;
        long now = clock.getAsLong();
        if (state == State.WAITING) {
            if (now >= reconnectAt) connect(resumeNext);
            return;
        }
        boolean beating = state == State.IDENTIFYING || state == State.RESUMING || state == State.READY;
        if (beating && interval > 0 && now >= nextBeat) {
            if (!acked) reconnect(true, "no heartbeat answer from Discord");
            else heartbeat(now);
        }
    }

    private void reconnect(boolean resume, String why) {
        lastError = why;
        closeSocket(4000, "reconnect");
        retryLater(resume);
    }

    private void retryLater(boolean resume) {
        generation++;
        attempts++;
        long wait = Math.min(MAX_BACKOFF, 1000L << Math.min(6, attempts - 1));
        reconnectAt = clock.getAsLong() + wait + (long) (wait * 0.25 * random.getAsDouble());
        resumeNext = resume;
        state = State.WAITING;
    }

    private void closeSocket(int code, String reason) {
        Socket old = socket;
        socket = null;
        generation++;
        if (old != null) {
            try { old.close(code, reason); } catch (RuntimeException ignored) { }
        }
    }

    private synchronized void closed(int mine, int code, String reason) {
        if (mine != generation || stopping) return;
        socket = null;
        switch (code) {
            case 4004 -> fatal("Discord refused the bot token (4004). Put a valid token into discord-token.txt and run /vchat reload.");
            case 4010, 4011, 4012 -> fatal("Discord closed the connection (" + code + "); the bot cannot connect with these settings.");
            case 4013 -> fatal("Discord refused the gateway intents (4013).");
            case 4014 -> fatal("The bot may not read message content (4014): switch on \"Message Content Intent\" for the bot in the Discord developer portal, then run /vchat reload.");
            case 4007, 4009 -> {
                sessionId = null;
                sequence = null;
                resumeUrl = null;
                lastError = "session expired (" + code + "), connecting again";
                retryLater(false);
            }
            default -> {
                lastError = "connection closed (" + code + "), connecting again";
                retryLater(sessionId != null);
            }
        }
    }

    private void fatal(String why) {
        lastError = why;
        state = State.STOPPED;
        generation++;
        log.warning("Discord: " + why);
    }

    private synchronized void failed(int mine, Throwable error) {
        if (mine != generation || stopping) return;
        socket = null;
        lastError = "connection failed (" + error.getClass().getSimpleName() + "), connecting again";
        retryLater(sessionId != null);
    }

    private void send(int op, JsonElement data) {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", op);
        payload.add("d", data);
        sendRaw(payload.toString());
    }

    private void sendRaw(String text) {
        Socket current = socket;
        if (current == null) return;
        try { current.send(text); }
        catch (RuntimeException failure) { lastError = "send failed (" + failure.getClass().getSimpleName() + ")"; }
    }

    synchronized State state() { return state; }
    synchronized String selfId() { return selfId; }
    synchronized String sessionId() { return sessionId; }
    synchronized Integer sequence() { return sequence; }
    synchronized long reconnectAt() { return reconnectAt; }
    synchronized int attempts() { return attempts; }
    synchronized int identifies() { return identifies; }
    synchronized int resumes() { return resumes; }
    synchronized long connectedSince() { return connectedSince; }
    String lastError() { return lastError; }

    // ------------------------------------------------------------------ the JDK WebSocket

    /** {@link Transport} through {@code java.net.http.WebSocket}; fragments are joined, sends are chained. */
    static Transport jdk() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (uri, events) -> {
            StringBuilder buffer = new StringBuilder();
            WebSocket.Listener listener = new WebSocket.Listener() {
                // opened before the first request(1), so the hello can never arrive before the socket is known
                @Override public void onOpen(WebSocket webSocket) { events.opened(socket(webSocket)); webSocket.request(1); }
                @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                        String message = buffer.toString();
                        buffer.setLength(0);
                        events.text(message);
                    }
                    webSocket.request(1);
                    return null;
                }
                @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    events.closed(statusCode, reason);
                    return null;
                }
                @Override public void onError(WebSocket webSocket, Throwable error) { events.failed(error); }
            };
            client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, listener).whenComplete((webSocket, error) -> {
                if (error != null) events.failed(error);
            });
        };
    }

    /** JDK WebSockets allow one pending send: every send waits for the one before. */
    private static Socket socket(WebSocket webSocket) {
        return new Socket() {
            private CompletableFuture<?> last = CompletableFuture.completedFuture(null);
            @Override public synchronized void send(String text) {
                last = last.handle((ok, failure) -> null).thenCompose(ignored -> webSocket.sendText(text, true));
            }
            @Override public synchronized void close(int code, String reason) {
                last.handle((ok, failure) -> null).thenCompose(ignored -> webSocket.sendClose(code, reason))
                        .orTimeout(3, java.util.concurrent.TimeUnit.SECONDS).whenComplete((ok, failure) -> webSocket.abort());
            }
        };
    }
}
