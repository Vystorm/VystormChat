package at.esoren.vystormchat;

import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Announcement planner: timed broadcasts managed in a dialog ({@code /vchat announcements}). Each announcement has a
 * text (MiniMessage or {@code &} codes), an interval in minutes, an optional world and an on/off switch. Kept in
 * {@code announcements.yml}; the time of the last broadcast lives in memory, so a restart starts every timer anew.
 */
final class Announcements {
    static final int MAX = 50, MAX_TEXT = 256;

    record Announcement(int id, String text, int minutes, String world, boolean enabled) {
        Announcement {
            minutes = Math.clamp(minutes, 1, 10_080);
            world = world == null ? "" : world.strip();
            text = text == null ? "" : text;
        }
    }

    /** Pure timer rule: due when it never ran or {@code minutes} passed since the last run. */
    static boolean due(Announcement a, Long last, long now) { return a.enabled() && (last == null || now - last >= a.minutes() * 60_000L); }

    private final ChatPlugin plugin;
    private final Path file;
    private final Map<Integer, Announcement> entries = new TreeMap<>();
    private final Map<Integer, Long> lastSent = new HashMap<>();
    private int nextId = 1;

    Announcements(ChatPlugin plugin, Path file) {
        this.plugin = plugin;
        this.file = file;
        if (!Files.exists(file)) return;
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file.toFile()).getConfigurationSection("announcements");
        if (all != null) for (String key : all.getKeys(false)) try {
            int id = Integer.parseInt(key);
            ConfigurationSection s = all.getConfigurationSection(key);
            if (s == null || s.getString("text", "").isBlank()) continue;
            entries.put(id, new Announcement(id, s.getString("text"), s.getInt("minutes", 15), s.getString("world", ""), s.getBoolean("enabled", true)));
            nextId = Math.max(nextId, id + 1);
        } catch (NumberFormatException ignored) { /* damaged row */ }
        // First broadcasts come one interval after the start, not all at once.
        long now = System.currentTimeMillis();
        for (Announcement a : entries.values()) lastSent.put(a.id(), now);
    }

    boolean enabled() { return plugin.getConfig().getBoolean("announcements.enabled", true); }
    List<Announcement> all() { return List.copyOf(entries.values()); }

    /** Every 20 seconds from the plugin timer. */
    void tick() {
        if (!enabled() || entries.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Announcement a : entries.values())
            if (due(a, lastSent.get(a.id()), now)) { lastSent.put(a.id(), now); broadcast(a); }
    }

    private void broadcast(Announcement a) {
        Component body = Formats.loose(a.text());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!a.world().isEmpty() && !player.getWorld().getName().equalsIgnoreCase(a.world())) continue;
            plugin.core().compat().platform().sendMessage(player, plugin.lang().component(player, "announce.prefix").append(body));
        }
    }

    private boolean save() {
        YamlConfiguration y = new YamlConfiguration();
        y.createSection("announcements");
        for (Announcement a : entries.values()) {
            String p = "announcements." + a.id() + ".";
            y.set(p + "text", a.text()); y.set(p + "minutes", a.minutes()); y.set(p + "world", a.world()); y.set(p + "enabled", a.enabled());
        }
        try { Files.createDirectories(file.getParent()); plugin.core().data().files().write(file, y.saveToString().getBytes(StandardCharsets.UTF_8)); return true; }
        catch (IOException failure) { plugin.getLogger().warning("announcements.yml not saved: " + failure.getMessage()); return false; }
    }

    private boolean put(Announcement a) {
        Announcement old = entries.put(a.id(), a);
        if (save()) { lastSent.putIfAbsent(a.id(), System.currentTimeMillis()); return true; }
        if (old == null) entries.remove(a.id()); else entries.put(a.id(), old);
        return false;
    }

    // ---------------------------------------------------------------- dialogs

    private Lang lang() { return plugin.lang(); }

    /** The planner: one button per announcement, plus "new". */
    void open(Player player) {
        Lang lang = lang();
        if (!enabled()) { lang.send(player, "announce.disabled"); return; }
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(lang.component(player, entries.isEmpty() ? "announce.empty" : "announce.intro"), 300));
        List<DialogButton> buttons = new ArrayList<>();
        for (Announcement a : entries.values()) {
            String preview = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Formats.loose(a.text()));
            if (preview.length() > 40) preview = preview.substring(0, 39) + "…";
            buttons.add(new DialogButton(lang.component(player, a.enabled() ? "announce.row" : "announce.row-off", "id", a.id(), "minutes", a.minutes(), "text", preview),
                    (who, response) -> edit(who, a.id())));
        }
        if (entries.size() < MAX) buttons.add(new DialogButton(lang.component(player, "announce.new"), (who, response) -> edit(who, 0)));
        plugin.core().dialogs().open(plugin, player, new DialogRequest(lang.component(player, "announce.title"), body, List.of(), buttons,
                p -> p.isOnline() && p.hasPermission("vystormchat.announce"), () -> {}, lang.plain(player, "dialog.cancel"), 1));
    }

    /** Edit dialog; {@code id} 0 creates a new announcement. */
    void edit(Player player, int id) {
        Lang lang = lang();
        Announcement current = entries.get(id);
        List<DialogInput> inputs = List.of(
                DialogInput.text("text", lang.component(player, "announce.input-text")).initial(current == null ? "" : current.text()).maxLength(MAX_TEXT).width(320).build(),
                DialogInput.text("minutes", lang.component(player, "announce.input-minutes")).initial(current == null ? "15" : Integer.toString(current.minutes())).maxLength(6).build(),
                DialogInput.text("world", lang.component(player, "announce.input-world")).initial(current == null ? "" : current.world()).maxLength(64).build());
        List<DialogButton> buttons = new ArrayList<>();
        buttons.add(new DialogButton(lang.component(player, "announce.save"), (who, response) -> {
            String text = response.getText("text");
            if (text == null || text.isBlank() || text.length() > MAX_TEXT) { lang.send(who, "announce.error-text"); return; }
            int minutes;
            try { minutes = Integer.parseInt(Objects.requireNonNullElse(response.getText("minutes"), "").strip()); } catch (NumberFormatException bad) { lang.send(who, "announce.error-minutes"); return; }
            if (minutes < 1 || minutes > 10_080) { lang.send(who, "announce.error-minutes"); return; }
            String world = Objects.requireNonNullElse(response.getText("world"), "").strip();
            if (!world.isEmpty() && Bukkit.getWorld(world) == null) { lang.send(who, "announce.error-world", "world", world); return; }
            int target = current == null ? nextId++ : current.id();
            if (!put(new Announcement(target, text.strip(), minutes, world, current == null || current.enabled()))) { lang.send(who, "announce.error-storage"); return; }
            lang.send(who, "announce.saved", "id", target);
            open(who);
        }));
        if (current != null) {
            buttons.add(new DialogButton(lang.component(player, current.enabled() ? "announce.pause" : "announce.resume"), (who, response) -> {
                Announcement now = entries.get(id);
                if (now != null && !put(new Announcement(now.id(), now.text(), now.minutes(), now.world(), !now.enabled()))) lang.send(who, "announce.error-storage");
                open(who);
            }));
            buttons.add(new DialogButton(lang.component(player, "announce.send-now"), (who, response) -> {
                Announcement now = entries.get(id);
                if (now != null) { broadcast(now); lastSent.put(id, System.currentTimeMillis()); }
                open(who);
            }));
            buttons.add(new DialogButton(lang.component(player, "announce.delete"), (who, response) -> {
                Announcement removed = entries.remove(id);
                if (removed != null && !save()) { entries.put(id, removed); lang.send(who, "announce.error-storage"); }
                else { lastSent.remove(id); lang.send(who, "announce.deleted", "id", id); }
                open(who);
            }));
        }
        plugin.core().dialogs().open(plugin, player, new DialogRequest(lang.component(player, current == null ? "announce.title-new" : "announce.title-edit", "id", id),
                List.of(DialogBody.plainMessage(lang.component(player, "announce.help"), 320)), inputs, buttons,
                p -> p.isOnline() && p.hasPermission("vystormchat.announce"), () -> {}, lang.plain(player, "dialog.cancel"), 2));
    }
}
