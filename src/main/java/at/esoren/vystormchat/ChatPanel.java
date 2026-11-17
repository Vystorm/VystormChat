package at.esoren.vystormchat;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Shared, pure presentation for the chat screens. Services supply localized snapshots, never live inventories. */
final class ChatPanel {
    static final int PAGE_SIZE = 8;
    enum Kind { BUTTON, TOGGLE, NUMBER }
    record Control(String id, String label, Material icon, String target, Kind kind, Object value,
                   boolean handover, String confirmation) {}
    record Field(String label, Object value) {}
    record Row(String id, String title, String subtitle, Ui.Icon icon, List<Field> fields, List<Control> controls) {}
    record Data(String title, String subtitle, Material icon, boolean collection, List<Row> rows,
                List<Field> fields, List<Control> controls, List<Control> footer, boolean tabs) {}
    interface Actions {
        boolean allowed(Player player);
        Result run(Player player, String action, String target, Object value);
    }

    // One instance per screen; selection and page survive refreshes, but are removed on logout.
    private final Map<UUID, String> selected = new HashMap<>();
    private final Map<UUID, Integer> pages = new HashMap<>();
    private final Map<UUID, String> tabs = new HashMap<>();

    void forget(UUID player) {
        selected.remove(player);
        pages.remove(player);
        tabs.remove(player);
    }
    void page(UUID player, int page) { pages.put(player, Math.max(0, page)); }
    void select(UUID player, String key) { selected.put(player, key); }

    Panel render(PanelContext ctx, Data data, Actions actions) {
        UUID player = ctx.player().getUniqueId();
        int count = Math.max(1, (data.rows().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.clamp(pages.getOrDefault(player, 0), 0, count - 1);
        List<Row> visible = data.rows().subList(page * PAGE_SIZE, Math.min(data.rows().size(), (page + 1) * PAGE_SIZE));
        Row chosen = visible.stream().filter(r -> r.id().equals(selected.get(player))).findFirst()
                .orElse(visible.isEmpty() ? null : visible.getFirst());
        Ui.Box content = Ui.col().gap(8);
        if (!data.fields().isEmpty()) content.child(fields(data.fields()).id("summary"));
        if (data.collection()) {
            Ui.ListView list = Ui.list("entries").empty(ctx.text("panel.empty")).grow(2)
                    .items(visible, r -> Ui.listItem(r.id(), r.title()).subtitle(r.subtitle()).icon(r.icon()))
                    .selected(chosen == null ? null : chosen.id())
                    .onSelect((a, id) -> {
                        if (!actions.allowed(a.player())) return Result.error(ctx.text("no-permission"));
                        if (visible.stream().noneMatch(r -> r.id().equals(id))) return Result.error(ctx.text("panel.stale"));
                        select(a.player().getUniqueId(), id);
                        a.refresh();
                        return Result.ok();
                    });
            Ui.Box detail = Ui.col().gap(6).grow(3);
            if (chosen != null) {
                if (chosen.icon() instanceof Ui.ItemIcon icon) detail.child(icon.id("detail-item").size(32));
                detail.child(Ui.heading(chosen.title()).level(2).id("detail-title"));
                if (!chosen.subtitle().isBlank()) detail.child(Ui.badge(chosen.subtitle()).id("detail-state"));
                detail.child(fields(chosen.fields()).id("detail-fields"));
                for (Control control : chosen.controls()) detail.child(control(control, ctx, actions));
            }
            content.child(Ui.row().gap(8).children(list, detail));
            if (count > 1) content.child(Ui.text(ctx.text("panel.page", "page", page + 1, "pages", count)).id("page"));
        }
        Ui.Box controls = Ui.col().gap(6);
        for (Control control : data.controls()) controls.child(control(control, ctx, actions));
        Panel panel = Panel.page(data.title()).subtitle(data.subtitle()).icon(Ui.item(data.icon()))
                .size(data.collection() ? Panel.Size.LARGE : Panel.Size.MEDIUM);
        if (data.tabs()) {
            panel.child(Ui.tabs("chat-tabs").tab("channels", ctx.text("panel.channels"), content)
                    .tab("personal", ctx.text("menu.settings"), controls)
                    .select(tabs.getOrDefault(player, "channels"))
                    .onTab((a, id) -> {
                        if (!actions.allowed(a.player())) return Result.error(ctx.text("no-permission"));
                        if (!List.of("channels", "personal").contains(id)) return Result.error(ctx.text("panel.stale"));
                        tabs.put(a.player().getUniqueId(), id);
                        a.refresh();
                        return Result.ok();
                    }));
        } else panel.children(content, controls);
        List<Ui.Button> footer = new ArrayList<>();
        if (count > 1) {
            footer.add(pageButton(ctx, actions, "previous", page - 1, page == 0));
            footer.add(pageButton(ctx, actions, "next", page + 1, page == count - 1));
        }
        for (Control control : data.footer()) footer.add(button(control, ctx, actions).ghost());
        return panel.footer(footer.toArray(Ui.Button[]::new));
    }

    private Ui.Button pageButton(PanelContext ctx, Actions actions, String id, int page, boolean disabled) {
        return Ui.button(id).label(ctx.text("reports." + id)).icon(Ui.item(Material.ARROW)).ghost().disabled(disabled)
                .onClick(a -> {
                    if (!actions.allowed(a.player())) return Result.error(ctx.text("no-permission"));
                    pages.put(a.player().getUniqueId(), Math.max(0, page));
                    selected.remove(a.player().getUniqueId());
                    a.refresh();
                    return Result.ok();
                });
    }

    private static Ui.Kv fields(List<Field> fields) {
        Ui.Kv kv = Ui.kv();
        for (Field field : fields) kv.row(field.label(), field.value());
        return kv;
    }

    private static Ui.Element<?> control(Control c, PanelContext ctx, Actions actions) {
        return switch (c.kind()) {
            case BUTTON -> button(c, ctx, actions);
            case TOGGLE -> Ui.toggle(c.id(), c.label()).value(Boolean.TRUE.equals(c.value()))
                    .onChange((a, value) -> run(a, c, value, ctx, actions));
            case NUMBER -> Ui.number(c.id(), c.label()).range(0, 3600, 1).decimals(0).value(((Number) c.value()).doubleValue())
                    .onChange((a, value) -> run(a, c, value, ctx, actions));
        };
    }

    private static Ui.Button button(Control c, PanelContext ctx, Actions actions) {
        Ui.Button button = Ui.button(c.id()).label(c.label()).icon(Ui.item(c.icon())).primary()
                .onClick(a -> run(a, c, null, ctx, actions));
        if (c.confirmation() != null) button.danger().confirm(c.label(), c.confirmation());
        return button;
    }

    private static Result run(PanelAction a, Control c, Object value, PanelContext ctx, Actions actions) {
        if (!actions.allowed(a.player())) return Result.error(ctx.text("no-permission"));
        if (c.handover()) {
            // The dispatcher checks permission and current state again after the delayed handover.
            a.closeAndThen(p -> {
                Result result = actions.run(p, c.id(), c.target(), value);
                if (!result.success()) Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(p, result.message());
            });
            return Result.ok();
        }
        Result result = actions.run(a.player(), c.id(), c.target(), value);
        a.refresh();
        return result;
    }
}
