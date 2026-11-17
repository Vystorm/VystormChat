package at.esoren.vystormchat;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.InventoryCompatInterface;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** A chest menu whose slots run actions; items can never be taken out. Actions run one tick after the click. */
final class Menu implements InventoryHolder {
    private final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();
    private Inventory inventory;

    @Override
    public Inventory getInventory() { return inventory; }

    static Menu create(Component title, int rows) {
        Menu menu = new Menu();
        menu.inventory = Vystorm.vystorm_core.compat.PlatformCompat.createInventory(menu, Math.clamp(rows, 1, 6) * 9, title);
        return menu;
    }

    void set(int slot, ItemStack item, BiConsumer<Player, ClickType> action) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
    }

    void open(Player player) { player.openInventory(inventory); }

    static ItemStack item(Lang lang, Player viewer, Material material, String nameKey, String loreKey, Object... placeholders) {
        ItemStack item = new ItemStack(material == null || !material.isItem() ? Material.PAPER : material);
        Vystorm.vystorm_core.compat.PlatformCompat.editMeta(item, meta -> {
            Vystorm.vystorm_core.compat.PlatformCompat.displayName(meta, plain(lang.component(viewer, nameKey, placeholders)));
            if (loreKey != null) Vystorm.vystorm_core.compat.PlatformCompat.lore(meta, lines(lang.lines(viewer, loreKey, placeholders)));
        });
        return item;
    }

    static Component plain(Component line) { return line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE); }

    static List<Component> lines(List<Component> lines) { return lines.stream().map(Menu::plain).toList(); }

    static final class Clicks implements Listener {
        private final Plugin plugin;
        private final InventoryCompatInterface inventories;

        Clicks(Plugin plugin, InventoryCompatInterface inventories) { this.plugin = plugin; this.inventories = inventories; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(inventories.top(event.getView()).getHolder() instanceof Menu menu)) return;
            event.setCancelled(true);
            if (event.getClickedInventory() != inventories.top(event.getView()) || !(event.getWhoClicked() instanceof Player player)) return;
            BiConsumer<Player, ClickType> action = menu.actions.get(event.getSlot());
            ClickType click = event.getClick();
            if (action != null) Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) action.accept(player, click); });
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (inventories.top(event.getView()).getHolder() instanceof Menu) event.setCancelled(true);
        }
    }
}
