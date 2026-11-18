package at.esoren.vystormchat;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Speech bubbles: public chat messages float above the sender's head for a few seconds as a text display riding on
 * the player. Only recipients of the message within {@code bubbles.radius} who did not switch bubbles off see it; the
 * display is never saved with the world. One bubble per player, a new message replaces it. Server thread.
 */
final class SpeechBubbles implements Listener {
    static final String PREF = "bubbles";
    private final ChatPlugin plugin;
    private final Map<UUID, UUID> bubbles = new HashMap<>();

    SpeechBubbles(ChatPlugin plugin) { this.plugin = plugin; }

    boolean enabled() { return plugin.getConfig().getBoolean("bubbles.enabled", true) && !plugin.off(Settings.Feature.CHAT); }

    boolean wants(Player player) {
        try { return plugin.core().settings().prefs().bool(plugin, player, PREF, true); }
        catch (RuntimeException none) { return true; }
    }

    void setWants(Player player, boolean on) {
        try { plugin.core().settings().prefs().setBool(plugin, player, PREF, on); }
        catch (RuntimeException none) { }
    }

    /** Called after a channel message was delivered (any thread). */
    void after(Delivery delivery) {
        if (!enabled()) return;
        List<String> channels = plugin.getConfig().getStringList("bubbles.channels");
        if (!(channels.isEmpty() ? List.of("global", "local", "world") : channels).contains(delivery.channel.id())) return;
        String text = delivery.plain;
        Set<UUID> reach = new HashSet<>(delivery.reach.keySet());
        Player sender = delivery.sender;
        Bukkit.getScheduler().runTask(plugin, () -> show(sender, text, reach));
    }

    private void show(Player sender, String text, Set<UUID> reach) {
        if (sender == null || !sender.isOnline() || sender.isDead() || text == null || text.isBlank()) return;
        remove(sender.getUniqueId());
        int seconds = Math.clamp(plugin.getConfig().getInt("bubbles.seconds", 5), 1, 30);
        double radius = Math.clamp(plugin.getConfig().getDouble("bubbles.radius", 24), 4, 128);
        String body = String.join("\n", wrap(text, Math.clamp(plugin.getConfig().getInt("bubbles.width", 24), 10, 60), 3));
        TextDisplay display;
        try {
            display = sender.getWorld().spawn(sender.getLocation().add(0, sender.getHeight() + 0.4, 0), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setVisibleByDefault(false);
                d.setBillboard(Display.Billboard.CENTER);
                plugin.core().compat().platform().text(d, Component.text(body, NamedTextColor.WHITE));
                d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                d.setShadowed(true);
            });
        } catch (RuntimeException | LinkageError unsupported) { return; }
        sender.addPassenger(display);
        double r2 = radius * radius;
        for (UUID id : reach) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null || viewer.equals(sender) || !viewer.getWorld().equals(sender.getWorld())) continue;
            if (viewer.getLocation().distanceSquared(sender.getLocation()) > r2 || !wants(viewer)) continue;
            viewer.showEntity(plugin, display);
        }
        bubbles.put(sender.getUniqueId(), display.getUniqueId());
        UUID shown = display.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (shown.equals(bubbles.get(sender.getUniqueId()))) remove(sender.getUniqueId());
        }, seconds * 20L);
    }

    private void remove(UUID player) {
        UUID id = bubbles.remove(player);
        Entity entity = id == null ? null : Bukkit.getEntity(id);
        if (entity != null) entity.remove();
    }

    void removeAll() { for (UUID player : List.copyOf(bubbles.keySet())) remove(player); }

    @EventHandler
    public void quit(PlayerQuitEvent event) { remove(event.getPlayer().getUniqueId()); }

    /**
     * Splits a message into at most {@code lines} lines of about {@code width} characters at spaces (long words are
     * cut); "…" marks cut text.
     */
    static List<String> wrap(String text, int width, int lines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String[] words = text.strip().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            while (word.length() > width) {
                if (!line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
                out.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (line.length() + word.length() + (line.isEmpty() ? 0 : 1) > width) { out.add(line.toString()); line.setLength(0); }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        out.removeIf(String::isEmpty);
        if (out.size() > lines) {
            List<String> cut = new ArrayList<>(out.subList(0, lines));
            String last = cut.getLast();
            cut.set(lines - 1, (last.length() >= width ? last.substring(0, width - 1) : last) + "…");
            return cut;
        }
        return out;
    }
}
