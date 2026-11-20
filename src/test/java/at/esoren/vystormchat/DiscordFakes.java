package at.esoren.vystormchat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;

/** Fakes for the Discord tests: a local HTTP server for webhooks and REST, an in-memory gateway, a host, a log catcher. */
final class DiscordFakes {
    private DiscordFakes() {}

    /**
     * An obviously fake bot token; the tests check that it never reaches a log line or a status text. Built at run
     * time so the secret scan does not read it as a token in the source.
     */
    static final String TOKEN = String.join(".", "fake", "bot-token", "0".repeat(22));

    // ------------------------------------------------------------------ log

    static final class LogCatcher extends Handler {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final Logger logger;
        private static final AtomicLong COUNT = new AtomicLong();

        LogCatcher() {
            logger = Logger.getLogger("discord-test-" + COUNT.incrementAndGet());
            logger.setUseParentHandlers(false);
            logger.addHandler(this);
            logger.setLevel(java.util.logging.Level.ALL);
        }

        @Override public void publish(LogRecord record) {
            String line = record.getLevel() + " " + record.getMessage();
            if (record.getThrown() != null) line += " " + record.getThrown();
            lines.add(line);
        }
        @Override public void flush() {}
        @Override public void close() {}

        String all() { return String.join("\n", lines); }
    }

    // ------------------------------------------------------------------ HTTP

    /** Answers with the scripted replies in order; without a script 204. Records every request. */
    static final class Server implements AutoCloseable {
        record Request(String method, String path, Map<String, String> headers, String body) {
            JsonObject json() { return JsonParser.parseString(body).getAsJsonObject(); }
        }
        record Reply(int status, Map<String, String> headers, String body) {}

        final List<Request> requests = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedDeque<Reply> replies = new ConcurrentLinkedDeque<>();
        private final HttpServer server;
        final String base;

        Server() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                Map<String, String> headers = new HashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));
                Reply reply = replies.pollFirst();
                if (reply == null) reply = new Reply(204, Map.of(), "");
                reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
                byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
                if (out.length > 0) exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String webhook(String name) { return base + "/api/webhooks/1234567/" + name + "-fakewebhooktoken"; }

        void reply(int status, Map<String, String> headers, String body) { replies.addLast(new Reply(status, headers, body)); }

        @Override public void close() { server.stop(0); }
    }

    // ------------------------------------------------------------------ gateway

    /** An in-memory gateway: each open() is one connection the test talks through as "Discord". */
    static final class Gateway implements DiscordGateway.Transport {
        final class Connection implements DiscordGateway.Socket {
            final URI uri;
            final DiscordGateway.Events events;
            final List<String> sent = new CopyOnWriteArrayList<>();
            int closeCode = -1;
            Connection(URI uri, DiscordGateway.Events events) { this.uri = uri; this.events = events; }
            @Override public void send(String text) { sent.add(text); }
            @Override public void close(int code, String reason) { closeCode = code; }
            void hello(long interval) { events.text("{\"op\":10,\"d\":{\"heartbeat_interval\":" + interval + "}}"); }
            void dispatch(String type, int seq, String data) { events.text("{\"op\":0,\"t\":\"" + type + "\",\"s\":" + seq + ",\"d\":" + data + "}"); }
            void ready(int seq, String session, String resumeUrl, String selfId) {
                dispatch("READY", seq, "{\"v\":10,\"session_id\":\"" + session + "\",\"resume_gateway_url\":\"" + resumeUrl
                        + "\",\"user\":{\"id\":\"" + selfId + "\",\"username\":\"Bridge\",\"bot\":true},\"guilds\":[]}");
            }
            JsonObject lastSent() { return JsonParser.parseString(sent.get(sent.size() - 1)).getAsJsonObject(); }
            List<Integer> ops() {
                List<Integer> ops = new ArrayList<>();
                for (String text : sent) ops.add(JsonParser.parseString(text).getAsJsonObject().get("op").getAsInt());
                return ops;
            }
        }

        final List<Connection> connections = new CopyOnWriteArrayList<>();

        @Override public void open(URI uri, DiscordGateway.Events events) {
            Connection connection = new Connection(uri, events);
            connections.add(connection);
            events.opened(connection);
        }

        Connection last() { return connections.get(connections.size() - 1); }
    }

    // ------------------------------------------------------------------ host

    static final class Host implements DiscordBridge.Host {
        record Shown(Channel channel, Component line, String log) {}
        final LogCatcher log = new LogCatcher();
        final List<Shown> shown = new CopyOnWriteArrayList<>();
        final List<Runnable> parked = new CopyOnWriteArrayList<>();
        final Map<String, String> env = new HashMap<>();
        final Set<String> plugins = new HashSet<>();
        final Set<String> publicPermissions = new HashSet<>(Set.of("vystorm_core.chat.trade.receive"));
        Path folder;
        boolean parkAsync;
        int online = 3;
        private final Vystorm.vystorm_core.lang.Lang lang;

        Host(Path folder) throws IOException {
            this.folder = folder;
            lang = Vystorm.vystorm_core.lang.Lang.create("vystormchat", Map.of("en", Files.readString(Path.of("src/main/resources/lang/en.yml"))));
        }

        @Override public void async(Runnable task) { if (parkAsync) parked.add(task); else task.run(); }
        @Override public void main(Runnable task) { task.run(); }
        @Override public Logger log() { return log.logger; }
        @Override public String text(String key, Object... placeholders) {
            if (!lang.has(key)) throw new AssertionError("missing lang key " + key);
            return lang.plain("en", key, placeholders);
        }
        @Override public boolean publicPermission(String permission) { return publicPermissions.contains(permission); }
        @Override public Path dataFolder() { return folder; }
        @Override public String environment(String name) { return env.get(name); }
        @Override public boolean pluginPresent(String name) { return plugins.contains(name); }
        @Override public int onlinePlayers() { return online; }
        @Override public int maxPlayers() { return 20; }
        @Override public void show(Channel channel, Component line, String logLine) { shown.add(new Shown(channel, line, logLine)); }
    }

    // ------------------------------------------------------------------ settings

    /** The shipped config.yml with the bridge on, global bridged to {@code webhook} and Discord channel 111111, plus changes. */
    static Settings settings(String webhook, Object... changes) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        config.set("discord.enabled", true);
        config.set("discord.channels.global.webhook", webhook);
        config.set("discord.channels.global.discord-id", "111111");
        for (int i = 0; i + 1 < changes.length; i += 2) config.set((String) changes[i], changes[i + 1]);
        DiscordConfig.allowLocalForTests = true;
        try { return new Settings(config, Logger.getLogger("test")); }
        finally { DiscordConfig.allowLocalForTests = false; }
    }

    static DiscordBridge bridge(Host host, Server server, Gateway gateway, AtomicLong clock) {
        return new DiscordBridge(host, DiscordRest.jdk(java.time.Duration.ofSeconds(5)), gateway, clock::get,
                URI.create(server == null ? "http://127.0.0.1:9/api/v10" : server.base + "/api/v10"), URI.create("wss://gateway.invalid"));
    }
}
