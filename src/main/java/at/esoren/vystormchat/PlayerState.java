package at.esoren.vystormchat;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * What the chat needs to know about an online player, taken on the server thread (positions twice a second, the rest
 * every few seconds) so the async chat thread never touches live Bukkit state.
 *
 * @param regions Core region keys ({@code kind:id}) at the player's position, highest priority first
 * @param party   party id or null; {@code city}/{@code faction} likewise
 */
record PlayerState(UUID id, String name, Component displayName, UUID world, String worldName, double x, double y, double z,
                   List<String> regions, String party, String partyName, String city, String cityName, String faction,
                   String factionName, String group, String prefix, String suffix, long playTicks) {

    PlayerState {
        regions = regions == null ? List.of() : List.copyOf(regions);
        displayName = displayName == null ? Component.text(name) : displayName;
        group = group == null ? "" : group;
        prefix = prefix == null ? "" : prefix;
        suffix = suffix == null ? "" : suffix;
    }

    static PlayerState basic(UUID id, String name, UUID world, String worldName, double x, double y, double z) {
        return new PlayerState(id, name, null, world, worldName, x, y, z, List.of(), null, null, null, null, null, null, "", "", "", 0);
    }

    PlayerState withPosition(UUID world, String worldName, double x, double y, double z, List<String> regions, String party, String partyName) {
        return new PlayerState(id, name, displayName, world, worldName, x, y, z, regions, party, partyName, city, cityName, faction, factionName, group, prefix, suffix, playTicks);
    }

    PlayerState withMeta(Component displayName, String city, String cityName, String faction, String factionName, String group,
                         String prefix, String suffix, long playTicks) {
        return new PlayerState(id, name, displayName, world, worldName, x, y, z, regions, party, partyName, city, cityName, faction, factionName, group, prefix, suffix, playTicks);
    }

    /** Group id for a group scope (party, city, faction), else null. */
    String groupOf(Channel.Scope scope) {
        return switch (scope) {
            case PARTY -> party;
            case CITY -> city;
            case FACTION -> faction;
            default -> null;
        };
    }

    String groupName(Channel.Scope scope) {
        return switch (scope) {
            case PARTY -> partyName;
            case CITY -> cityName;
            case FACTION -> factionName;
            default -> null;
        };
    }

    /** The first region at the player's position whose kind is in {@code kinds} (any kind when empty), or null. */
    String region(java.util.Set<String> kinds) {
        for (String key : regions) {
            int colon = key.indexOf(':');
            if (kinds.isEmpty() || colon > 0 && kinds.contains(key.substring(0, colon))) return key;
        }
        return null;
    }
}
