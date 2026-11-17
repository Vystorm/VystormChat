package at.esoren.vystormchat;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One small YAML file per player ({@code players/<uuid>.yml}). All file work runs on one background thread in
 * order, so a save on quit always finishes before the next load on join. Broken files are kept as
 * {@code .corrupt-<time>} and the player starts with defaults instead of losing the server's chat.
 */
final class ProfileStore {
    private final Path folder;
    private final Logger log;
    private final ExecutorService io;
    private final Map<UUID, Profile> online = new ConcurrentHashMap<>();
    /** Players whose broken file could not be moved aside: never written until the next restart. */
    private final java.util.Set<UUID> readOnly = ConcurrentHashMap.newKeySet();

    private final AtomicFilesInterface files;

    ProfileStore(Path folder, Logger log, AtomicFilesInterface files) {
        this(folder, log, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VystormChat-profiles");
            thread.setDaemon(true);
            return thread;
        }), files);
    }

    ProfileStore(Path folder, Logger log, ExecutorService io, AtomicFilesInterface files) {
        this.folder = folder;
        this.files = files;
        this.log = log;
        this.io = io;
    }

    Path file(UUID id) { return folder.resolve(id + ".yml"); }

    /** Reads a profile from disk (defaults when there is none or it is broken). Any thread; does not cache. */
    Profile read(UUID id) {
        Path file = file(id);
        if (!Files.isRegularFile(file)) return new Profile(id);
        try {
            return Profile.fromYaml(id, Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception broken) {
            Path backup = folder.resolve(id + ".yml.corrupt-" + System.currentTimeMillis());
            try { Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException moveFailed) { readOnly.add(id); backup = file; } // still in place: never save the defaults over it
            log.log(Level.WARNING, "Chat profile " + file.getFileName() + " could not be read, kept as " + backup.getFileName() + "; the player starts with defaults", broken);
            return new Profile(id);
        }
    }

    /** Writes a profile now on the calling thread. */
    void write(Profile profile) {
        if (readOnly.contains(profile.id)) return;
        try {
            files.write(file(profile.id), profile.toYaml().getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            profile.markDirty(); // try again with the next flush
            log.log(Level.WARNING, "Could not save chat profile " + profile.id, failed);
        }
    }

    /** Loads in the background (in order with saves) and caches the profile for an online player. */
    CompletableFuture<Profile> load(UUID id) {
        Profile cached = online.get(id);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return CompletableFuture.supplyAsync(() -> online.computeIfAbsent(id, this::read), io);
    }

    /** The cached profile, loading it right away when it is missing (only after /reload or a skipped pre-login). */
    Profile get(UUID id) {
        Profile cached = online.get(id);
        return cached != null ? cached : online.computeIfAbsent(id, this::read);
    }

    Profile cached(UUID id) { return online.get(id); }

    Collection<Profile> all() { return online.values(); }

    /** Saves in the background if changed and forgets the profile (player quit). */
    void unload(UUID id) {
        Profile profile = online.remove(id);
        if (profile != null && profile.takeDirty()) io.execute(() -> write(profile));
    }

    /** Saves every changed profile in the background. */
    void flush() {
        for (Profile profile : online.values()) if (profile.takeDirty()) io.execute(() -> write(profile));
    }

    /** Offline edit (e.g. unignore from a menu while the other player is offline is not needed, but admin tools may). */
    CompletableFuture<Void> edit(UUID id, java.util.function.Consumer<Profile> change) {
        Profile cached = online.get(id);
        if (cached != null) { change.accept(cached); return CompletableFuture.completedFuture(null); }
        return CompletableFuture.runAsync(() -> { Profile profile = read(id); change.accept(profile); write(profile); }, io);
    }

    /** Saves everything and stops the background thread (plugin disable); waits up to 10 seconds. */
    void close() {
        for (Profile profile : online.values()) if (profile.takeDirty()) io.execute(() -> write(profile));
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) log.warning("Chat profiles took longer than 10 s to save");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
