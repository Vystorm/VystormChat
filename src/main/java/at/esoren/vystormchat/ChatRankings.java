package at.esoren.vystormchat;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The ranking {@code chat:messages} in Core's placeholders (Core 0.67.0, capability {@code placeholder-rankings}), in
 * the plugin's namespace {@code chat}: messages a player wrote in public channels ({@link MessageCounts#counts}),
 * read from memory. Only names and numbers, never a message.
 *
 * <p>This is the only class that names Core's ranking types. An older Core lacks them, so it is only touched after
 * the capability check for {@code placeholder-rankings} ({@link ChatPlugin#rankings}).
 */
final class ChatRankings {
    static final String MESSAGES = "messages";

    private ChatRankings() {}

    /** Declares the ranking when {@code on}, otherwise removes it (switch turned off by a reload). */
    static void apply(Plugin plugin, PlaceholderInterface registry, boolean on, Supplier<MessageCounts> counts, UnaryOperator<String> ref) {
        if (!on) {
            registry.namespace(ChatPlaceholders.NAMESPACE).ifPresent(ns -> ns.removeRanking(MESSAGES));
            return;
        }
        registry.register(plugin, ChatPlaceholders.NAMESPACE).ranking(MESSAGES,
                RankingInfo.of(ref.apply("rankings.messages.label"), ref.apply("rankings.messages.description")),
                n -> {
                    MessageCounts current = counts.get();
                    return current == null ? List.of() : current.top(n).stream()
                            .map(s -> new RankingSource.Row(s.player(), s.name(), s.messages())).toList();
                });
    }
}
