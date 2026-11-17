package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profiles, mutes and the chat log on disk, including broken files. */
class StorageTest {
    private static final Logger LOG = Logger.getLogger("test");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @TempDir Path folder;

    @Test
    void profileRoundTrip() throws Exception {
        ProfileStore store = new ProfileStore(folder, LOG, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        Profile profile = store.load(ID).get();
        assertSame(profile, store.cached(ID));
        var channels = TestChannels.parse("channels:\n  global:\n    scope: global\n  opt:\n    auto-join: false\n");
        Channel global = channels.get("global"), opt = channels.get("opt");
        assertTrue(profile.listening(global));
        assertFalse(profile.listening(opt), "opt-in channels start off");
        profile.focus("trade");
        profile.listen(global, false);
        profile.listen(opt, true);
        UUID other = UUID.randomUUID();
        assertTrue(profile.toggleIgnore(other));
        profile.mentionSound(false);
        profile.timestamps(true);
        profile.allowMessages(false);
        profile.spy(true);
        store.unload(ID);
        store.close();

        ProfileStore again = new ProfileStore(folder, LOG, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        Profile loaded = again.load(ID).get();
        assertEquals("trade", loaded.focus());
        assertFalse(loaded.listening(global));
        assertTrue(loaded.listening(opt));
        assertTrue(loaded.ignores(other));
        assertFalse(loaded.mentionSound());
        assertTrue(loaded.messageSound());
        assertTrue(loaded.timestamps());
        assertFalse(loaded.allowMessages());
        assertTrue(loaded.spy());
        assertFalse(loaded.toggleIgnore(other), "second toggle removes");
        again.close();
    }

    @Test
    void channelsThatCannotBeLeftAlwaysListen() {
        Profile profile = new Profile(ID);
        Channel fixed = TestChannels.one("leavable: false\nauto-join: false");
        profile.listen(fixed, false);
        assertTrue(profile.listening(fixed));
    }

    @Test
    void brokenProfileIsKeptAndDefaultsUsed() throws Exception {
        Files.writeString(folder.resolve(ID + ".yml"), "focus: [unclosed\n  - : :", StandardCharsets.UTF_8);
        ProfileStore store = new ProfileStore(folder, LOG, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        Profile profile = store.read(ID);
        assertEquals("", profile.focus());
        assertTrue(profile.mentionSound());
        try (Stream<Path> files = Files.list(folder)) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString().startsWith(ID + ".yml.corrupt-")));
        }
        assertFalse(Files.exists(folder.resolve(ID + ".yml")));
        store.close();
    }

    @Test
    void brokenEntriesInsideAProfileAreSkipped() throws Exception {
        Profile profile = Profile.fromYaml(ID, "ignored: [not-a-uuid, " + ID + "]\nleft: [Global]\n");
        assertEquals(Set.of(ID), profile.ignored);
        assertEquals(Set.of("global"), profile.left);
    }

