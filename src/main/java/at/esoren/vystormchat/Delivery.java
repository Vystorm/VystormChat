package at.esoren.vystormchat;

import Vystorm.vystorm_core.lang.Lang;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/**
 * One accepted message on its way to the recipients. The line is rendered per viewer language (hover card, buttons),
 * with the viewer's own mention highlighted and the timestamp hover when they want it; renders are cached so 100+
 * viewers cost a handful of renders. Safe to render from any thread.
 */
final class Delivery {
    final ChatService service;
    final Player sender;
    final PlayerState state;
    final Channel channel;
    final Settings settings;
    final String text, plain;
    final Set<PlayerText.Style> styles;
    final boolean linksAllowed;
    final Showcase.Snapshot showcase;
    final List<Mentions.Hit> hits;
    final Set<UUID> mentioned;
    final boolean everyone;
    final Map<UUID, Visibility.Reach> reach;
    final long time = System.currentTimeMillis();

    private final Map<String, Component> bodies = new ConcurrentHashMap<>(), names = new ConcurrentHashMap<>(), lines = new ConcurrentHashMap<>();
    private final Map<String, String> placeholderValues = new ConcurrentHashMap<>();

    Delivery(ChatService service, Player sender, PlayerState state, Channel channel, String text, String plain, Set<PlayerText.Style> styles,
             boolean linksAllowed, Showcase.Snapshot showcase, List<Mentions.Hit> hits, Map<UUID, Visibility.Reach> reach) {
        this.service = service;
        this.sender = sender;
        this.state = state;
        this.channel = channel;
        this.settings = service.settings();
        this.text = text;
        this.plain = plain;
        this.styles = styles;
        this.linksAllowed = linksAllowed;
        this.showcase = showcase;
        this.hits = hits;
        this.mentioned = Mentions.players(hits);
        this.everyone = Mentions.everyone(hits);
        this.reach = reach;
    }

    /** Players who get the message normally (not as spy), without the sender. */
    Delivery restrictTo(Set<UUID> recipients) {
        Map<UUID, Visibility.Reach> restricted = new java.util.LinkedHashMap<>();
        reach.forEach((id, visibility) -> { if (recipients.contains(id)) restricted.put(id, visibility); });
        return new Delivery(service, sender, state, channel, text, plain, styles, linksAllowed, showcase, hits, restricted);
    }

    /** Players who get the message normally (not as spy), without the sender. */
    List<UUID> listeners() {
        List<UUID> list = new ArrayList<>();
        for (Map.Entry<UUID, Visibility.Reach> entry : reach.entrySet())
            if (entry.getValue() == Visibility.Reach.NORMAL && !entry.getKey().equals(sender.getUniqueId())) list.add(entry.getKey());
        return list;
    }

    boolean mentions(UUID viewer) {
        return !viewer.equals(sender.getUniqueId()) && (mentioned.contains(viewer) || everyone) && reach.get(viewer) == Visibility.Reach.NORMAL;
    }

    /** The line for one viewer (console: English, no extras). */
    Component render(Object viewer) {
        Lang lang = service.lang();
        if (!(viewer instanceof Player player)) return line("en", null, false, false, false);
        UUID id = player.getUniqueId();
        Profile profile = service.plugin().profiles().cached(id);
        boolean timestamps = profile != null && profile.timestamps();
        boolean spy = reach.get(id) == Visibility.Reach.SPY;
        String language = lang.language(player);
        if (mentions(id)) return line(language, id, spy, timestamps);
        return lines.computeIfAbsent(language + '|' + timestamps + '|' + spy, key -> line(language, null, spy, timestamps));
    }

    private Component line(String language, UUID highlight, boolean spy, boolean timestamps) {
        return line(language, highlight, spy, timestamps, true);
    }

