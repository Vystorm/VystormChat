package at.esoren.vystormchat;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Soft hooks into other plugins. Every hook is optional, looked up by name at runtime and guarded, so a missing or
 * changed plugin only switches that feature off (with one log line) and never breaks the chat.
 */
final class Hooks {
    private final Plugin plugin;
    private CityHook city;
    private FactionHook faction;
    private VaultMeta meta;
    private Money money;
    private Method papi;
    private boolean party;

    private final Vystorm.vystorm_core.API core;

    Hooks(Plugin plugin, Vystorm.vystorm_core.API core) { this.plugin = plugin; this.core = core; }

    /** Looks up every hook (server thread; again after a reload, so plugins enabled later are found). */
    void refresh() {
        city = CityHook.find(plugin, core.area());
        faction = FactionHook.find(plugin);
        try { meta = VaultMeta.find(plugin); } catch (LinkageError noVault) { meta = null; }
        try { money = Money.find(plugin); } catch (LinkageError noVault) { money = null; }
        papi = null;
        if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true,
                        plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI").getClass().getClassLoader());
                papi = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException missing) {
                plugin.getLogger().log(Level.INFO, "PlaceholderAPI found but its API could not be used; %placeholders% stay as written", missing);
            }
        }
        party = core.parties().available();
    }

    String summary() {
        StringBuilder out = new StringBuilder();
        if (party) out.append("parties ");
        if (city != null) out.append("cities ");
        if (faction != null) out.append("factions ");
        if (meta != null) out.append("vault-chat ");
        if (money != null) out.append("vault-economy ");
        if (papi != null) out.append("placeholderapi ");
        return out.isEmpty() ? "none" : out.toString().trim();
    }

    Money money() { return money; }

    boolean papi() { return papi != null; }

    /** PlaceholderAPI: one {@code %token%} for the player; the token itself when unavailable. Any thread. */
    String placeholder(Player player, String token) {
        Method method = papi;
        if (method == null) return token;
        try {
            Object result = method.invoke(null, player, token);
            return result == null ? token : result.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
            return token;
        }
    }

    record Group(String id, String name) {}

    /** The player's party (Core party register, thread-safe), or null. */
    Group party(UUID player) {
        try {
            return core.parties().partyOf(player)
                    .map(view -> new Group(view.id().toString(), view.name() == null ? "" : view.name())).orElse(null);
        } catch (RuntimeException failed) {
            return null;
        }
    }

    /** City memberships of all online players (server thread). */
    Map<UUID, Group> cities(Iterable<? extends Player> players) {
        CityHook hook = city;
        return hook == null ? Map.of() : hook.memberships(players);
    }

    Group faction(UUID player) {
        FactionHook hook = faction;
        return hook == null ? null : hook.membership(player);
    }

    /** [group, prefix, suffix] from Vault (server thread), or empty strings. */
    String[] meta(Player player) {
        VaultMeta hook = meta;
        if (hook == null) return new String[] {"", "", ""};
        try { return hook.meta(player); } catch (RuntimeException | LinkageError failed) { return new String[] {"", "", ""}; }
    }

    // ------------------------------------------------------------------ VystormCityClaim

    /**
     * Cities through VystormCityClaim's {@code VystormCityApi} service (found by class name, called by reflection):
     * the city list comes from Core's region register (kind {@code city}), membership from {@code role(player, city)}.
     */
    static final class CityHook {
        private final Object api;
        private final Method role;
        private final Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface area;

        private CityHook(Object api, Method role, Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface area) { this.api = api; this.role = role; this.area = area; }

        static CityHook find(Plugin plugin, Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface area) {
            if (!plugin.getServer().getPluginManager().isPluginEnabled("VystormCityClaim")) return null;
            try {
                for (Class<?> type : plugin.getServer().getServicesManager().getKnownServices()) {
                    if (!type.getName().equals("at.esoren.stadtclaim.VystormCityApi")) continue;
                    RegisteredServiceProvider<?> registration = plugin.getServer().getServicesManager().getRegistration(type);
                    if (registration == null) return null;
                    return new CityHook(registration.getProvider(), type.getMethod("role", UUID.class, UUID.class), area);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
                plugin.getLogger().log(Level.INFO, "VystormCityClaim found but its API could not be used; the city channel stays empty", failed);
            }
            return null;
        }

        Map<UUID, Group> memberships(Iterable<? extends Player> players) {
            Map<UUID, String> cities = new LinkedHashMap<>();
            try {
                for (Vystorm.vystorm_core.region.Region region : area.regions().all("city")) {
                    String id = region.id();
                    int at = id.indexOf('@');
                    if (at > 0) id = id.substring(0, at);
                    cities.putIfAbsent(UUID.fromString(id), region.attribute("name").orElse(""));
                }
            } catch (RuntimeException failed) {
                return Map.of();
            }
            Map<UUID, Group> result = new HashMap<>();
            for (Player player : players) {
                for (Map.Entry<UUID, String> city : cities.entrySet()) {
                    try {
                        Object role = this.role.invoke(api, player.getUniqueId(), city.getKey());
                        String name = String.valueOf(role);
                        if (name.equals("OWNER") || name.equals("ASSISTANT") || name.equals("RESIDENT")) {
                            result.put(player.getUniqueId(), new Group(city.getKey().toString(), city.getValue()));
                            break;
                        }
                    } catch (ReflectiveOperationException | RuntimeException failed) {
                        return result;
                    }
                }
            }
            return result;
        }
    }

    // ------------------------------------------------------------------ Vystorm-Factions

    /** Faction membership through Vystorm-Factions' {@code FactionsApi} service (by class name, reflection). */
    static final class FactionHook {
        private final Object api;
        private final Method membership, faction;

        private FactionHook(Object api, Method membership, Method faction) { this.api = api; this.membership = membership; this.faction = faction; }

        static FactionHook find(Plugin plugin) {
            if (!plugin.getServer().getPluginManager().isPluginEnabled("Vystorm-Factions")) return null;
            try {
                for (Class<?> type : plugin.getServer().getServicesManager().getKnownServices()) {
                    if (!type.getName().equals("at.esoren.factions.api.FactionsApi")) continue;
                    RegisteredServiceProvider<?> registration = plugin.getServer().getServicesManager().getRegistration(type);
                    if (registration == null) return null;
                    return new FactionHook(registration.getProvider(), type.getMethod("membership", UUID.class), type.getMethod("faction", String.class));
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
                plugin.getLogger().log(Level.INFO, "Vystorm-Factions found but its API could not be used; the faction channel stays empty", failed);
            }
            return null;
        }

        Group membership(UUID player) {
            try {
                Object result = membership.invoke(api, player);
                if (!(result instanceof Optional<?> optional) || optional.isEmpty()) return null;
                String id = String.valueOf(optional.get());
                String name = id;
                Object view = faction.invoke(api, id);
                if (view instanceof Optional<?> found && found.isPresent()) {
                    Object value = found.get().getClass().getMethod("name").invoke(found.get());
                    if (value != null) name = value.toString();
                }
                return new Group(id, name);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ Vault

    /** Vault chat meta (prefix, suffix, primary group); the Vault types live only in here. */
    static final class VaultMeta {
        private final net.milkbowl.vault.chat.Chat chat;

        private VaultMeta(net.milkbowl.vault.chat.Chat chat) { this.chat = chat; }

        static VaultMeta find(Plugin plugin) {
            var manager = plugin.getServer().getPluginManager();
            if (manager.getPlugin("Vault") == null && manager.getPlugin("VaultUnlocked") == null) return null;
            try {
                RegisteredServiceProvider<net.milkbowl.vault.chat.Chat> registration =
                        plugin.getServer().getServicesManager().getRegistration(net.milkbowl.vault.chat.Chat.class);
                return registration == null ? null : new VaultMeta(registration.getProvider());
            } catch (LinkageError | RuntimeException missing) {
                return null;
            }
        }

        String[] meta(Player player) {
            String group = "";
            try { group = chat.getPrimaryGroup(player); } catch (RuntimeException | LinkageError unsupported) { /* no groups */ }
            return new String[] {group == null ? "" : group, nonNull(chat.getPlayerPrefix(player)), nonNull(chat.getPlayerSuffix(player))};
        }

        private static String nonNull(String text) { return text == null ? "" : text; }
    }
}
