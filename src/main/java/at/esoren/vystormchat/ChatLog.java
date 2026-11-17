package at.esoren.vystormchat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat log in {@code logs/chat-YYYY-MM-DD.log}: a new file each day and when a file grows beyond the size limit
 * ({@code chat-YYYY-MM-DD.1.log}, {@code .2.log} ...). Files older than the keep time are deleted. Lines are written by
 * one background thread, so chat never waits for the disk; {@link #close()} writes what is still queued.
 */
final class ChatLog {
    private static final Pattern NAME = Pattern.compile("chat-(\\d{4}-\\d{2}-\\d{2})(?:\\.(\\d+))?\\.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path folder;
    private final Logger log;
    private final ZoneId zone;
    private final BlockingQueue<String[]> queue = new LinkedBlockingQueue<>(100_000);
    private volatile boolean enabled;
    private volatile long maxBytes;
    private volatile int keepDays;
    private Thread thread;
    private volatile boolean running;

    // writer state, only used by the writer thread (or by close after the thread ended)
    private BufferedWriter writer;
    private LocalDate day;
    private int part;
    private long written;

    ChatLog(Path folder, Logger log, ZoneId zone) {
        this.folder = folder;
        this.log = log;
        this.zone = zone;
    }

    void configure(boolean enabled, double maxMegabytes, int keepDays) {
        this.enabled = enabled;
        this.maxBytes = Math.max(64 * 1024, (long) (maxMegabytes * 1024 * 1024));
        this.keepDays = Math.max(0, keepDays);
    }

    boolean enabled() { return enabled; }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "VystormChat-log");
        thread.setDaemon(true);
        thread.start();
    }

    /** Queues a line ("[channel] name: text"); dropped only if 100,000 lines are waiting (disk gone). */
    void log(String type, String line) {
        if (!enabled) return;
        if (!queue.offer(new String[] {String.valueOf(System.currentTimeMillis()), type, line.replace('\n', ' ').replace('\r', ' ')}))
            log.warning("Chat log queue is full, a line was not written");
    }

    private void run() {
        long lastCleanup = 0;
        while (running || !queue.isEmpty()) {
            try {
                String[] entry = queue.poll(1, TimeUnit.SECONDS);
                if (entry != null) {
                    write(entry);
                    List<String[]> more = new ArrayList<>();
                    queue.drainTo(more, 500);
                    for (String[] next : more) write(next);
                }
                if (writer != null) writer.flush();
                long now = System.currentTimeMillis();
                if (now - lastCleanup > 3_600_000L) { lastCleanup = now; cleanup(LocalDate.now(zone)); }
            } catch (InterruptedException interrupted) {
                if (!running) break;
            } catch (IOException failed) {
                log.log(Level.WARNING, "Could not write the chat log", failed);
                closeWriter();
            }
        }
        // after stop: whatever is left
        try {
            String[] entry;
            while ((entry = queue.poll()) != null) write(entry);
        } catch (IOException failed) {
            log.log(Level.WARNING, "Could not write the chat log", failed);
        }
        closeWriter();
    }

    /** Writes one entry, opening the right file first. Package-private for tests. */
    void write(String[] entry) throws IOException {
        Instant instant = Instant.ofEpochMilli(Long.parseLong(entry[0]));
        LocalDate date = LocalDate.ofInstant(instant, zone);
        String line = TIME.format(instant.atZone(zone)) + " [" + entry[1] + "] " + entry[2] + System.lineSeparator();
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (writer == null || !date.equals(day)) open(date, false);
        else if (written + bytes.length > maxBytes && written > 0) open(date, true);
        writer.write(line);
        written += bytes.length;
    }

    private void open(LocalDate date, boolean full) throws IOException {
        closeWriter();
        Files.createDirectories(folder);
        if (!date.equals(day)) { day = date; part = 0; }
        else if (full) part++;
        Path file;
        while (true) {
            file = folder.resolve(fileName(date, part));
            long size = Files.isRegularFile(file) ? Files.size(file) : 0;
            if (size < maxBytes) { written = size; break; }
            part++;
        }
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String fileName(LocalDate date, int part) { return "chat-" + date + (part > 0 ? "." + part : "") + ".log"; }

    void flushForTest() throws IOException { if (writer != null) writer.flush(); }

    private void closeWriter() {
        if (writer == null) return;
        try { writer.close(); } catch (IOException ignored) { /* nothing more to do */ }
        writer = null;
    }

    /** Deletes log files older than the keep time (0 = keep forever). */
    void cleanup(LocalDate today) {
        int keep = keepDays;
        if (keep <= 0 || !Files.isDirectory(folder)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "chat-*.log")) {
            for (Path file : files) {
                Matcher matcher = NAME.matcher(file.getFileName().toString());
                if (!matcher.matches()) continue;
                try {
                    if (LocalDate.parse(matcher.group(1)).isBefore(today.minusDays(keep))) Files.deleteIfExists(file);
                } catch (DateTimeParseException | IOException skipped) { /* not ours or locked */ }
            }
        } catch (IOException failed) {
            log.log(Level.FINE, "Chat log cleanup failed", failed);
        }
    }

    /** Stops the writer thread after writing every queued line (waits up to 5 seconds). */
    void close() {
        Thread current;
        synchronized (this) {
            running = false;
            current = thread;
            thread = null;
        }
        if (current == null) return;
        // no interrupt: it would close the file channel in the middle of a write; the loop wakes up within a second
        try { current.join(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
