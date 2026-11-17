package at.esoren.vystormchat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Reply partners and optional session locks; updated only after successful delivery. */
final class ReplyTargets {
    private final Map<UUID, UUID> partners = new ConcurrentHashMap<>();
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();

    UUID partner(UUID player) { return partners.get(player); }

    boolean lock(UUID player) { return partners.containsKey(player) && locked.add(player); }

    boolean unlock(UUID player) { return locked.remove(player); }

    void delivered(UUID from, UUID to) {
        if (!locked.contains(from)) partners.put(from, to);
        if (!locked.contains(to)) partners.put(to, from);
    }

    void forget(UUID player) {
        locked.remove(player);
        partners.remove(player);
    }
}
