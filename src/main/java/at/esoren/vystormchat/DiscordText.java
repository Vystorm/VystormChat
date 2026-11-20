package at.esoren.vystormchat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text between Minecraft and Discord, without any network or server access (so all of it is unit tested).
 * Towards Discord: markdown escaped, mass mentions broken with a zero-width space, length cut to Discord's limits.
 * From Discord: mentions, channels, emoji and timestamps as plain text, markdown markers removed, one line.
 */
final class DiscordText {
    private DiscordText() {}

    /** Discord's limit for the content of one message. */
    static final int MAX_CONTENT = 2000;
    /** Discord's limit for a webhook user name. */
    static final int MAX_NAME = 80;
    /** Longest text that is taken from Discord into the Minecraft chat. */
    static final int MAX_INBOUND = 256;

    private static final String ZWSP = "​";
    private static final Pattern MASS = Pattern.compile("@(everyone|here)", Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d{1,20})>");
    private static final Pattern ROLE_MENTION = Pattern.compile("<@&(\\d{1,20})>");
    private static final Pattern CHANNEL_MENTION = Pattern.compile("<#(\\d{1,20})>");
    private static final Pattern EMOJI = Pattern.compile("<a?:(\\w{1,32}):\\d{1,20}>");
    private static final Pattern TIMESTAMP = Pattern.compile("<t:(-?\\d{1,13})(?::[tTdDfFR])?>");
    private static final Pattern SLASH_COMMAND = Pattern.compile("</([\\w -]{1,64}):\\d{1,20}>");
    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:[\\w+-]{1,20}\\n)?(.*?)```", Pattern.DOTALL);
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern UNDERLINE = Pattern.compile("__(.+?)__");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])");
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile("(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    private static final Pattern SPOILER = Pattern.compile("\\|\\|(.+?)\\|\\|");
    private static final Pattern MASKED_LINK = Pattern.compile("\\[([^\\]]{1,200})]\\(<?(https?://[^)\\s>]{1,500})>?\\)");
    private static final Pattern LINE_MARKERS = Pattern.compile("(?m)^\\s*(?:#{1,3} |-# |>>> |> |[-*] )");
    private static final Pattern ESCAPED = Pattern.compile("\\\\([\\\\*_~`|>#\\[\\]()-])");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    // ------------------------------------------------------------------ Minecraft -> Discord

    /** Backslash before every character Discord reads as markdown, so player text shows exactly as typed. */
    static String escapeMarkdown(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() + 8);
        boolean lineStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("\\*_~`|[]".indexOf(c) >= 0 || lineStart && "#>-".indexOf(c) >= 0) out.append('\\');
            out.append(c);
            lineStart = c == '\n';
        }
        return out.toString();
    }

    /**
     * Breaks @everyone, @here and (unless user mentions are allowed) {@code <@id>} mentions with a zero-width space, so
     * they show as text even if a Discord client highlighted them. The real protection is the empty
     * {@code allowed_mentions} of every request; this is the second line.
     */
    static String neutralise(String text, boolean userMentions) {
        if (text == null) return "";
        String out = MASS.matcher(text).replaceAll("@" + ZWSP + "$1");
        out = out.replace("<@&", "<" + ZWSP + "@&");
        if (!userMentions) out = out.replace("<@", "<" + ZWSP + "@");
        return out;
    }

    /** At most {@code max} characters (code points kept whole); a cut text ends with "…". */
    static String cut(String text, int max) {
        if (text == null) return "";
        if (text.codePointCount(0, text.length()) <= max) return text;
        int end = text.offsetByCodePoints(0, max - 1);
        return text.substring(0, end) + "…";
    }

    /** One chat line for Discord: escaped, neutralised and cut to {@link #MAX_CONTENT}. */
    static String content(String plain, boolean userMentions) {
        return cut(neutralise(escapeMarkdown(plain), userMentions), MAX_CONTENT);
    }

    /**
     * A webhook user name: 1 to 80 characters. Discord refuses names that contain "discord" or "clyde" (the request
     * fails with 400), so those words get one letter replaced.
     */
    static String webhookName(String name) {
        String out = name == null ? "" : name.replaceAll("\\p{Cntrl}", "").strip();
        out = Pattern.compile("discord", Pattern.CASE_INSENSITIVE).matcher(out).replaceAll(match -> Matcher.quoteReplacement(match.group().substring(0, 4) + "0" + match.group().substring(5)));
        out = Pattern.compile("clyde", Pattern.CASE_INSENSITIVE).matcher(out).replaceAll(match -> Matcher.quoteReplacement(match.group().substring(0, 4) + "3"));
        if (out.isEmpty()) out = "Player";
        return cut(out, MAX_NAME);
    }

    /** A template with {@code {uuid}}, {@code {uuid-plain}} (no dashes) and {@code {name}}; {@code url}: the name URL-encoded. */
    static String fill(String template, java.util.UUID uuid, String name, boolean url) {
        if (template == null) return "";
        String id = uuid == null ? "" : uuid.toString();
        String shown = name == null ? "" : url ? java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8) : name;
        return template.replace("{uuid-plain}", id.replace("-", "")).replace("{uuid}", id).replace("{name}", shown);
    }

    // ------------------------------------------------------------------ Discord -> Minecraft

    /**
     * Discord markup as plain text: {@code <@id>} becomes {@code @name} (names from the message's mention list, else
     * "@user"), roles "@role", channels "#channel", custom emoji {@code :name:}, timestamps a UTC date, masked links
     * "text (url)"; markdown markers are removed, line breaks become spaces, control characters are dropped.
     */
    static String fromDiscord(String content, Map<String, String> users) {
        if (content == null || content.isEmpty()) return "";
        // escaped markers (\*) are parked in the private use area so the marker patterns below skip them
        String parked = ESCAPED.matcher(content).replaceAll(match -> Matcher.quoteReplacement(String.valueOf((char) (0xE000 + match.group(1).charAt(0)))));
        String text = CODE_BLOCK.matcher(parked).replaceAll(match -> Matcher.quoteReplacement(match.group(1).strip()));
        text = INLINE_CODE.matcher(text).replaceAll("$1");
        text = USER_MENTION.matcher(text).replaceAll(match -> Matcher.quoteReplacement("@" + users.getOrDefault(match.group(1), "user")));
        text = ROLE_MENTION.matcher(text).replaceAll("@role");
        text = CHANNEL_MENTION.matcher(text).replaceAll("#channel");
        text = EMOJI.matcher(text).replaceAll(":$1:");
        text = SLASH_COMMAND.matcher(text).replaceAll("/$1");
        text = TIMESTAMP.matcher(text).replaceAll(match -> {
            try { return Matcher.quoteReplacement(TIME.format(Instant.ofEpochSecond(Long.parseLong(match.group(1))))); }
            catch (RuntimeException outOfRange) { return "?"; }
        });
        text = MASKED_LINK.matcher(text).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + " (" + match.group(2) + ")"));
        text = LINE_MARKERS.matcher(text).replaceAll("");
        for (Pattern marker : new Pattern[] {BOLD, UNDERLINE, STRIKE, SPOILER, ITALIC_STAR, ITALIC_UNDERSCORE})
            text = marker.matcher(text).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp >= 0xE000 && cp < 0xE080) out.append((char) (cp - 0xE000));
            else if (cp == '\n' || cp == '\r' || cp == '\t') out.append(' ');
            else if (!Character.isISOControl(cp) && cp != 0x200B) out.appendCodePoint(cp);
        });
        return out.toString().replaceAll(" {2,}", " ").strip();
    }

    /** The name a Discord member shows: server nickname, global display name, user name. */
    static String displayName(String nick, String globalName, String username) {
        for (String candidate : new String[] {nick, globalName, username})
            if (candidate != null && !candidate.isBlank()) return fromDiscord(candidate, Map.of());
        return "Discord";
    }
}
