package at.esoren.vystormchat;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/** Reports are allegations, never automatic punishment. Snapshots become visible only after durable writes. */
final class ReportStore {
    static final long RETENTION = 30L * 24 * 60 * 60_000;
    record Entry(long at, UUID actor, String kind, String text) {}
    record Report(UUID id, UUID reporter, String reporterName, UUID target, String targetName,
                  String reason, long created, boolean closed, List<Entry> history) {
        Report {
            history = List.copyOf(history);
        }
        boolean open(long now) { return !closed && now - created < RETENTION; }
    }
    enum Result { SAVED, INVALID, LIMITED, MISSING, UNAVAILABLE }
    private final Path file;
    private final Logger log;
    private Map<UUID, Report> reports = Map.of();
    private boolean readOnly;
    private final AtomicFilesInterface files;

    ReportStore(Path file, Logger log, AtomicFilesInterface files) {
        this.file = file;
        this.files = files;
        this.log = log;
        load();
    }
    List<Report> list(long now) {
        return reports.values().stream().sorted(Comparator.comparing((Report r) -> !r.open(now)).thenComparing(Report::created, Comparator.reverseOrder())).toList();
    }
    Report get(UUID id) { return reports.get(id); }
    static boolean valid(String text, int max) {
        return text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= max && text.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0x00a7 || c == 0x2028 || c == 0x2029);
    }
    Result submit(UUID reporter, String reporterName, UUID target, String targetName, String reason, long now) {
        if (reporter == null || target == null || reporter.equals(target) || !valid(reporterName, 64) || !valid(targetName, 64) || !valid(reason, 500)) return Result.INVALID;
        if (reports.values().stream().anyMatch(r -> r.reporter().equals(reporter) && (now - r.created() < 60_000 || r.target().equals(target) && r.open(now)))) return Result.LIMITED;
        Map<UUID, Report> next = new LinkedHashMap<>(reports);
        next.values().removeIf(r -> !r.open(now) && now - r.created() > RETENTION * 2);
        if (next.size() >= 1000 || next.values().stream().filter(r -> r.reporter().equals(reporter) && r.open(now)).count() >= 10) return Result.LIMITED;
        UUID id = UUID.randomUUID();
        next.put(id, new Report(id, reporter, reporterName, target, targetName, reason.strip(), now, false, List.of()));
        return persist(next);
    }
    Result update(UUID id, UUID actor, String kind, String text, long now) {
        Report r = get(id);
        if (r == null) return Result.MISSING;
        if (actor == null || !Set.of("note", "resolve", "reopen", "mute-request", "unmute-request").contains(kind) || !valid(text, 500)) return Result.INVALID;
        if (r.history().size() >= 100) return Result.LIMITED;
        if (kind.equals("reopen") && now - r.created() >= RETENTION) return Result.INVALID;
        List<Entry> history = new ArrayList<>(r.history());
        history.add(new Entry(now, actor, kind, text.strip()));
        Map<UUID, Report> next = new LinkedHashMap<>(reports);
        next.put(id, new Report(r.id(), r.reporter(), r.reporterName(), r.target(), r.targetName(), r.reason(), r.created(), kind.equals("resolve") || !kind.equals("reopen") && r.closed(), history));
        return persist(next);
    }
    private Result persist(Map<UUID, Report> next) {
        if (readOnly) return Result.UNAVAILABLE;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("schema", 1);
            y.createSection("reports");
            for (Report r : next.values()) {
                String p = "reports." + r.id();
                y.set(p + ".reporter", r.reporter().toString());
                y.set(p + ".reporter-name", r.reporterName());
                y.set(p + ".target", r.target().toString());
                y.set(p + ".target-name", r.targetName());
                y.set(p + ".reason", r.reason());
                y.set(p + ".created", r.created());
                y.set(p + ".closed", r.closed());
                y.set(p + ".history", r.history().stream().map(e -> Map.of("at", e.at(), "actor", e.actor().toString(), "kind", e.kind(), "text", e.text())).toList());
            }
            files.write(file, y);
            reports = Map.copyOf(next);
            return Result.SAVED;
        } catch (Exception failure) {
            log.warning("Could not save chat reports: " + failure.getMessage());
            return Result.UNAVAILABLE;
        }
    }
    private void load() {
        if (!Files.exists(file)) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.load(file.toFile());
            var all = y.getConfigurationSection("reports");
            if (y.getInt("schema") != 1 || all == null || all.getKeys(false).size() > 1000) throw new IllegalArgumentException("Invalid report data");
            Map<UUID, Report> loaded = new LinkedHashMap<>();
            for (String key : all.getKeys(false)) {
                var s = all.getConfigurationSection(key);
                if (s == null || !s.isLong("created") && !s.isInt("created") || !s.isBoolean("closed")) throw new IllegalArgumentException("Invalid report");
                UUID id = UUID.fromString(key), reporter = UUID.fromString(s.getString("reporter", "")), target = UUID.fromString(s.getString("target", ""));
                String reporterName = s.getString("reporter-name"), targetName = s.getString("target-name"), reason = s.getString("reason");
                if (!valid(reporterName, 64) || !valid(targetName, 64) || !valid(reason, 500) || s.getLong("created") < 0 || !(s.get("history") instanceof List<?> raw) || raw.size() > 100) throw new IllegalArgumentException("Invalid report metadata");
                List<Entry> history = new ArrayList<>();
                for (Object value : raw) {
                    if (!(value instanceof Map<?, ?> e) || !(e.get("at") instanceof Number at) || !(e.get("text") instanceof String text) || !valid(text, 500) || !(e.get("kind") instanceof String kind) || !Set.of("note", "resolve", "reopen", "mute-request", "unmute-request").contains(kind)) throw new IllegalArgumentException("Invalid report history");
                    history.add(new Entry(at.longValue(), UUID.fromString(Objects.toString(e.get("actor"))), kind, text));
                }
                loaded.put(id, new Report(id, reporter, reporterName, target, targetName, reason, s.getLong("created"), s.getBoolean("closed"), history));
            }
            reports = Map.copyOf(loaded);
        } catch (Exception failure) {
            readOnly = true;
            log.warning("Chat report data retained; reports are read-only: " + failure.getMessage());
        }
    }
}
