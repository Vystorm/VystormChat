package at.esoren.vystormchat;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The values of Vystorm Chat in Core's placeholder registry, namespace {@code chat}. They read the snapshots the chat
 * thread uses as well (concurrent maps, volatile fields, immutable records), so every value is async-safe and answers
 * in scoreboards, tab lists and chat formats off the server thread. A value of a switched-off feature is empty.
 *
 * <pre>
 * %vystorm_chat_channel%        {chat:channel}        id of the channel the player writes in
 * %vystorm_chat_channel_name%   {chat:channel_name}   its display name
 * %vystorm_chat_muted%          {chat:muted}          true or false
 * %vystorm_chat_mute_remaining% {chat:mute_remaining} time left, "permanent", or empty
 * party_name, city_name, faction_name, prefix, suffix, group, ignored_count, spy, reply_partner
 * </pre>
 */
final class ChatPlaceholders {
    static final String NAMESPACE = "chat";

    /** What the values read, at call time (a reload replaces the settings). */
    interface Source {
        Settings settings();
        Profile profile(UUID player);
        PlayerState state(UUID player);
        MuteStore.Mute mute(UUID player, long now);
        UUID partner(UUID player);
    }

    private ChatPlaceholders() {}

    static PlaceholderNamespace register(PlaceholderInterface registry, Plugin owner, Source source, UnaryOperator<String> describe) {
        PlaceholderNamespace ns = registry.register(owner, NAMESPACE);
        value(ns, describe, "channel", Settings.Feature.CHAT, source, (s, id) -> channel(s, source.profile(id)).id());
        value(ns, describe, "channel_name", Settings.Feature.CHAT, source, (s, id) -> channel(s, source.profile(id)).displayName());
        value(ns, describe, "muted", Settings.Feature.MUTES, source, (s, id) -> source.mute(id, System.currentTimeMillis()) != null);
        value(ns, describe, "mute_remaining", Settings.Feature.MUTES, source, (s, id) -> {
            MuteStore.Mute mute = source.mute(id, System.currentTimeMillis());
            if (mute == null) return "";
            return mute.permanent() ? "permanent" : Durations.format(mute.remaining(System.currentTimeMillis()));
        });
        value(ns, describe, "party_name", Settings.Feature.FAMILY_LINKS, source, (s, id) -> state(source, id, PlayerState::partyName));
        value(ns, describe, "city_name", Settings.Feature.FAMILY_LINKS, source, (s, id) -> state(source, id, PlayerState::cityName));
        value(ns, describe, "faction_name", Settings.Feature.FAMILY_LINKS, source, (s, id) -> state(source, id, PlayerState::factionName));
        value(ns, describe, "prefix", Settings.Feature.CHAT, source, (s, id) -> state(source, id, PlayerState::prefix));
        value(ns, describe, "suffix", Settings.Feature.CHAT, source, (s, id) -> state(source, id, PlayerState::suffix));
        value(ns, describe, "group", Settings.Feature.CHAT, source, (s, id) -> state(source, id, PlayerState::group));
        value(ns, describe, "ignored_count", Settings.Feature.IGNORE, source, (s, id) -> {
            Profile profile = source.profile(id);
            return profile == null ? "" : profile.ignored.size();
        });
        value(ns, describe, "spy", Settings.Feature.SOCIAL_SPY, source, (s, id) -> {
            Profile profile = source.profile(id);
            return profile != null && profile.spy();
        });
        value(ns, describe, "reply_partner", Settings.Feature.PRIVATE_MESSAGES, source, (s, id) -> {
            UUID partner = source.partner(id);
            if (partner == null) return "";
            if (partner.equals(PrivateMessages.CONSOLE)) return "Console";
            PlayerState state = source.state(partner);
            return state == null ? "" : state.name();
        });
        return ns;
    }

    /** One async-safe value of the viewer that is empty while {@code feature} (or chat.enabled) is off. */
    private static void value(PlaceholderNamespace ns, UnaryOperator<String> describe, String key, Settings.Feature feature, Source source,
                              BiFunction<Settings, UUID, Object> value) {
        ns.add(PlaceholderValue.viewer(key, viewer -> {
            Settings settings = source.settings();
            if (settings == null || !settings.enabled(feature)) return "";
            return value.apply(settings, viewer.getUniqueId());
        }).asyncSafe().description(describe.apply("placeholder." + key)));
    }

    /** The channel the player writes in, from the stored choice (no permission check, which needs the server thread). */
    private static Channel channel(Settings settings, Profile profile) {
        Channel chosen = profile == null ? null : settings.channel(profile.focus());
        return chosen != null ? chosen : settings.defaultChannel();
    }

    private static Object state(Source source, UUID id, java.util.function.Function<PlayerState, String> field) {
        PlayerState state = source.state(id);
        String value = state == null ? null : field.apply(state);
        return value == null ? "" : value;
    }
}