    @Test
    void savesOnlyChangedProfiles() throws Exception {
        ProfileStore store = new ProfileStore(folder, LOG, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        store.load(ID).get();
        store.flush();
        store.close();
        assertFalse(Files.exists(folder.resolve(ID + ".yml")), "untouched profiles are not written");
    }

    @Test
    void offlineEditReadsAndWrites() throws Exception {
        ProfileStore store = new ProfileStore(folder, LOG, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        UUID other = UUID.randomUUID();
        store.edit(ID, profile -> profile.toggleIgnore(other)).get();
        store.close();
        assertTrue(new ProfileStore(folder, LOG, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).read(ID).ignores(other));
    }

    @Test
    void mutesRoundTripAndExpire() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        Path file = folder.resolve("mutes.yml");
        MuteStore store = new MuteStore(file, LOG, io, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        long now = System.currentTimeMillis();
        UUID forever = UUID.randomUUID(), shortly = UUID.randomUUID(), gone = UUID.randomUUID();
        store.mute(new MuteStore.Mute(forever, "Forever", now, 0, "spam", "Mod"));
        store.mute(new MuteStore.Mute(shortly, "Shortly", now, now + 60_000, "", "Console"));
        store.mute(new MuteStore.Mute(gone, "Gone", now - 10_000, now - 1, "old", "Mod"));
        io.shutdown();
        io.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        MuteStore loaded = new MuteStore(file, LOG, Runnable::run, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        loaded.load();
        MuteStore.Mute mute = loaded.active(forever, now);
        assertNotNull(mute);
        assertTrue(mute.permanent());
        assertEquals("spam", mute.reason());
        assertEquals("Mod", mute.by());
        assertEquals(-1, mute.remaining(now));
        assertNotNull(loaded.active(shortly, now));
        assertNull(loaded.active(shortly, now + 61_000), "expires");
        assertNull(loaded.active(gone, now), "expired mutes are not loaded");
        assertEquals(List.of("Forever"), loaded.list(now + 61_000).stream().map(MuteStore.Mute::name).toList());
        assertNotNull(loaded.unmute(forever));
        assertNull(loaded.unmute(forever));
        assertEquals(List.of(), loaded.list(now + 61_000));
    }

    @Test
    void mutesListSortsSoonestFirst() {
        MuteStore store = new MuteStore(folder.resolve("m.yml"), LOG, Runnable::run, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        long now = System.currentTimeMillis();
        store.mute(new MuteStore.Mute(UUID.randomUUID(), "Late", now, now + 90_000, "", ""));
        store.mute(new MuteStore.Mute(UUID.randomUUID(), "Perm", now, 0, "", ""));
        store.mute(new MuteStore.Mute(UUID.randomUUID(), "Soon", now, now + 10_000, "", ""));
        assertEquals(List.of("Soon", "Late", "Perm"), store.list(now).stream().map(MuteStore.Mute::name).toList());
    }

    @Test
    void brokenMuteFileIsKept() throws IOException {
        Path file = folder.resolve("mutes.yml");
        Files.writeString(file, "mutes: [ : broken", StandardCharsets.UTF_8);
        MuteStore store = new MuteStore(file, LOG, Runnable::run, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        store.load();
        assertEquals(List.of(), store.list(System.currentTimeMillis()));
        try (Stream<Path> files = Files.list(folder)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("mutes.yml.corrupt-")));
        }
        store.mute(new MuteStore.Mute(UUID.randomUUID(), "New", System.currentTimeMillis(), 0, "", ""));
        assertFalse(Files.exists(file), "a broken mutes.yml is never replaced until the next restart");
    }

    @Test
    void chatLogWritesRotatesAndCleansUp() throws IOException {
        Path logs = folder.resolve("logs");
        ChatLog log = new ChatLog(logs, LOG, ZoneOffset.UTC);
        log.configure(true, 0.0625, 3); // 64 KiB per file
        long day = LocalDate.of(2026, 10, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        String line = "x".repeat(1000);
        for (int i = 0; i < 100; i++) log.write(new String[] {Long.toString(day + i), "global", "Steve: " + line});
        log.write(new String[] {Long.toString(day + 86_400_000L), "msg", "a -> b: next day"});
        log.flushForTest();
        assertTrue(Files.size(logs.resolve("chat-2026-10-30.log")) <= 64 * 1024);
        assertTrue(Files.exists(logs.resolve("chat-2026-10-30.1.log")), "a full file continues in .1");
        String nextDay = Files.readString(logs.resolve("chat-2026-10-31.log"));
        assertTrue(nextDay.startsWith("00:00:00 [msg] a -> b: next day"), nextDay);

        Files.writeString(logs.resolve("chat-2026-10-01.log"), "old");
        Files.writeString(logs.resolve("other.log"), "not ours");
        log.cleanup(LocalDate.of(2026, 10, 31));
        assertFalse(Files.exists(logs.resolve("chat-2026-10-01.log")));
        assertTrue(Files.exists(logs.resolve("other.log")));
        assertTrue(Files.exists(logs.resolve("chat-2026-10-30.log")));
        assertEquals("chat-2026-10-30.2.log", ChatLog.fileName(LocalDate.of(2026, 10, 30), 2));
    }

    @Test
    void chatLogThreadWritesQueuedLinesOnClose() throws IOException {
        Path logs = folder.resolve("logs");
        ChatLog log = new ChatLog(logs, LOG, ZoneOffset.UTC);
        log.configure(true, 10, 30);
        log.start();
        for (int i = 0; i < 500; i++) log.log("global", "Player" + i + ": message " + i);
        log.close();
        String today = ChatLog.fileName(LocalDate.now(ZoneOffset.UTC), 0);
        List<String> lines = Files.readAllLines(logs.resolve(today));
        assertEquals(500, lines.size(), "no line is lost");
        assertTrue(lines.getLast().endsWith("[global] Player499: message 499"));
    }

    @Test
    void givenMutesSwitchedOff_whenAsked_thenNobodyIsMutedAndSavedMutesStay() {
        Path file = folder.resolve("switch.yml");
        MuteStore store = new MuteStore(file, LOG, Runnable::run, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        long now = System.currentTimeMillis();
        UUID muted = UUID.randomUUID(), expired = UUID.randomUUID();
        store.mute(new MuteStore.Mute(muted, "Muted", now, 0, "spam", "Mod"));
        store.mute(new MuteStore.Mute(expired, "Short", now, now + 1_000, "", "Mod"));

        store.enabled(false);
        assertNull(store.active(muted, now), "nobody is muted while the feature is off");
        assertNull(store.active(expired, now + 5_000));
        assertTrue(store.toYaml().contains(muted.toString()), "the mute stays saved");
        assertTrue(store.toYaml().contains(expired.toString()), "nothing is dropped while off");

        store.enabled(true);
        assertNotNull(store.active(muted, now), "applies again when switched back on");
        MuteStore loaded = new MuteStore(file, LOG, Runnable::run, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        loaded.load();
        assertNotNull(loaded.active(muted, now));
    }
}
