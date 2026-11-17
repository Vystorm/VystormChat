package at.esoren.vystormchat;

import java.util.function.Predicate;

/** The rules for who may write into a channel and who sees a message. Pure, so tests cover every case. */
final class Visibility {
    private Visibility() {}

    enum Reach { NONE, NORMAL, SPY }

    /** Why a player may not write into a channel, or null when they may. Values are lang keys. */
    static String writeProblem(Channel channel, PlayerState sender, Predicate<String> permission) {
        if (!channel.writable(permission)) return "channel-no-write";
        if (!channel.inWorld(sender.worldName())) return "channel-wrong-world";
        if (channel.scope().group() && channel.forwardCommand().isEmpty() && sender.groupOf(channel.scope()) == null)
            return "channel-no-" + channel.scope().name().toLowerCase(java.util.Locale.ROOT);
        if (channel.scope() == Channel.Scope.REGION && sender.region(channel.regionKinds()) == null) return "channel-no-region";
        return null;
    }

    /**
     * Whether {@code viewer} receives a message {@code sender} wrote into {@code channel}.
     *
     * @param permission  the viewer's permissions
     * @param listening   the viewer has not left (hidden) the channel
     * @param ignoring    the viewer ignores the sender (and the sender is not exempt)
     * @param spying      the viewer has social spy switched on and the spy permission
     */
    static Reach reach(Channel channel, PlayerState sender, PlayerState viewer, Predicate<String> permission,
                       boolean listening, boolean ignoring, boolean spying) {
        if (viewer.id().equals(sender.id())) return Reach.NORMAL;
        if (ignoring) return Reach.NONE;
        boolean readable = channel.readable(permission);
        if (readable && listening && inScope(channel, sender, viewer)) return Reach.NORMAL;
        return spying && channel.spy() ? Reach.SPY : Reach.NONE;
    }

    /** The scope rule alone (world list, radius, region, group). */
    static boolean inScope(Channel channel, PlayerState sender, PlayerState viewer) {
        if (!channel.inWorld(viewer.worldName())) return false;
        return switch (channel.scope()) {
            case GLOBAL -> true;
            case WORLD -> sender.world() != null && sender.world().equals(viewer.world());
            case LOCAL -> {
                if (sender.world() == null || !sender.world().equals(viewer.world())) yield false;
                double dx = sender.x() - viewer.x(), dy = sender.y() - viewer.y(), dz = sender.z() - viewer.z();
                yield dx * dx + dy * dy + dz * dz <= (double) channel.radius() * channel.radius();
            }
            case REGION -> {
                String region = sender.region(channel.regionKinds());
                yield region != null && viewer.regions().contains(region);
            }
            case PARTY, CITY, FACTION -> {
                String group = sender.groupOf(channel.scope());
                yield group != null && group.equals(viewer.groupOf(channel.scope()));
            }
        };
    }
}
