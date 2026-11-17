package at.esoren.vystormchat;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.InventoryCompatInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * {@code [item]}, {@code [inv]} and {@code [pos]} in chat. The item and inventory are copied on the server thread at
 * the moment the message is sent; {@code [inv]} becomes a button that opens a read-only copy for 10 minutes.
 */
final class Showcase {
    /** What a message shows, taken on the server thread. Null fields were not asked for or not allowed. */
    record Snapshot(ItemStack item, UUID inventory, String owner, Location position) {
        static final Snapshot NONE = new Snapshot(null, null, null, null);
    }

    record Stored(String owner, ItemStack[] contents, long created) {}

    private static final long KEEP_MILLIS = 10 * 60_000L;
    private final Map<UUID, Stored> inventories = new ConcurrentHashMap<>();

    /** Copies what the message needs. Server thread. */
    Snapshot take(Player player, boolean item, boolean inventory, boolean position) {
        ItemStack hand = null;
        if (item) {
            ItemStack held = player.getInventory().getItemInMainHand();
            hand = held.getType().isAir() ? new ItemStack(Material.AIR) : held.clone();
        }
        UUID stored = null;
        if (inventory) {
            PlayerInventory source = player.getInventory();
            ItemStack[] copy = new ItemStack[54];
            ItemStack[] storage = source.getStorageContents();
            // rows 1-3 main inventory, row 4 hotbar, row 6 armour + off hand
            for (int i = 9; i < Math.min(36, storage.length); i++) copy[i - 9] = clone(storage[i]);
            for (int i = 0; i < 9 && i < storage.length; i++) copy[27 + i] = clone(storage[i]);
            ItemStack[] armor = source.getArmorContents();
            for (int i = 0; i < armor.length && i < 4; i++) copy[45 + (3 - i)] = clone(armor[i]);
            copy[50] = clone(source.getItemInOffHand());
            stored = UUID.randomUUID();
            inventories.put(stored, new Stored(player.getName(), copy, System.currentTimeMillis()));
        }
        return new Snapshot(hand, stored, player.getName(), position ? player.getLocation().clone() : null);
    }

    private static ItemStack clone(ItemStack item) { return item == null || item.getType().isAir() ? null : item.clone(); }

    /** The chat part for the item in hand: "[3x Diamond Sword]" with the item tooltip. */
    static Component item(PlatformCompatInterface platform, Lang lang, String language, ItemStack item) {
        if (item == null || item.getType().isAir()) return lang.component(language, "showcase-empty-hand");
        Component name = platform.displayName(item);
        return item.getAmount() > 1 ? Component.text(item.getAmount() + "x ", NamedTextColor.AQUA).append(name) : name;
    }

    /** The "[Inventory of Name]" button. */
    static Component inventoryButton(Lang lang, String language, UUID id, String owner, ChatPlugin plugin) {
        return lang.component(language, "showcase-inventory", "player", owner)
                .hoverEvent(HoverEvent.showText(lang.component(language, "showcase-inventory-hover", "player", owner)))
                .clickEvent(plugin.core().compat().clicks().callback(plugin, viewer -> {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.showcase().open(viewer, id));
                }, -1, java.time.Duration.ofMillis(KEEP_MILLIS)));
    }

    /** Opens the stored inventory copy; tells the player when it is gone. Server thread. */
    void open(Player viewer, UUID id) {
        if (!ChatPlugin.instance().openPanel(viewer, "showcase", id.toString())) openChest(viewer, id);
    }

    Stored stored(UUID id) {
        Stored stored = inventories.get(id);
        return stored == null || System.currentTimeMillis() - stored.created() > KEEP_MILLIS ? null : stored;
    }

    void openChest(Player viewer, UUID id) {
        ChatPlugin plugin = ChatPlugin.instance();
        Stored stored = inventories.get(id);
        if (stored == null || System.currentTimeMillis() - stored.created() > KEEP_MILLIS) {
            plugin.lang().send(viewer, "showcase-expired");
            return;
        }
        var platform = plugin.core().compat().platform();
        View view = new View();
        view.inventory = platform.createInventory(view, 54, plugin.lang().component(viewer, "showcase-inventory-title", "player", stored.owner()));
        for (int i = 0; i < 54; i++) if (stored.contents()[i] != null) view.inventory.setItem(i, stored.contents()[i].clone());
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        platform.editMeta(pane, meta -> platform.displayName(meta, Component.empty()));
        for (int i = 36; i < 45; i++) view.inventory.setItem(i, pane);
        viewer.openInventory(view.inventory);
    }

    /** Drops copies older than 10 minutes. */
    void cleanup() {
        long now = System.currentTimeMillis();
        for (Iterator<Stored> it = inventories.values().iterator(); it.hasNext(); ) if (now - it.next().created() > KEEP_MILLIS) it.remove();
    }

    void clear() { inventories.clear(); }

    /** Read-only inventory copy. */
    static final class View implements InventoryHolder {
        private Inventory inventory;

        @Override public Inventory getInventory() { return inventory; }
    }

    static final class Clicks implements Listener {
        private final InventoryCompatInterface inventories;

        Clicks(InventoryCompatInterface inventories) { this.inventories = inventories; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (inventories.top(event.getView()).getHolder() instanceof View) event.setCancelled(true);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (inventories.top(event.getView()).getHolder() instanceof View) event.setCancelled(true);
        }
    }
}
