package at.esoren.vystormchat;

import java.util.*;
import java.util.function.Consumer;
import java.time.Instant;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Staff-only case history, explicit timed moderation, and player reports with durable abuse limits. */
final class Reports {
    private final ChatPlugin plugin;
    private final ReportStore store;
    Reports(ChatPlugin plugin, ReportStore store) {
        this.plugin = plugin;
        this.store = store;
    }
    private boolean user(Player p) {
        return p.isOnline() && p.hasPermission("vystormchat.use");
    }
    private boolean staff(Player p) {
        return user(p) && p.hasPermission("vystormchat.moderate");
    }
    /** reports.enabled in config.yml: when off every entry point answers "feature-off"; saved cases stay in reports.yml. */
    private boolean off(Player p) {
        return plugin.refuse(p, Settings.Feature.REPORTS);
    }
    private boolean mutesOff(Player p) {
        return plugin.refuse(p, Settings.Feature.MUTES);
    }
    private void result(Player p, ReportStore.Result r) {
        plugin.lang().send(p, "reports.result-" + r.name().toLowerCase(Locale.ROOT));
    }
    void submit(Player p, String name, String reason) {
        if (!user(p) || off(p)) return;
        OfflinePlayer target = plugin.offline(name);
        if (target == null) {
            plugin.lang().send(p, "player-unknown", "player", name);
            return;
        }
        String targetName = Objects.toString(target.getName(), name);
        ReportStore.Result outcome = store.submit(p.getUniqueId(), p.getName(), target.getUniqueId(), targetName, reason, System.currentTimeMillis());
        result(p, outcome);
        // discord.reports.enabled: a copy for the staff webhook, only for a report that was really saved
        if (outcome == ReportStore.Result.SAVED && plugin.discord() != null) plugin.discord().report(p.getName(), targetName, reason);
    }
    private void prompt(Player p, String key, String initial, boolean moderator, Consumer<String> action) {
        p.closeInventory();
        plugin.core().dialogs().prompt(plugin, p, plugin.lang().ref("reports.title"), plugin.lang().ref("reports." + key), initial,
                text -> {
                    if ((moderator ? staff(p) : user(p)) && text != null && !off(p)) action.accept(text);
                }, () -> {}, moderator ? this::staff : this::user);
    }
    void reportDialog(Player p) {
        if (user(p) && !off(p)) prompt(p, "player-prompt", "", false, name -> prompt(p, "reason-prompt", "", false, reason -> submit(p, name.strip(), reason)));
    }
    void list(Player p, int page) {
        if (off(p)) return;
        if (!plugin.openPanel(p, "reports", Integer.toString(page))) listChest(p, page);
    }
    void listChest(Player p, int page) {
        if (!staff(p) || off(p)) return;
        var rows = store.list(System.currentTimeMillis());
        int current = Math.clamp(page, 0, Math.max(0, (rows.size() - 1) / 45));
        Menu menu = Menu.create(plugin.lang().component(p, "reports.title"), 6);
        for (int i = current * 45; i < Math.min(rows.size(), current * 45 + 45); i++) {
            var r = rows.get(i);
            menu.set(i - current * 45, icon(p, Material.WRITABLE_BOOK, Component.text(r.targetName()),
                    plugin.lang().component(p, "reports.by", "player", r.reporterName()), state(p, r), Component.text(shortText(r.reason(), 80))),
                    (who, click) -> detail(who, r.id(), 0));
        }
        menu.set(45, button(p, Material.ARROW, "previous"), (who, click) -> list(who, current - 1));
        menu.set(49, button(p, Material.ARROW, "back"), (who, click) -> plugin.menu().moderation(who));
        menu.set(53, button(p, Material.ARROW, "next"), (who, click) -> list(who, current + 1));
        menu.open(p);
    }
    private Component state(Player p, ReportStore.Report r) {
        return plugin.lang().component(p, "reports." + (r.open(System.currentTimeMillis()) ? "open" : r.closed() ? "closed" : "expired"));
    }
    void detail(Player p, UUID id, int page) {
        if (off(p)) return;
        if (!plugin.openPanel(p, "report", id + ":" + page)) detailChest(p, id, page);
    }
    void detailChest(Player p, UUID id, int page) {
        if (!staff(p) || off(p)) return;
        var r = store.get(id);
        if (r == null) return;
        Menu menu = Menu.create(plugin.lang().component(p, "reports.case", "player", r.targetName()), 6);
        menu.set(0, icon(p, Material.PAPER, Component.text(r.targetName()), Component.text(r.id().toString()), plugin.lang().component(p, "reports.by", "player", r.reporterName()),
                Component.text(Instant.ofEpochMilli(r.created()).toString()), state(p, r), Component.text(r.reason())), null);
        var mute = plugin.mutes().active(r.target(), System.currentTimeMillis());
        menu.set(8, icon(p, Material.CLOCK, plugin.lang().component(p, "reports.current-mute"),
                Component.text(mute == null ? plugin.lang().plain(p, "reports.none") : mute.permanent() ? plugin.lang().plain(p, "permanent") : Durations.format(mute.remaining(System.currentTimeMillis())))), null);
        int current = Math.clamp(page, 0, Math.max(0, (r.history().size() - 1) / 27));
        for (int i = current * 27; i < Math.min(r.history().size(), current * 27 + 27); i++) {
            var e = r.history().get(r.history().size() - 1 - i);
            menu.set(9 + i - current * 27, icon(p, Material.BOOK, plugin.lang().component(p, "reports.event-" + e.kind()), Component.text(Instant.ofEpochMilli(e.at()).toString()),
                    Component.text(ChatMenu.name(e.actor())), Component.text(e.text())), null);
        }
        menu.set(36, button(p, Material.ARROW, "previous"), (who, click) -> detail(who, id, current - 1));
        menu.set(44, button(p, Material.ARROW, "next"), (who, click) -> detail(who, id, current + 1));
        menu.set(45, button(p, Material.ARROW, "back"), (who, click) -> list(who, 0));
        menu.set(46, button(p, Material.FEATHER, "note"), (who, click) -> edit(who, id, "note", current));
        menu.set(47, button(p, Material.EMERALD, r.closed() ? "reopen" : "resolve"), (who, click) -> edit(who, id, "status", current));
        if (p.hasPermission("vystormchat.mute") && !plugin.off(Settings.Feature.MUTES)) {
            menu.set(50, button(p, Material.LEAD, "mute"), (who, click) -> muteDialog(who, id));
            menu.set(51, button(p, Material.SHEARS, "unmute"), (who, click) -> confirmAction(who, id, 0, true));
        }
        menu.open(p);
    }
    ReportStore store() {
        return store;
    }
    void edit(Player p, UUID id, String requested, int page) {
        if (!staff(p) || off(p)) return;
        var latest = store.get(id);
        if (latest == null) return;
        String action = requested.equals("note") ? "note" : latest.closed() ? "reopen" : "resolve";
        prompt(p, "note-prompt", "", true, text -> {
            // A different moderator may have changed the case while this prompt was open.
            var current = store.get(id);
            if (current == null) {
                result(p, ReportStore.Result.MISSING);
                return;
            }
            if (!action.equals("note") && current.closed() != latest.closed()) {
                detail(p, id, page);
                return;
            }
            result(p, store.update(id, p.getUniqueId(), action, text, System.currentTimeMillis()));
            detail(p, id, page);
        });
    }
    void muteDialog(Player p, UUID id) {
        if (!staff(p) || !p.hasPermission("vystormchat.mute") || off(p) || mutesOff(p) || store.get(id) == null) return;
        prompt(p, "duration-prompt", "30m", true, text -> {
            long duration = Durations.muteTime(text.strip());
            if (duration <= 0 || duration > 7L * 24 * 60 * 60_000) {
                result(p, ReportStore.Result.INVALID);
                return;
            }
            confirmAction(p, id, duration, false);
        });
    }
    void confirmAction(Player p, UUID id, long duration, boolean unmute) {
        if (!staff(p) || !p.hasPermission("vystormchat.mute") || off(p) || mutesOff(p)) return;
        p.closeInventory();
        plugin.core().dialogs().confirm(plugin, p, plugin.lang().ref("reports." + (unmute ? "unmute" : "mute")), plugin.lang().ref("reports.action-confirm"), () -> {
            if (!staff(p) || !p.hasPermission("vystormchat.mute") || off(p) || mutesOff(p)) return;
            var r = store.get(id);
            if (r == null) return;
            // Record the request first. The current mute shows the outcome; a crash cannot falsely record a completed action.
            var saved = store.update(id, p.getUniqueId(), unmute ? "unmute-request" : "mute-request", unmute ? id.toString() : Durations.format(duration), System.currentTimeMillis());
            if (saved != ReportStore.Result.SAVED) {
                result(p, saved);
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(r.target());
            if (unmute) plugin.unmute(p, target);
            else plugin.mute(p, target, duration, plugin.lang().plain(p, "reports.mute-reason", "id", r.id(), "reason", shortText(r.reason(), 120)));
            detail(p, id, 0);
        }, () -> detail(p, id, 0), this::staff);
    }
    static String shortText(String s, int max) {
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max)) + "…";
    }
    private ItemStack button(Player p, Material material, String key) {
        return Menu.item(plugin.lang(), p, material, "reports." + key, null);
    }
    private ItemStack icon(Player p, Material material, Component title, Component... lore) {
        var platform = plugin.core().compat().platform();
        ItemStack i = new ItemStack(material);
        platform.editMeta(i, m -> {
            platform.displayName(m, Menu.plain(title));
            platform.lore(m, Menu.lines(Arrays.asList(lore)));
        });
        return i;
    }
}
