package at.esoren.vystormchat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

/**
 * Snapshots of every online player for the async chat thread. Positions, regions and parties are refreshed twice a
 * second, ranks, cities, factions and play time every few seconds, all on the server thread.
 */
final class States {
    private final Hooks hooks;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Map<String, UUID> names = new ConcurrentHashMap<>();
    private volatile boolean regions = true;

    private final Vystorm.vystorm_core.API core;

    States(Hooks hooks, Vystorm.vystorm_core.API core) { this.hooks = hooks; this.core = core; }

    void regions(boolean on) { regions = on; }

    PlayerState get(UUID id) { return states.get(id); }

    Collection<PlayerState> all() { return states.values(); }

    /** Lower-case name → id of everyone online (for mentions and tab completion). */
    Map<String, UUID> names() { return names; }

    /** Full snapshot right away (join, enable). Server thread. */
    PlayerState join(Player player) {
        names.put(player.getName().toLowerCase(Locale.ROOT), player.getUniqueId());
        Location at = player.getLocation();
        PlayerState state = PlayerState.basic(player.getUniqueId(), player.getName(), at.getWorld().getUID(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
        state = position(player, state);
        Hooks.Group city = hooks.cities(List.of(player)).get(player.getUniqueId());
        state = meta(player, state, city);
        states.put(player.getUniqueId(), state);
        return state;
    }

    void quit(Player player) {
        states.remove(player.getUniqueId());
        names.remove(player.getName().toLowerCase(Locale.ROOT), player.getUniqueId());
    }

    /** Twice a second: position, regions, party. */
    void fast(Collection<? extends Player> online) {
        for (Player player : online) {
            PlayerState state = states.get(player.getUniqueId());
            if (state == null) { join(player); continue; }
            states.put(player.getUniqueId(), position(player, state));
        }
    }

    /** Every few seconds: rank, prefix, suffix, display name, city, faction, play time. */
    void slow(Collection<? extends Player> online) {
        Map<UUID, Hooks.Group> cities = hooks.cities(online);
        for (Player player : online) {
            PlayerState state = states.get(player.getUniqueId());
            if (state == null) { join(player); continue; }
            states.put(player.getUniqueId(), meta(player, state, cities.get(player.getUniqueId())));
        }
    }

    private PlayerState position(Player player, PlayerState state) {
        Location at = player.getLocation();
        List<String> keys = List.of();
        if (regions) {
            try {
                List<String> found = new ArrayList<>();
                for (Vystorm.vystorm_core.region.Region region : core.area().regions().at(at)) found.add(region.key());
                keys = found;
            } catch (RuntimeException unavailable) {
                keys = List.of();
            }
        }
        Hooks.Group party = hooks.party(player.getUniqueId());
        return state.withPosition(at.getWorld().getUID(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), keys,
                party == null ? null : party.id(), party == null ? null : party.name());
    }

    private PlayerState meta(Player player, PlayerState state, Hooks.Group city) {
        String[] meta = hooks.meta(player);
        Hooks.Group faction = hooks.faction(player.getUniqueId());
        long ticks;
        try { ticks = player.getStatistic(Statistic.PLAY_ONE_MINUTE); } catch (RuntimeException unsupported) { ticks = 0; }
        return state.withMeta(core.compat().platform().displayName(player), city == null ? null : city.id(), city == null ? null : city.name(),
                faction == null ? null : faction.id(), faction == null ? null : faction.name(), meta[0], meta[1], meta[2], ticks);
    }
}
