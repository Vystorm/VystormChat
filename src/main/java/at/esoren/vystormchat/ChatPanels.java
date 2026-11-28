package at.esoren.vystormchat;

import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import static at.esoren.vystormchat.ChatPanel.*;

/** Loaded only after the Core capability guard. Snapshots and actions reuse the existing chat services. */
final class ChatPanels {
    static final List<String> NAMES = List.of("chat", "ignored", "moderation", "mutes", "reports", "report", "showcase", "keywords");
    private final ChatPlugin plugin;
    private final Panels panels;
    private final Map<String, ChatPanel> screens = new HashMap<>();
    private final Map<UUID, Map<String, String>> arguments = new HashMap<>();

    ChatPanels(ChatPlugin plugin) { this(plugin, plugin.core().gui().panels().of(plugin)); }
    ChatPanels(ChatPlugin plugin, Panels panels) {
        this.plugin = plugin;
        this.panels = panels;
        for (String name : NAMES) screens.put(name, new ChatPanel());
    }

    void register() {
        List<String> registered = new ArrayList<>();
        try {
            for (String name : NAMES) {
                PanelSpec.Builder spec = PanelSpec.builder(plugin.lang().ref(titleKey(name)))
                        .access(p -> allowed(p, name))
                        .render(ctx -> screens.get(name).render(ctx, data(ctx.player(), name, ctx.arg()), actions(name)))
                        .fallback(p -> fallback(p, name));
                if (name.equals("chat")) spec.menu(MenuCategory.COMMUNITY, Material.WRITABLE_BOOK, plugin.lang().ref("vmenu-chat-desc"));
                registered.add(name);
                panels.register(name, spec.build());
            }
        } catch (RuntimeException failure) {
            for (String name : registered) {
                try { panels.unregister(name); } catch (RuntimeException ignored) { }
            }
            throw failure;
        }
    }

