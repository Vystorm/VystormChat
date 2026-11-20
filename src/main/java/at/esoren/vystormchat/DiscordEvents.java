package at.esoren.vystormchat;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * The Bukkit side of the Discord bridge: the {@link DiscordBridge.Host} of the running plugin and the server events
 * (join, leave, first join, death, advancement). Deaths go through Core's event stages (OBSERVE); join, quit and
 * advancements are plain MONITOR listeners that only read. Vanished players (metadata "vanished") are not announced.
 */
final class DiscordEvents implements Listener, DiscordBridge.Host {
    private static final Pattern KEY = Pattern.compile("^[a-z0-9_]+(?:\\.[a-z0-9_]+)+$");
    private final ChatPlugin plugin;

    DiscordEvents(ChatPlugin plugin) { this.plugin = plugin; }

    void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.core().priority().stages().handle(plugin, PlayerDeathEvent.class, EventStage.OBSERVE, this::death);
    }

    private DiscordBridge bridge() { return plugin.discord(); }

    private static String name(Player player) { return DiscordText.escapeMarkdown(player.getName()); }

    private static boolean vanished(Player player) {
        for (MetadataValue value : player.getMetadata("vanished")) if (value.asBoolean()) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        DiscordBridge bridge = bridge();
        Player player = event.getPlayer();
        if (bridge == null || vanished(player)) return;
        Settings s = plugin.service().settings();
        if (!player.hasPlayedBefore() && s.active(Settings.Feature.DISCORD_FIRST_JOIN))
            bridge.event(Settings.Feature.DISCORD_FIRST_JOIN, "discord.events.first-join", "player", name(player));
        else bridge.event(Settings.Feature.DISCORD_JOIN, "discord.events.join", "player", name(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        DiscordBridge bridge = bridge();
        if (bridge == null || vanished(event.getPlayer())) return;
        bridge.event(Settings.Feature.DISCORD_LEAVE, "discord.events.leave", "player", name(event.getPlayer()));
    }

    private void death(PlayerDeathEvent event) {
        DiscordBridge bridge = bridge();
        if (bridge == null || !plugin.service().settings().active(Settings.Feature.DISCORD_DEATH)) return;
        Player player = event.getEntity();
        if (vanished(player)) return;
        String text;
        try {
            Component message = event.deathMessage();
            if (message == null) return; // another plugin hid it
            text = PlainTextComponentSerializer.plainText().serialize(message);
        } catch (NoSuchMethodError spigot) {
            @SuppressWarnings("deprecation") String legacy = event.getDeathMessage();
            if (legacy == null) return;
            text = legacy.replaceAll("§.", "");
        }
        if (text.isBlank() || KEY.matcher(text.strip()).matches()) text = plugin.lang().plain(Bukkit.getConsoleSender(), "discord.events.death-plain", "player", player.getName());
        bridge.event(Settings.Feature.DISCORD_DEATH, "discord.events.death", "player", name(player), "message", DiscordText.escapeMarkdown(text));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void advancement(PlayerAdvancementDoneEvent event) {
        DiscordBridge bridge = bridge();
        if (bridge == null || !plugin.service().settings().active(Settings.Feature.DISCORD_ADVANCEMENT) || vanished(event.getPlayer())) return;
        String title = advancementTitle(event.getAdvancement());
        if (title == null) return;
        bridge.event(Settings.Feature.DISCORD_ADVANCEMENT, "discord.events.advancement", "player", name(event.getPlayer()), "advancement", DiscordText.escapeMarkdown(title));
    }

    /**
     * The title of an advancement that is announced in chat, or null (recipes, hidden ones). Through reflection,
     * because Paper ({@code title()}, {@code doesAnnounceToChat()}) and Spigot ({@code getTitle()},
     * {@code shouldAnnounceChat()}) name these differently and declare {@code getDisplay()} with different types.
     */
    static String advancementTitle(Object advancement) {
        try {
            Object display = call(advancement, "getDisplay");
            if (display == null) return null;
            Object announce = call(display, "doesAnnounceToChat", "shouldAnnounceChat");
            if (Boolean.FALSE.equals(announce)) return null;
            Object title = call(display, "title", "getTitle");
            String text = title instanceof Component component ? PlainTextComponentSerializer.plainText().serialize(component) : title == null ? "" : title.toString();
            if (text.isBlank() || KEY.matcher(text.strip()).matches()) {
                Object key = call(advancement, "getKey");
                String path = key instanceof org.bukkit.NamespacedKey namespaced ? namespaced.getKey() : "";
                String last = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
                return last.isEmpty() ? null : Character.toUpperCase(last.charAt(0)) + last.substring(1);
            }
            return text.replaceAll("§.", "");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unknown) {
            return null;
        }
    }

    private static Object call(Object target, String... names) throws ReflectiveOperationException {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException next) { /* the other platform's name */ }
        }
        return null;
    }

    // ------------------------------------------------------------------ DiscordBridge.Host

    @Override public void async(Runnable task) { plugin.tasks().async(task); }

    @Override public void main(Runnable task) { plugin.tasks().now(task); }

    @Override public Logger log() { return plugin.getLogger(); }

    @Override public String text(String key, Object... placeholders) { return plugin.lang().plain(Bukkit.getConsoleSender(), key, placeholders); }

    @Override public boolean publicPermission(String permission) {
        Permission registered = Bukkit.getPluginManager().getPermission(permission);
        return registered != null && registered.getDefault() == PermissionDefault.TRUE;
    }

    @Override public Path dataFolder() { return plugin.getDataFolder().toPath(); }

    @Override public String environment(String name) { return System.getenv(name); }

    @Override public boolean pluginPresent(String name) { return Bukkit.getPluginManager().getPlugin(name) != null; }

    @Override public int onlinePlayers() { return Bukkit.getOnlinePlayers().size(); }

    @Override public int maxPlayers() { return Bukkit.getMaxPlayers(); }

    /** Who reads a Discord line: the channel's read permission, its worlds and the player's own channel choice. */
    static boolean receives(Settings s, Channel channel, java.util.function.Predicate<String> permission, String world, Profile profile) {
        return channel.readable(permission) && channel.inWorld(world) && ChatService.listens(s, profile, channel);
    }

    @Override public void show(Channel channel, Component line, String logLine) {
        Settings s = plugin.service().settings();
        Component tagged = plugin.service().clientTab(channel, line);
        var platform = plugin.core().compat().platform();
        for (Player player : Bukkit.getOnlinePlayers())
            if (receives(s, channel, player::hasPermission, player.getWorld().getName(), plugin.profiles().cached(player.getUniqueId())))
                platform.sendMessage(player, tagged);
        if (s.consoleLog) platform.sendMessage(Bukkit.getConsoleSender(), line);
        plugin.chatLog().log(channel.id(), logLine);
    }
}
