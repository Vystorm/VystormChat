package at.esoren.vystormchat;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Messages every player wrote in public channels, for the ranking {@code chat:messages} (1.19.0), kept in
 * {@code message-counts.yml} ({@code players.<uuid>.name} and {@code .messages}). Only channels everyone can read
 * count ({@link #counts}): never private messages, party, city or faction channels, channels with a read permission
 * (staff) or channels forwarded to another plugin. Only the number is stored, never a message.
 *
 * <p>Thread-safe: messages are counted on the chat thread. A minute timer writes the file in the background when
 * something changed (never once per message), and the plugin's stop writes it once more. An unreadable file is kept as
 * {@code .corrupt-<time>} and nothing is written until the next restart.
 */
final class MessageCounts {
    record Standing(UUID player, String name, long messages) {}

    private static final class Entry {
        volatile String name;
        final AtomicLong messages = new AtomicLong();
    }

    private final Path file;
    private final Logger log;
    private final Executor io;
    private final AtomicFilesInterface files;
    private final Map<UUID, Entry> players = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Object writeLock = new Object();
    private volatile boolean readOnly;

    MessageCounts(Path file, Logger log, Executor io, AtomicFilesInterface files) {
        this.file = file;
        this.log = log;
        this.io = io;
        this.files = files;
    }

    /** Whether a message in {@code channel} counts: everyone may read it, no group, not forwarded to another plugin. */
    static boolean counts(Channel channel) {
        return channel != null && !channel.scope().group() && channel.readPermission().isEmpty() && channel.forwardCommand().isEmpty();
    }

    /** One delivered message; ignored for channels that do not count. Any thread. */
    void count(UUID player, String name, Channel channel) {
        if (player == null || !counts(channel)) return;
        Entry entry = players.computeIfAbsent(player, id -> new Entry());
        if (name != null) entry.name = name;
        entry.messages.incrementAndGet();
        dirty.set(true);
    }

    long messages(UUID player) {
        Entry entry = players.get(player);
        return entry == null ? 0 : entry.messages.get();
    }

    /** The best {@code limit} writers, most messages first, ties by name. Memory only. */
    List<Standing> top(int limit) {
        List<Standing> standings = new ArrayList<>();
        players.forEach((id, entry) -> {
            long messages = entry.messages.get();
            String name = entry.name;
            if (messages > 0) standings.add(new Standing(id, name == null ? id.toString().substring(0, 8) : name, messages));
        });
        standings.sort(Comparator.comparingLong(Standing::messages).reversed().thenComparing(Standing::name, String.CASE_INSENSITIVE_ORDER));
        return standings.size() <= limit ? standings : List.copyOf(standings.subList(0, limit));
    }

    /** Reads the file (missing = nobody yet; broken = kept aside, starts empty, never written until restart). */
    void load() {
        players.clear();
        if (!Files.isRegularFile(file)) return;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            fromYaml(yaml);
        } catch (Exception broken) {
            players.clear();
            readOnly = true;
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try { Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING); } catch (IOException moveFailed) { backup = file; }
            log.log(Level.SEVERE, file.getFileName() + " could not be read and was kept as " + backup.getFileName()
                    + "; message counting starts empty and is not saved until the next restart.", broken);
        }
    }

    void fromYaml(YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection saved = section.getConfigurationSection(key);
            if (saved == null) continue;
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException skipped) { continue; }
            Entry entry = new Entry();
            entry.name = saved.getString("name");
            entry.messages.set(Math.max(0, saved.getLong("messages", 0)));
            players.put(id, entry);
        }
    }

    String toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        players.forEach((id, entry) -> {
            if (entry.name != null) yaml.set("players." + id + ".name", entry.name);
            yaml.set("players." + id + ".messages", entry.messages.get());
        });
        return yaml.saveToString();
    }

    boolean dirty() { return dirty.get(); }

    /** The minute timer: writes in the background when something changed. */
    void flush() {
        if (dirty.get()) io.execute(this::saveNow);
    }

    /** Writes now on the calling thread when something changed (background thread, or the plugin's stop). */
    void saveNow() {
        if (readOnly) return;
        synchronized (writeLock) {
            if (!dirty.getAndSet(false)) return;
            try {
                files.write(file, toYaml().getBytes(StandardCharsets.UTF_8));
            } catch (IOException failed) {
                dirty.set(true);   // again with the next minute
                log.log(Level.WARNING, "Could not save " + file.getFileName(), failed);
            }
        }
    }
}
