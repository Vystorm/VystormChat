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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Mutes with end time and reason, kept in {@code mutes.yml}. Thread-safe; saves run on the given executor. */
final class MuteStore {
    /** @param until end in epoch millis, 0 = until unmuted */
    record Mute(UUID player, String name, long since, long until, String reason, String by) {
        boolean permanent() { return until <= 0; }

        boolean active(long now) { return permanent() || now < until; }

        long remaining(long now) { return permanent() ? -1 : Math.max(0, until - now); }
    }

    private final Path file;
    private final Logger log;
    private final Executor io;
    private final Map<UUID, Mute> mutes = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    /** Set when mutes.yml was unreadable: it is never written until the next restart. */
    private volatile boolean readOnly;
    /** mutes.enabled in config.yml: while off nobody counts as muted, the saved mutes stay untouched. */
    private volatile boolean enabled = true;

    private final AtomicFilesInterface files;

    MuteStore(Path file, Logger log, Executor io, AtomicFilesInterface files) {
        this.file = file;
        this.files = files;
        this.log = log;
        this.io = io;
    }

    /** Reads the file (missing = no mutes, broken = kept as .corrupt-time, starts empty and is not saved until restart). */
    void load() {
        mutes.clear();
        if (!Files.isRegularFile(file)) return;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            ConfigurationSection section = yaml.getConfigurationSection("mutes");
            if (section == null) return;
            long now = System.currentTimeMillis();
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) continue;
                UUID id;
                try { id = UUID.fromString(key); } catch (IllegalArgumentException skipped) { continue; }
                Mute mute = new Mute(id, entry.getString("name", key), entry.getLong("since", now), entry.getLong("until", 0),
                        entry.getString("reason", ""), entry.getString("by", ""));
                if (mute.active(now)) mutes.put(id, mute);
            }
        } catch (Exception broken) {
            mutes.clear();
            readOnly = true; // never save over the mutes, even when moving the file failed
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try { Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING); } catch (IOException moveFailed) { backup = file; }
            log.log(Level.SEVERE, "mutes.yml could not be read and was kept as " + backup.getFileName() + "; starting without mutes."
                    + " Mute changes are not saved until the next restart. Fix that file and rename it back to mutes.yml before restarting.", broken);
        }
    }

    void enabled(boolean on) { enabled = on; }

    /** The active mute of a player, or null. Expired mutes are dropped on the way. */
    Mute active(UUID player, long now) {
        if (!enabled) return null;
        Mute mute = mutes.get(player);
        if (mute == null) return null;
        if (mute.active(now)) return mute;
        if (mutes.remove(player, mute)) save();
        return null;
    }

    void mute(Mute mute) {
        synchronized (writeLock) { mutes.put(mute.player(), mute); }
        save();
    }

    /** Returns the removed mute or null. */
    Mute unmute(UUID player) {
        Mute removed;
        synchronized (writeLock) { removed = mutes.remove(player); }
        if (removed != null) save();
        return removed;
    }

    /** Active mutes, ending soonest first, permanent ones last. */
    List<Mute> list(long now) {
        List<Mute> list = new ArrayList<>();
        for (Mute mute : mutes.values()) if (mute.active(now)) list.add(mute);
        list.sort(Comparator.comparingLong((Mute mute) -> mute.permanent() ? Long.MAX_VALUE : mute.until()).thenComparing(Mute::name));
        return list;
    }

    String toYaml() {
        return toYaml(mutes);
    }

    private String toYaml(Map<UUID, Mute> values) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Mute mute : values.values()) {
            String path = "mutes." + mute.player();
            yaml.set(path + ".name", mute.name());
            yaml.set(path + ".since", mute.since());
            yaml.set(path + ".until", mute.until());
            yaml.set(path + ".reason", mute.reason());
            yaml.set(path + ".by", mute.by());
        }
        return yaml.saveToString();
    }

    /** Administrative changes must reach disk before a successful moderation message is sent. */
    boolean changeDurably(UUID player, Mute replacement) {
        synchronized (writeLock) {
            if (readOnly) return false;
            Map<UUID, Mute> next = new java.util.HashMap<>(mutes);
            if (replacement == null) next.remove(player); else next.put(player, replacement);
            try { files.write(file, toYaml(next).getBytes(StandardCharsets.UTF_8)); }
            catch (IOException failed) { log.log(Level.WARNING, "Could not save moderation change", failed); return false; }
            if (replacement == null) mutes.remove(player); else mutes.put(player, replacement);
            return true;
        }
    }

    /** Writes the current state in the background; writes never overlap. */
    void save() {
        io.execute(this::saveNow);
    }

    void saveNow() {
        if (readOnly) return;
        synchronized (writeLock) {
            try {
                files.write(file, toYaml().getBytes(StandardCharsets.UTF_8));
            } catch (IOException failed) {
                log.log(Level.WARNING, "Could not save mutes.yml", failed);
            }
        }
    }
}