    private Component line(String language, UUID highlight, boolean spy, boolean timestamps, boolean clientTag) {
        Lang lang = service.lang();
        Function<String, String> papi = settings.placeholderApi && service.plugin().hooks().papi()
                ? token -> placeholderValues.computeIfAbsent(token, t -> service.plugin().hooks().placeholder(sender, t)) : null;
        String groupName = state.groupName(channel.scope());
        Component line = Formats.render(channel.format(), papi, settings::trustedPlaceholder,
                Placeholder.component("channel", Formats.render(channel.tag(), null, Formats.playerTags(state),
                        Placeholder.unparsed("group_name", groupName == null ? "" : groupName))),
                Placeholder.unparsed("channel_name", channel.displayName()),
                Placeholder.unparsed("group_name", groupName == null ? "" : groupName),
                Placeholder.component("name", name(language)),
                Placeholder.component("message", body(language, highlight)),
                Formats.playerTags(state));
        if (spy) line = lang.component(language, "spy-prefix").append(line);
        if (timestamps) {
            String at = DateTimeFormatter.ofPattern(settings.timestampFormat, Locale.ROOT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time));
            line = Component.text().append(line).hoverEvent(HoverEvent.showText(lang.component(language, "timestamp-hover", "time", at))).build();
        }
        return clientTag ? service.clientTab(channel, line) : line;
    }

    /** The sender's name from the name format, with hover card and click-to-message. */
    Component name(String language) {
        return names.computeIfAbsent(language, code -> {
            Formats.NameFormat format = settings.formats.nameFormat(sender::hasPermission);
            Component name = Formats.render(format.format(), null, Formats.playerTags(state));
            return name.hoverEvent(HoverEvent.showText(service.hoverCard(code, state)))
                    .clickEvent(ClickEvent.suggestCommand("/msg " + state.name() + " "));
        });
    }

    /** The message: player text, channel colour, links, showcase parts and mentions. */
    Component body(String language, UUID highlight) {
        Component base = bodies.computeIfAbsent(language, this::baseBody);
        if (hits.isEmpty()) return base;
        String key = language + "|m|" + (highlight == null ? "" : "self");
        if (highlight == null || !(mentioned.contains(highlight) || everyone)) return bodies.computeIfAbsent(key, k -> mentionBody(base, null));
        return mentionBody(base, highlight);
    }

    private Component baseBody(String language) {
        Lang lang = service.lang();
        Component body = PlayerText.parse(text, styles);
        TextColor color = Formats.color(channel.color());
        if (color != null) body = body.colorIfAbsent(color);
        body = body.replaceText(TextReplacementConfig.builder().match(Links.LINK).replacement((match, builder) -> {
            String link = Links.trimTrailing(match.group(1));
            if (!linksAllowed && !Links.allowed(link, settings.allowedDomains)) return builder;
            Component clickable = Formats.render(settings.linkFormat, null, Placeholder.unparsed("link", link))
                    .clickEvent(ClickEvent.openUrl(Links.url(link)))
                    .hoverEvent(HoverEvent.showText(lang.component(language, "link-hover", "url", Links.url(link))));
            String rest = match.group(1).substring(link.length());
            return rest.isEmpty() ? clickable : Component.text().append(clickable).append(Component.text(rest));
        }).build());
        if (showcase.item() != null) body = replaceToken(body, settings.itemTokens, Showcase.item(service.plugin().core().compat().platform(), lang, language, showcase.item()));
        if (showcase.inventory() != null)
            body = replaceToken(body, settings.inventoryTokens, Showcase.inventoryButton(lang, language, showcase.inventory(), showcase.owner(), service.plugin()));
        if (showcase.position() != null) body = replaceToken(body, settings.positionTokens, service.position(showcase.position()));
        return body;
    }

    private static Component replaceToken(Component body, Set<String> tokens, Component replacement) {
        StringBuilder pattern = new StringBuilder();
        for (String token : tokens) pattern.append(pattern.isEmpty() ? "" : "|").append(Pattern.quote(token));
        return body.replaceText(TextReplacementConfig.builder().match(Pattern.compile(pattern.toString(), Pattern.CASE_INSENSITIVE))
                .once().replacement(replacement).build());
    }

    private Component mentionBody(Component base, UUID highlight) {
        Map<String, UUID> online = service.plugin().states().names();
        StringBuilder names = new StringBuilder();
        for (UUID id : mentioned) {
            PlayerState target = service.plugin().states().get(id);
            if (target != null) names.append(names.isEmpty() ? "" : "|").append(Pattern.quote(target.name()));
        }
        if (everyone) names.append(names.isEmpty() ? "" : "|").append("everyone|here");
        if (names.isEmpty()) return base;
        String at = settings.mentionsPlainNames ? "@?" : "@";
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_@])" + at + "(?:" + names + ")(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        return base.replaceText(TextReplacementConfig.builder().match(pattern).replacement((match, builder) -> {
            String found = match.group();
            String bare = found.startsWith("@") ? found.substring(1).toLowerCase(Locale.ROOT) : found.toLowerCase(Locale.ROOT);
            if (!found.startsWith("@") && (bare.equals("everyone") || bare.equals("here"))) return builder;
            boolean everyoneToken = found.startsWith("@") && (bare.equals("everyone") || bare.equals("here"));
            if (!everyoneToken && !online.containsKey(bare)) return builder;
            boolean self = highlight != null && (everyoneToken || highlight.equals(online.get(bare)));
            String format = self ? settings.mentionHighlightSelf : settings.mentionHighlight;
            return Formats.render(format, null, Placeholder.unparsed("mention", found));
        }).build());
    }
}