    boolean open(Player player, String name, String arg) {
        if (!NAMES.contains(name)) return false;
        Settings.Feature feature = feature(name);
        if (feature != null && plugin.refuse(player, feature)) return true;
        if (!allowed(player, name)) { plugin.lang().send(player, "no-permission"); return true; }
        arguments.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>()).put(name, arg);
        if (name.equals("reports")) screens.get(name).page(player.getUniqueId(), page(arg));
        // A new case/snapshot starts at its first page; selection cannot bleed into another case.
        if (name.equals("report") || name.equals("showcase")) screens.get(name).forget(player.getUniqueId());
        if (name.equals("report")) screens.get(name).page(player.getUniqueId(), casePage(arg));
        try { return panels.open(player, name, arg) != PanelOpenResult.NONE; }
        catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "Could not open chat panel " + name + "; using chest", failure);
            return false;
        }
    }

    void forget(UUID player) { arguments.remove(player); screens.values().forEach(screen -> screen.forget(player)); }
    void close() {
        arguments.clear();
        try { plugin.core().gui().panels().release(plugin); } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "Could not release chat panels", failure);
        }
    }

    boolean allowed(Player p, String screen) {
        if (!plugin.isEnabled() || !p.isOnline()) return false;
        return switch (screen) {
            case "ignored" -> p.hasPermission("vystormchat.ignore");
            case "mutes" -> p.hasPermission("vystormchat.mute");
            case "moderation" -> p.hasPermission("vystormchat.moderate");
            case "reports", "report" -> p.hasPermission("vystormchat.use") && p.hasPermission("vystormchat.moderate");
            case "keywords" -> p.hasPermission("vystormchat.use") && p.hasPermission("vystormchat.keywords");
            case "showcase" -> true; // A shared, read-only inventory link has no additional permission.
            default -> p.hasPermission("vystormchat.use");
        };
    }

    private void fallback(Player p, String name) {
        if (!allowed(p, name)) return;
        Settings.Feature feature = feature(name);
        if (feature != null && plugin.refuse(p, feature)) return;
        String arg = arguments.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(name, "");
        switch (name) {
            case "chat" -> plugin.menu().openChest(p);
            case "ignored" -> plugin.menu().ignoredChest(p);
            case "moderation" -> plugin.menu().moderationChest(p);
            case "mutes" -> plugin.menu().mutesChest(p);
            case "reports" -> plugin.reports().listChest(p, page(arg));
            case "report" -> { if (caseId(arg) != null) plugin.reports().detailChest(p, caseId(arg), casePage(arg)); }
            case "showcase" -> { if (uuid(arg) != null) plugin.showcase().openChest(p, uuid(arg)); }
            case "keywords" -> plugin.menu().keywordsChest(p);
            default -> { }
        }
    }

    Actions actions(String screen) {
        return new Actions() {
            public boolean allowed(Player p) { return ChatPanels.this.allowed(p, screen); }
            public Result run(Player p, String action, String target, Object value) {
                if (!allowed(p)) return error(p, "no-permission");
                if (off(feature(screen)) || off(actionFeature(action))) return error(p, "feature-off");
                return act(p, action, target, value);
            }
        };
    }

    /** The feature switch (config.yml) a whole screen belongs to, or null. */
    private static Settings.Feature feature(String screen) {
        return switch (screen) {
            case "ignored" -> Settings.Feature.IGNORE;
            case "mutes" -> Settings.Feature.MUTES;
            case "reports", "report" -> Settings.Feature.REPORTS;
            case "chat", "moderation" -> Settings.Feature.CHAT_MENU;
            case "showcase" -> Settings.Feature.SHOWCASE;
            case "keywords" -> Settings.Feature.KEYWORDS;
            default -> null;
        };
    }

    /** The feature switch one action belongs to, or null. */
    private static Settings.Feature actionFeature(String action) {
        return switch (action) {
            case "message", "reply" -> Settings.Feature.PRIVATE_MESSAGES;
            case "ignored", "unignore", "add-ignore" -> Settings.Feature.IGNORE;
            case "mutes", "unmute", "mute-player", "case-mute", "case-unmute" -> Settings.Feature.MUTES;
            case "reports", "submit", "case", "note", "status" -> Settings.Feature.REPORTS;
            case "listen" -> Settings.Feature.CHANNEL_CHOICE;
            case "spy" -> Settings.Feature.SOCIAL_SPY;
            case "clear" -> Settings.Feature.CLEAR_CHAT;
            case "slow" -> Settings.Feature.SLOW_MODE;
            case "settings" -> Settings.Feature.CHAT_MENU;
            case "keywords", "keyword-add", "keyword-remove", "keyword-alerts", "keyword-test" -> Settings.Feature.KEYWORDS;
            default -> null;
        };
    }

    private boolean off(Settings.Feature feature) { return feature != null && plugin.off(feature); }

    private boolean permission(Player p, String permission) { return p.hasPermission("vystormchat." + permission); }
    private Result error(Player p, String key) { return Result.error(text(p, key)); }

    private Result act(Player p, String action, String target, Object value) {
        switch (action) {
            case "focus", "listen" -> {
                Channel channel = plugin.service().settings().channel(target);
                if (channel == null || !visible(p, channel)) return error(p, "panel.stale");
                if (action.equals("focus")) {
                    if (!channel.writable(p::hasPermission)) return error(p, "no-permission");
                    plugin.focus(p, channel);
                } else {
                    if (!channel.readable(p::hasPermission) || !channel.leavable() || !(value instanceof Boolean on)) return error(p, "no-permission");
                    plugin.listen(p, channel, on);
                }
            }
            case "spy" -> {
                if (!permission(p, "spy") || !(value instanceof Boolean on)) return error(p, "no-permission");
                if (plugin.profiles().get(p.getUniqueId()).spy() != on) plugin.toggleSpy(p);
            }
            case "unignore" -> {
                if (!permission(p, "ignore")) return error(p, "no-permission");
                UUID id = uuid(target);
                if (id == null || !plugin.profiles().get(p.getUniqueId()).ignores(id)) return error(p, "panel.stale");
                plugin.toggleIgnore(p, Bukkit.getOfflinePlayer(id));
            }
            case "clear" -> {
                if (!permission(p, "moderate") || !permission(p, "clearchat")) return error(p, "no-permission");
                plugin.clearChat(p);
            }
            case "slow" -> {
                if (!permission(p, "moderate") || !permission(p, "slowmode")) return error(p, "no-permission");
                if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0
                        || n.doubleValue() > 3600 || n.doubleValue() != n.intValue()) return error(p, "panel.stale");
                plugin.slowMode(p, null, n.intValue());
            }
            case "unmute" -> {
                if (!permission(p, "mute")) return error(p, "no-permission");
                UUID id = uuid(target);
                if (id == null || plugin.mutes().active(id, System.currentTimeMillis()) == null) return error(p, "panel.stale");
                plugin.unmute(p, Bukkit.getOfflinePlayer(id));
                if (plugin.mutes().active(id, System.currentTimeMillis()) != null) return error(p, "reports.result-unavailable");
            }
            case "message", "reply" -> {
                if (!permission(p, "msg")) return error(p, "no-permission");
                String partner = "";
                if (action.equals("reply")) {
                    UUID id = plugin.messages().partner(p.getUniqueId());
                    if (id == null) return error(p, "panel.stale");
                    partner = id.equals(PrivateMessages.CONSOLE) ? "Console" : ChatMenu.name(id);
                }
                plugin.menu().messageDialog(p, partner);
            }
            case "add-ignore" -> {
                if (!permission(p, "ignore")) return error(p, "no-permission");
                plugin.menu().ignoreDialog(p);
            }
            case "mute-player" -> {
                if (!permission(p, "mute")) return error(p, "no-permission");
                plugin.menu().muteDialog(p, "");
            }
            case "submit" -> {
                if (!permission(p, "use")) return error(p, "no-permission");
                plugin.reports().reportDialog(p);
            }
            case "case", "note", "status", "case-mute", "case-unmute" -> {
                if (!permission(p, "use") || !permission(p, "moderate")) return error(p, "no-permission");
                UUID id = uuid(target);
                if (id == null || plugin.reports().store().get(id) == null) return error(p, "reports.result-missing");
                switch (action) {
                    case "case" -> plugin.reports().detail(p, id, 0);
                    case "note", "status" -> plugin.reports().edit(p, id, action, 0);
                    case "case-mute" -> {
                        if (!permission(p, "mute")) return error(p, "no-permission");
                        plugin.reports().muteDialog(p, id);
                    }
                    case "case-unmute" -> {
                        if (!permission(p, "mute")) return error(p, "no-permission");
                        plugin.reports().confirmAction(p, id, 0, true);
                    }
                    default -> { }
                }
            }
            case "settings" -> {
                if (!permission(p, "use")) return error(p, "no-permission");
                plugin.openSettings(p);
            }
            case "inventory-view" -> {
                UUID id = uuid(target);
                if (id == null || plugin.showcase().stored(id) == null) return error(p, "showcase-expired");
                plugin.showcase().openChest(p, id);
            }
            case "keyword-add", "keyword-remove", "keyword-alerts", "keyword-test" -> {
                if (!permission(p, "keywords")) return error(p, "no-permission");
                UUID id = p.getUniqueId();
                Keywords.Reply reply = switch (action) {
                    case "keyword-add" -> Keywords.add(plugin, id, value instanceof String text ? text : "");
                    case "keyword-remove" -> Keywords.remove(plugin, id, target);
                    case "keyword-alerts" -> value instanceof Boolean on ? Keywords.state(plugin, id, on) : null;
                    default -> Keywords.test(plugin, id, value instanceof String text ? text : "");
                };
                if (reply == null) return error(p, "panel.stale");
                String message = text(p, reply.key(), reply.placeholders());
                return reply.ok() ? Result.ok(message) : Result.error(message);
            }
            case "chat", "ignored", "moderation", "mutes", "reports", "keywords" -> {
                if (!allowed(p, action)) return error(p, "no-permission");
                switch (action) {
                    case "chat" -> plugin.menu().open(p);
                    case "ignored" -> plugin.menu().ignored(p);
                    case "moderation" -> plugin.menu().moderation(p);
                    case "mutes" -> plugin.menu().mutes(p);
                    case "reports" -> plugin.reports().list(p, 0);
                    case "keywords" -> plugin.menu().keywords(p);
                    default -> { }
                }
            }
            default -> { return error(p, "panel.stale"); }
        }
        return Result.ok();
    }

    Data data(Player p, String name, String arg) {
        if (!allowed(p, name)) return empty(p, name, "no-permission");
        if (off(feature(name))) return empty(p, name, "feature-off");
        return switch (name) {
            case "chat" -> chat(p);
            case "ignored" -> ignored(p);
            case "moderation" -> moderation(p);
            case "mutes" -> mutes(p);
            case "reports" -> reports(p);
            case "report" -> report(p, caseId(arg));
            case "showcase" -> showcase(p, uuid(arg));
            case "keywords" -> keywords(p);
            default -> empty(p, name, "panel.stale");
        };
    }

    private Data chat(Player p) {
        Profile profile = plugin.profiles().get(p.getUniqueId());
        List<Row> rows = new ArrayList<>();
        for (Channel c : plugin.service().settings().channels.values()) {
            if (!visible(p, c)) continue;
            boolean focused = plugin.focus(p).id().equals(c.id());
            String state = focused ? "focus" : !c.readable(p::hasPermission) ? "write-only" : profile.listening(c) ? "listening" : c.autoJoin() ? "hidden" : "not-joined";
            List<Field> fields = new ArrayList<>();
            fields.add(new Field(text(p, "panel.audience"), text(p, "menu.scope-" + c.scope().name().toLowerCase(Locale.ROOT), "radius", c.radius())));
            if (!c.quickPrefix().isEmpty()) fields.add(new Field(text(p, "panel.shortcut"), c.quickPrefix()));
            if (!c.aliases().isEmpty()) fields.add(new Field(text(p, "panel.command"), "/" + c.aliases().getFirst()));
            if (c.cost() > 0 && plugin.hooks().money() != null) fields.add(new Field(text(p, "panel.cost"), plugin.hooks().money().format(c.cost())));
            int slow = plugin.service().slowMode(c.id());
            if (slow > 0) fields.add(new Field(text(p, "panel.slow"), Durations.format(slow * 1000L)));
            List<Control> controls = new ArrayList<>();
            if (!focused && c.writable(p::hasPermission)) controls.add(button(p, "focus", "panel.focus", Material.FEATHER, c.id(), false));
            if (c.readable(p::hasPermission) && c.leavable() && !off(Settings.Feature.CHANNEL_CHOICE)) controls.add(new Control("listen", text(p, "panel.listen"), Material.OAK_SIGN, c.id(), Kind.TOGGLE, profile.listening(c), false, null));
            Material icon = Material.matchMaterial(c.icon());
            rows.add(new Row(c.id(), c.displayName(), text(p, "menu.state-" + state), Ui.item(icon == null ? Material.OAK_SIGN : icon), fields, controls));
        }
        List<Control> controls = new ArrayList<>(), footer = new ArrayList<>();
        if (permission(p, "msg") && !off(Settings.Feature.PRIVATE_MESSAGES)) {
            controls.add(button(p, "message", "menu.message", Material.WRITABLE_BOOK, "", true));
            UUID partner = plugin.messages().partner(p.getUniqueId());
            if (partner != null) controls.add(new Control("reply", text(p, "menu.reply", "player", partner.equals(PrivateMessages.CONSOLE) ? text(p, "panel.console") : ChatMenu.name(partner)), Material.FEATHER, "", Kind.BUTTON, null, true, null));
        }
        if (permission(p, "spy") && !off(Settings.Feature.SOCIAL_SPY)) controls.add(new Control("spy", text(p, "settings.spy"), Material.ENDER_EYE, "", Kind.TOGGLE, profile.spy(), false, null));
        controls.add(button(p, "settings", "menu.settings", Material.COMPARATOR, "", true));
        if (!off(Settings.Feature.REPORTS)) controls.add(button(p, "submit", "reports.submit", Material.WRITABLE_BOOK, "", true));
        if (permission(p, "ignore") && !off(Settings.Feature.IGNORE)) footer.add(button(p, "ignored", "menu.ignored-title", Material.STRUCTURE_VOID, "", true));
        if (permission(p, "keywords") && !off(Settings.Feature.KEYWORDS)) footer.add(button(p, "keywords", "panel.keywords", Material.NAME_TAG, "", true));
        if (permission(p, "moderate")) footer.add(button(p, "moderation", "menu.moderation-title", Material.IRON_BARS, "", true));
        return new Data(text(p, "menu.title"), text(p, "vmenu-chat-desc"), Material.WRITABLE_BOOK, true, rows, List.of(), controls, footer, true);
    }

    private boolean visible(Player p, Channel c) {
        if (!c.readable(p::hasPermission) && !c.writable(p::hasPermission)) return false;
        PlayerState state = plugin.states().get(p.getUniqueId());
        return !c.scope().group() || state != null && state.groupOf(c.scope()) != null;
    }

    private Data ignored(Player p) {
        List<Row> rows = plugin.profiles().get(p.getUniqueId()).ignored.stream().sorted().map(id -> {
            String name = ChatMenu.name(id);
            Control remove = new Control("unignore", text(p, "player-menu.unignore", "player", name), Material.LIME_DYE, id.toString(), Kind.BUTTON, null, false, null);
            return new Row(id.toString(), name, "", Ui.item(Material.PLAYER_HEAD), List.of(), List.of(remove));
        }).toList();
        return new Data(text(p, "menu.ignored-title"), text(p, "panel.select"), Material.STRUCTURE_VOID, true, rows, List.of(),
                List.of(button(p, "add-ignore", "menu.ignore-add", Material.NAME_TAG, "", true)), List.of(back(p, "chat")), false);
    }

    private Data moderation(Player p) {
        List<Control> controls = new ArrayList<>(), footer = new ArrayList<>();
        if (permission(p, "clearchat") && !off(Settings.Feature.CLEAR_CHAT)) controls.add(new Control("clear", text(p, "menu.clear"), Material.SPONGE, "", Kind.BUTTON, null, false, text(p, "dialog.clear-question")));
        if (permission(p, "slowmode") && !off(Settings.Feature.SLOW_MODE)) controls.add(new Control("slow", text(p, "panel.slow-seconds"), Material.CLOCK, "", Kind.NUMBER, plugin.service().slowMode(null), false, null));
        if (permission(p, "mute") && !off(Settings.Feature.MUTES)) {
            footer.add(button(p, "mutes", "menu.mutes-title", Material.PAPER, "", true));
            controls.add(button(p, "mute-player", "menu.mute-player", Material.LEAD, "", true));
        }
        if (permission(p, "use") && !off(Settings.Feature.REPORTS)) footer.add(button(p, "reports", "reports.title", Material.WRITABLE_BOOK, "", true));
        footer.add(back(p, "chat"));
        return new Data(text(p, "menu.moderation-title"), text(p, "panel.moderation"), Material.IRON_BARS, false, List.of(), List.of(), controls, footer, false);
    }

    private Data mutes(Player p) {
        long now = System.currentTimeMillis();
        List<Row> rows = plugin.mutes().list(now).stream().map(m -> new Row(m.player().toString(), m.name(),
                m.permanent() ? text(p, "permanent") : Durations.format(m.remaining(now)), Ui.item(Material.PLAYER_HEAD),
                List.of(new Field(text(p, "dialog.mute-reason"), m.reason().isBlank() ? text(p, "reports.none") : m.reason()), new Field(text(p, "panel.by"), m.by())),
                List.of(new Control("unmute", text(p, "dialog.unmute-title"), Material.SHEARS, m.player().toString(), Kind.BUTTON, null, false, text(p, "dialog.unmute-question"))))).toList();
        return new Data(text(p, "menu.mutes-title"), text(p, "panel.select"), Material.PAPER, true, rows, List.of(),
                List.of(button(p, "mute-player", "menu.mute-player", Material.LEAD, "", true)), List.of(back(p, "moderation")), false);
    }

    private Data keywords(Player p) {
        Profile profile = plugin.profiles().get(p.getUniqueId());
        List<Row> rows = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String keyword : profile.keywords()) {
            // Row keys come from the phrase, never its position; removing goes by the phrase itself.
            String key = "k" + Integer.toHexString(keyword.toLowerCase(Locale.ROOT).hashCode());
            while (!keys.add(key)) key += "x";
            Control remove = new Control("keyword-remove", text(p, "panel.keyword-remove"), Material.BARRIER, keyword, Kind.BUTTON, null, false, null);
            rows.add(new Row(key, keyword, "", Ui.item(Material.NAME_TAG), List.of(), List.of(remove)));
        }
        List<Field> fields = List.of(new Field(text(p, "panel.keyword-count"), profile.keywords().size() + "/" + plugin.service().settings().keywordLimit));
        List<Control> controls = List.of(
                new Control("keyword-add", text(p, "panel.keyword-add"), Material.NAME_TAG, "", Kind.FORM, text(p, "panel.keyword-phrase"), false, null),
                new Control("keyword-alerts", text(p, "panel.keyword-alerts"), Material.BELL, "", Kind.TOGGLE, profile.keywordAlerts(), false, null),
                new Control("keyword-test", text(p, "panel.keyword-test"), Material.SPYGLASS, "", Kind.FORM, text(p, "panel.keyword-sample"), false, null));
        return new Data(text(p, "menu.keywords-title"), text(p, "panel.keywords-desc"), Material.NAME_TAG, true, rows, fields, controls, List.of(back(p, "chat")), false);
    }

    private String status(Player p, ReportStore.Report r) {
        return text(p, "reports." + (r.open(System.currentTimeMillis()) ? "open" : r.closed() ? "closed" : "expired"));
    }

    private List<Field> reportFields(Player p, ReportStore.Report r) {
        return List.of(new Field(text(p, "panel.case-id"), r.id().toString()),
                new Field(text(p, "panel.reporter"), r.reporterName()), new Field(text(p, "panel.created"), Instant.ofEpochMilli(r.created()).toString()),
                new Field(text(p, "dialog.mute-reason"), r.reason()));
    }

    private Data reports(Player p) {
        List<Row> rows = plugin.reports().store().list(System.currentTimeMillis()).stream().map(r -> new Row(r.id().toString(), r.targetName(), status(p, r),
                Ui.item(Material.WRITABLE_BOOK), reportFields(p, r), List.of(button(p, "case", "panel.open-case", Material.BOOK, r.id().toString(), true)))).toList();
        return new Data(text(p, "reports.title"), text(p, "panel.select"), Material.WRITABLE_BOOK, true, rows, List.of(), List.of(), List.of(back(p, "moderation")), false);
    }

    private Data report(Player p, UUID id) {
        ReportStore.Report r = id == null ? null : plugin.reports().store().get(id);
        if (r == null) return empty(p, "report", "reports.result-missing");
        List<Field> fields = new ArrayList<>(reportFields(p, r));
        fields.add(new Field(text(p, "panel.state"), status(p, r)));
        MuteStore.Mute mute = plugin.mutes().active(r.target(), System.currentTimeMillis());
        fields.add(new Field(text(p, "reports.current-mute"), mute == null ? text(p, "reports.none") : mute.permanent() ? text(p, "permanent") : Durations.format(mute.remaining(System.currentTimeMillis()))));
        List<Row> rows = new ArrayList<>();
        for (int i = r.history().size() - 1; i >= 0; i--) {
            ReportStore.Entry e = r.history().get(i);
            rows.add(new Row("event-" + i, text(p, "reports.event-" + e.kind()), Instant.ofEpochMilli(e.at()).toString(), Ui.item(Material.BOOK),
                    List.of(new Field(text(p, "panel.by"), ChatMenu.name(e.actor())), new Field(text(p, "panel.note"), e.text())), List.of()));
        }
        List<Control> controls = new ArrayList<>();
        controls.add(button(p, "note", "reports.note", Material.FEATHER, id.toString(), true));
        controls.add(button(p, "status", r.closed() ? "reports.reopen" : "reports.resolve", Material.EMERALD, id.toString(), true));
        if (permission(p, "mute") && !off(Settings.Feature.MUTES)) {
            controls.add(button(p, "case-mute", "reports.mute", Material.LEAD, id.toString(), true));
            controls.add(new Control("case-unmute", text(p, "reports.unmute"), Material.SHEARS, id.toString(), Kind.BUTTON, null, true, text(p, "reports.action-confirm")));
        }
        return new Data(text(p, "reports.case", "player", r.targetName()), text(p, "panel.history"), Material.BOOK, true, rows, fields, controls, List.of(back(p, "reports")), false);
    }

    private Data showcase(Player p, UUID id) {
        Showcase.Stored stored = id == null ? null : plugin.showcase().stored(id);
        if (stored == null) return empty(p, "showcase", "showcase-expired");
        List<Row> rows = new ArrayList<>();
        for (int slot = 0; slot < stored.contents().length; slot++) {
            ItemStack item = stored.contents()[slot];
            // Showcase.take already replaces empty/air stacks with null in the immutable snapshot.
            if (item == null) continue;
            String label = text(p, slot >= 45 ? "panel.equipment-slot" : "panel.inventory-slot", "slot", slot >= 45 ? slot - 44 : slot + 1);
            var display = plugin.core().compat().platform().displayName(item);
            Ui.ItemIcon icon = Ui.item(item).tooltip(display);
            if (item.hasItemMeta()) {
                List<net.kyori.adventure.text.Component> lore = plugin.core().compat().platform().lore(item.getItemMeta());
                if (lore != null) for (var line : lore.stream().limit(7).toList()) icon.tooltip(line);
            }
            rows.add(new Row("slot-" + slot, label, "", icon,
                    List.of(new Field(text(p, "panel.item"), display), new Field(text(p, "panel.amount"), Integer.toString(item.getAmount()))), List.of()));
        }
        return new Data(text(p, "showcase-inventory-title", "player", stored.owner()), text(p, "panel.read-only"), Material.CHEST, true, rows, List.of(), List.of(),
                List.of(button(p, "inventory-view", "panel.inventory-view", Material.CHEST, id.toString(), true)), false);
    }

    private Data empty(Player p, String name, String key) {
        return new Data(text(p, titleKey(name)), text(p, key), Material.PAPER, false, List.of(), List.of(), List.of(), List.of(), false);
    }
    private Control button(Player p, String id, String key, Material icon, String target, boolean handover) {
        return new Control(id, text(p, key), icon, target, Kind.BUTTON, null, handover, null);
    }
    private Control back(Player p, String target) { return button(p, target, "menu.back", Material.ARROW, "", true); }
    private String text(Player p, String key, Object... placeholders) { return plugin.lang().plain(p, key, placeholders); }
    private static String titleKey(String name) {
        return switch (name) {
            case "ignored" -> "menu.ignored-title";
            case "moderation" -> "menu.moderation-title";
            case "mutes" -> "menu.mutes-title";
            case "reports", "report" -> "reports.title";
            case "showcase" -> "panel.inventory";
            case "keywords" -> "menu.keywords-title";
            default -> "menu.title";
        };
    }
    private static UUID uuid(String text) { try { return UUID.fromString(text); } catch (IllegalArgumentException | NullPointerException invalid) { return null; } }
    private static UUID caseId(String arg) { return uuid(arg == null ? null : arg.split(":", 2)[0]); }
    private static int casePage(String arg) { return arg != null && arg.contains(":") ? page(arg.substring(arg.indexOf(':') + 1)) : 0; }
    private static int page(String text) { try { return Math.max(0, Integer.parseInt(text)); } catch (NumberFormatException invalid) { return 0; } }
}
