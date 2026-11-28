# Vystorm Chat

**Modern chat channels with menus instead of commands.** Global, local, world, trade, staff, party, city and faction chat out of the box, plus as many own channels as you like. Private messages with reply and ignore, hover cards on every name, `[item]` showcase, mentions with sound, and moderation that actually helps: anti-spam, word and link filter, mutes with reason, slow mode and a rotating chat log. Players never need to remember a command: everything is in the chat menu and in `/vsettings`.

Requires **[Vystorm Core](https://www.spigotmc.org/resources/139848/) 0.65.0 or newer** (free). What the build checks: Java 21 bytecode (`--release 21`), compiled against the Paper API 1.21.11 and, in `compat1206` and `test1206`, against Paper 1.20.6, plus unit tests of the Bukkit chat adapter that Spigot/CraftBukkit use. Use the Java version your server needs. See COMPATIBILITY.md for the exact validation scope. Tested on Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2.

On servers without native dialogs, Core presents message, ignore and moderation forms as chat prompts. Chest menus and all chat features remain available. Unknown configured channel icons fall back to an oak sign; sounds unavailable on the server cannot play.

## Features

**Speech bubbles (1.12.0)**
- Public messages float above the writer's head for a few seconds for nearby recipients; switchable per player in `/vsettings`.

**Discord bridge (1.18.0, off by default)**
- Chat channels you choose post to Discord through a webhook, with the player's name and head; join, leave, first join, death, advancement, server start and stop lines each have their own switch.
- With a bot, messages from Discord show in the chat channel, after the word filter, link filter and anti-spam. See [Discord bridge](#discord-bridge) below.

**Channels**
- Channels from `config.yml`, each with its own format, tag, colour, read and write permission, world list, cooldown, optional price per message (Vault) and client tab.
- Scopes: `global`, `world`, `local` (radius), `region` (everyone in the same Vystorm Core region: city, estate, arena ...), `party`, `city`, `faction`.
- Switch the channel you write in with one click in the chat menu, with `/ch CHANNEL`, or with its own command (`/g`, `/l`, `/sc` ...). `/g hello` sends just one message there.
- Quick prefixes: `!hello` goes to global, `#hello` to staff, `$hello` to trade, without switching.
- Hide channels you do not want to read (menu right-click or `/vsettings`), opt-in channels players join themselves.
- "Nobody heard you" hint when no one was in range of a local message.

**Works with the Vystorm family (all optional)**
- Core's `/vtrade` runs through the trade channel: same format, filters, mutes, ignore list and hide setting.
- Party channel hands messages to Vystorm Parties' `/partychat`, so `/pc` and the channel are one chat (without Parties the channel delivers itself through Core's party register).
- City chat for VystormCityClaim members, faction chat for Vystorm-Factions members, region chat through Core's region register.
- Messages are tagged for the chat tabs of the Vystorm client mod; plain clients see no difference.

**Formats**
- MiniMessage formats with placeholders, name formats per permission, Vault prefix, suffix and group (works with LuckPerms), PlaceholderAPI `%placeholders%` (inserted as plain text, so a nickname can never add clicks, hovers or colours; placeholders listed in `placeholderapi-trusted` keep their colours).
- Hover card on every name: rank, world, play time, party, city, faction. Click the name to message the player.
- Timestamps on hover for players who want them.
- `[item]` shows the item in your hand with its full tooltip, `[inv]` a button that opens a read-only copy of your inventory, `[pos]` clickable coordinates.
- Emoji codes: `:heart:` `:star:` `:smile:` `:check:` … become ❤ ★ ☺ ✔ in chat and private messages; `/vchat emojis` lists them, `emojis.codes` in `config.yml` sets your own.
- Clickable links for players with permission and for allowed domains.
- `@Name` mentions (and plain names): highlighted for the mentioned player, sound and action bar, each player can switch the sound off. `@everyone` and `@here` with permission.
- Players format their own messages only with permissions: colours, hex colours, bold/italic/..., magic, gradients, `&` codes. Click, hover and other tags are never parsed.

**Personal keyword alerts**
- The chat menu has a Keywords page (`/vchat menu`, button "Keywords"): your phrases with a remove button, a text field to add one, a switch to pause or resume the alerts and a test field. Players without the client mod get the same page as a chest, where adding and testing ask for the text in a dialog.
- The commands do the same: save words or phrases with `/vchat keywords add PHRASE`; list with `/vchat keywords`, remove with `remove PHRASE`, or `pause` / `resume` without losing the list.
- Case-insensitive literal substring matches in received channel messages show an action-bar hint and use your mention sound setting. Phrases and pause state survive restarts.
- Only normal recipients receive alerts: no self messages, spy copies, private messages or channels forwarded to another plugin. A normal mention takes priority. Filters run before matching.
- Defaults: 10 phrases of 2–48 characters and one alert per recipient every 10 seconds. Lowering the limit activates only the first N saved phrases; the remaining phrases stay saved.

Use `/vchat keywords test TEXT` to privately preview the first matching phrase within the configured keyword limit. The text is matched as supplied, before chat filters or emoji expansion. Testing works while your alerts are paused, does not send a chat message or sound, and does not consume the alert cooldown or modify your saved list. The existing keyword permission and server enable switch apply. German: `/vchat stichworte testen TEXT`; Chinese: `/vchat 关键词 测试 TEXT`.

**Private messages**
- `/vchat replylock` locks your current `/r` partner until logout; repeat to unlock. Incoming messages and messages you send to someone else keep the locked partner. All existing privacy and moderation checks still apply.
- `/msg`, `/r`, sound for the receiver, click a name to answer.
- `/ignore` with a menu; ignored players disappear from chat and private messages.
- "Allow private messages" switch in `/vsettings`; vanished players count as offline.
- `/socialspy` for staff: private messages and spy channels (local, party, city, faction by default).

**Moderation**
- Anti-spam: same message again, too many messages, shouting (lowered or blocked), "heeeeeeey" shortened.
- Word filter: whole words in any case, also spaced out ("b a d") and with look-alikes ("b4d"); regular expressions for anything else; censor or block.
- Link filter for players without permission: links, bare domains and IP addresses are censored or blocked, allowed domains stay.
- `/mute PLAYER [30m|2h|7d] [reason]`, also for offline players; muted players cannot use `/me`, `/msg`, `/pc`, `/vtrade` ... either. Mutes are checked before party chat.
- `/clearchat`, `/slowmode [seconds|off] [channel]`, staff notes about blocked messages and mutes.
- Chat log: `logs/chat-DATE.log`, a new file per day and when a file gets big, old files deleted after 30 days.
- Moderation page in the chat menu: clear chat, slow mode with one click, mute list with unmute, mute dialog.

**Menus and settings**
- Chat menu (`/channel`, `/vchat`, or Vystorm Core main menu → Community → Chat): channels, private message dialog, reply, ignore list, keyword alerts, settings, social spy, moderation.
- Vystorm Core player menu (sneak + right-click a player): message, ignore and (staff) mute.
- `/vsettings` → Chat: mention sound, private message sound, timestamps, allow private messages, social spy, and a show/hide switch per channel.
- Every config value can be edited in game with `/vadmin config` (Vystorm Core).
- English, German, Simplified Chinese, French, Brazilian Portuguese, Italian and Russian texts; each player gets their language through Core.

**Fast and safe**
- Chat uses Paper's async chat event where available and Bukkit's async chat event on Spigot/CraftBukkit. Player positions, groups and ranks are snapshots taken on the server thread. The Bukkit adapter schedules component delivery on the server thread and preserves ordinary recipient filters and cancellation checks.
- Files are written in the background with atomic replace; broken files are kept as `.corrupt-<time>` instead of being overwritten.
- A message that cannot be processed is never sent to anyone else: the sender gets it back with an error note (a staff or party message can never leak into global chat).
- Only the channels in `relay.only-channels` (default: global) stay in the server chat, where DiscordSRV, chat bridges and loggers see them. Staff, party, faction, local and all other channels are taken out of the server chat and delivered by Vystorm Chat itself, so no other plugin relays them; our own chat log, console log, mentions and client tabs work the same. This stays as it is with the built-in Discord bridge, which has its own channel list (`discord.channels`); a channel in both lists would reach Discord twice when DiscordSRV is installed too, so the console warns once.

## Commands

| Command | |
|---|---|
| `/channel` (`/ch`) | chat menu |
| `/channel CHANNEL [message]` | write in a channel (once with a message) |
| `/channel list`, `join CHANNEL`, `leave CHANNEL` | list, show, hide channels |
| `/g`, `/l`, `/wc`, `/tc`, `/sc`, `/cityc`, `/fc` | channel commands from the config |
| `/msg PLAYER MESSAGE` (`/tell`, `/w`, `/m`, `/pm`, `/dm`) | private message; aliases are registered only when not already taken |
| `/r MESSAGE` | answer |
| `/vchat keywords [add PHRASE \| remove PHRASE \| pause \| resume]` | personal keyword list and alerts |
| `/vchat replylock` | lock/unlock your reply partner for this session; requires `vystormchat.use` and `vystormchat.msg` |
| `/ignore [PLAYER \| list]` | ignore or unignore; without a name the ignore menu |
| `/socialspy` | social spy on/off |
| `/mute [PLAYER [time \| perm] [reason]]`, `/unmute PLAYER` | mutes; without a time or with `perm` until unmuted, a mistyped time is rejected; without a name the mute list |
| `/clearchat` (`/cc`) | clear the chat |
| `/slowmode [seconds \| off] [CHANNEL]` | slow mode |
| `/vchat [menu \| help \| mutes \| moderation \| reload]` | menu, help, staff pages, reload |
| `/vchat discord [status]`, `/vchat discord test CHANNEL` | Discord bridge: bridged channels, bot connection, token source, queue, last error; a test line (`vystormchat.admin`) |
| `/discord` | the Discord invite link from `discord.invite.link` (only while no other plugin owns `/discord`) |

## Permissions

| Permission | Default | |
|---|---|---|
| `vystormchat.use` | everyone | chat menu, channels |
| `vystormchat.msg` | everyone | private messages |
| `vystormchat.ignore` | everyone | ignore players |
| `vystormchat.keywords` | everyone | manage and receive keyword alerts (commands also require `vystormchat.use`) |
| `vystormchat.mention` | everyone | mention players |
| `vystormchat.emoji` | everyone | emoji codes like `:heart:` |
| `vystormchat.showcase.item` / `.inventory` / `.position` | everyone | `[item]`, `[inv]`, `[pos]` |
| `vystormchat.channel.staff` | op | staff channel |
| `vystormchat.links` | op | clickable links |
| `vystormchat.mention.everyone` | op | `@everyone`, `@here` |
| `vystormchat.format.color`, `.hex`, `.decoration`, `.magic`, `.gradient`, `.legacy` | nobody (`vystormchat.format.*` op) | formatting in own messages |
| `vystormchat.nameformat.admin` | op | the "admin" name format |
| `vystormchat.msg.bypass` | op | message players who switched messages off or ignore you |
| `vystormchat.seehidden` | op | message vanished players |
| `vystormchat.ignore.exempt` | op | cannot be ignored |
| `vystormchat.spy` | op | social spy |
| `vystormchat.notify` | op | notes about blocked messages and mutes |
| `vystormchat.moderate` | op | moderation page (includes mute, clearchat, slowmode, notify) |
| `vystormchat.mute`, `.mute.exempt` | op | mute; cannot be muted |
| `vystormchat.clearchat`, `.clearchat.exempt` | op | clear chat; keep own chat |
| `vystormchat.slowmode` | op | slow mode |
| `vystormchat.bypass.cooldown`, `.spam`, `.filter`, `.cost` | op | skip the checks |
| `vystormchat.admin` | op | everything, `/vchat reload` |

The trade channel uses Core's `vystorm_core.chat.trade.send` and `.receive` (everyone).

## Switches

Every feature has an on/off switch in `config.yml` (all on by default, listed in Vystorm Core's `/vadmin features`, applied without a restart except `tutorial.enabled`): `chat.enabled` (the whole plugin), `quick-prefixes.enabled`, `paid-channels.enabled`, `channel-choice.enabled`, `notify-nobody-heard`, `family-links.enabled`, `client-tabs`, `name-formats.enabled`, `placeholderapi`, `hover-cards.enabled`, `timestamps.enabled`, `showcase.enabled`, `emojis.enabled`, `links.enabled`, `mentions.enabled`, `mentions.plain-names`, `mentions.action-bar`, `player-formatting.enabled`, `keyword-alerts.enabled`, `private-messages.enabled`, `ignore.enabled`, `social-spy.enabled`, `moderation.spam.enabled`, `moderation.filter.enabled`, `moderation.notify-staff`, `mutes.enabled`, `clear-chat.enabled`, `slow-mode.enabled`, `reports.enabled`, `log.enabled`, `console-log`, `announcements.enabled`, `bubbles.enabled`, `chat-menu.enabled`, `relay.enabled`, `rankings.enabled` (the ranking below, 1.19.0), `tutorial.enabled`. A switched-off feature answers "This feature is switched off on this server."; profiles, mutes, reports, logs and announcements stay saved.

The Discord switches are off by default and need `discord.enabled`: `discord.enabled`, `discord.user-mentions.enabled`, `discord.events.join.enabled`, `discord.events.leave.enabled`, `discord.events.first-join.enabled`, `discord.events.death.enabled`, `discord.events.advancement.enabled`, `discord.events.server-start.enabled`, `discord.events.server-stop.enabled`, `discord.bot.enabled`, `discord.bot.player-count.enabled` (also needs `discord.bot.enabled`), `discord.invite.enabled`, `discord.reports.enabled` (also needs `reports.enabled`). A switch whose parent is off does nothing; the console names the missing switch at start and reload.

## Placeholders

With Vystorm Core 0.65.0 the plugin registers the namespace `chat`. Every value is async-safe, so it also answers in scoreboards, tab lists and chat formats off the server thread; a value of a switched-off feature is empty: `channel`, `channel_name`, `muted`, `mute_remaining`, `party_name`, `city_name`, `faction_name`, `prefix`, `suffix`, `group`, `ignored_count`, `spy`, `reply_partner`. Examples: `%vystorm_chat_channel_name%` (the channel the player writes in) and `{chat:muted}`. `/vadmin placeholders chat` lists them.

## Rankings (1.19.0)

With Vystorm Core 0.67.0 or newer the plugin offers one ranking in its namespace `chat`:

| Source id | Counts |
| --- | --- |
| `chat:messages` | Messages a player wrote in public channels |

Only channels everyone can read count: no read permission, no party, city or faction scope, not forwarded to another plugin. In the shipped config that is `global`, `local` and `world`; `trade` (it has a read permission) and `staff` do not count, and private messages never do. The plugin keeps only the number per player (with the last known name) in `message-counts.yml`, never a message, and writes it at most once a minute and when the server stops. Counting starts with 1.19.0. With `rankings.enabled: false` nothing is counted.

Vystorm Boards 1.21.0 or newer shows it on a board; put a text element above as heading:

```yaml
elements:
  - {type: text, text: "<yellow>Most active chatters", x: 16, y: 12, size: 22}
  - {type: top, source: "chat:messages", x: 16, y: 48, width: 480, rows: 10, highlight: true}
```

Placeholders, with `<n>` = 1 to 100: `%vystorm_chat_top_messages_<n>_name%`, `%vystorm_chat_top_messages_<n>_value%` (formatted in the viewer's language; `_raw` for the plain number, `_uuid` for the UUID), `%vystorm_chat_rank_messages%` and `%vystorm_chat_value_messages%` for the viewer (`-` if none). Short form in Vystorm texts: `{chat:top_messages:1:name}`, `{chat:rank_messages}`. `/vadmin placeholders rankings` lists it. With an older Core the plugin offers no ranking and works as before.

## Configuration

`config.yml` (every key is commented in the file):

| Key | Default | |
|---|---|---|
| `default-channel` | `global` | channel new players write in |
| `channels.<id>.*` | 8 channels | display-name, tag, color, scope, format, quick-prefix, aliases, read/write-permission, cooldown, cost, leavable, auto-join, spy, worlds, radius, region-kinds, client-tab, forward-command, icon, core-trade |
| `name-formats.<id>.permission/format` | admin, default | first matching permission wins |
| `hover-card` | name, rank, world, playtime, party, city, faction, click | lines of the name hover |
| `timestamp-format` | `HH:mm:ss` | time in the timestamp hover |
| `notify-nobody-heard`, `console-log`, `placeholderapi`, `client-tabs` | `true` | |
| `placeholderapi-trusted` | `[]` | PlaceholderAPI tokens (e.g. `%luckperms_prefix%`) whose results keep colours and tags; all others are plain text |
| `relay.only-channels` | `[global]` | channels that stay in the server chat for other plugins (DiscordSRV ...); `"*"` = all |
| `max-message-length` | `256` | |
| `keyword-alerts.enabled` | `true` | enable personal alerts |
| `keyword-alerts.max-keywords` | `10` | active phrases per player, clamped to 1–20 |
| `keyword-alerts.cooldown-seconds` | `10` | per recipient, 0–3600 seconds |
| `mentions.*` | on, plain names, sound | highlight formats, sound, volume, pitch, cooldown-seconds |
| `showcase.*` | on | tokens for item, inventory, position |
| `links.*` | censor | format, without-permission (censor/block), replacement, allowed-domains |
| `moderation.spam.*` | on | repeat-seconds 30, flood 4 in 5 s, caps 70 % of 8+ letters, max-repeated-characters 4 |
| `moderation.filter.*` | on, censor | words, regex, replacement |
| `moderation.notify-staff`, `muted-commands`, `clear-lines` | | |
| `private-messages.*` | on | enabled, formats, sound |
| `ignore.enabled`, `mutes.enabled`, `reports.enabled` | `true` | switch ignore lists, mutes or player reports off; saved data is kept |
| `log.*` | on, 10 MB, 30 days | |
| `discord.channels.<id>.webhook` / `.discord-id` / `.allow-restricted` | global, empty | webhook URL (Minecraft to Discord), Discord channel id (for the bot), bridge a channel only some players may read |
| `discord.avatar` | `https://mc-heads.net/avatar/{uuid}/128` | picture next to a player's messages; `{uuid}`, `{uuid-plain}`, `{name}` |
| `discord.webhook-name` | `{name}` | name above a player's messages |
| `discord.queue-size` | `200` | messages waiting for Discord at most (10–5000) |
| `discord.events.webhook` | empty | where event lines go; empty = the default channel's webhook |
| `discord.bot.format` | `[Discord] <name>: <message>` (with colours) | how Discord messages look in Minecraft |
| `discord.bot-token` | empty | read only when `discord-token.txt` and `VYSTORM_CHAT_DISCORD_TOKEN` are missing |
| `discord.blocked-users` | `[]` | Discord user ids whose messages never show in Minecraft |
| `discord.invite.link`, `discord.reports.webhook` | empty | link for `/discord`; webhook for player reports |

Data files in `plugins/VystormChat/`: `players/<uuid>.yml` (focus channel, hidden channels, ignore list, keyword phrases and pause state, personal settings), `mutes.yml`, `logs/chat-YYYY-MM-DD[.n].log`. Texts in seven languages: `lang/en.yml`, `de`, `fr`, `it`, `pt_br`, `ru` and `zh_cn`.

## Building

Build with JDK 25; plugin and test bytecode targets Java 21. The supplied Core jar must be version 0.65.0 or newer.

`check` includes `compat1206` (all main sources compiled against Paper 1.20.6) and `test1206` (compatibility tests using the old runtime API). Supply the local server libraries with `-Ppaper1206Libraries=/path/to/smoke-1206/libraries`; the default is `<home>/source/tools/smoke-1206/libraries`. These checks work offline with cached build dependencies and do not start a server. Newer API adapters are supplied by Core, so this plugin needs no additional source set.

`./gradlew build -PvystormCoreJar=path/to/Vystorm_Core.jar` → `build/libs/VystormChat-<version>.jar`

## Discord bridge

Vystorm Chat talks to Discord itself, without DiscordSRV and without a Discord library. There are two levels, both off by default.

**Level 1, webhooks (Minecraft to Discord).** No bot is needed.

1. In Discord: Channel settings → Integrations → Webhooks → New Webhook → Copy Webhook URL.
2. In `config.yml`: set `discord.enabled: true` and paste the URL into `discord.channels.global.webhook` (use the id of any chat channel instead of `global`; one webhook per channel). Keep the URL secret: whoever has it can post into that channel.
3. Optional: switch on the event lines under `discord.events` (join, leave, first join, death, advancement, server start, server stop). They go to `discord.events.webhook`, or to the default channel's webhook when that is empty.
4. `/vchat reload`, then `/vchat discord test global` sends a test line. `/vchat discord status` shows errors.

**Level 2, the bot (Discord to Minecraft).**

1. Go to the Discord developer portal (discord.com/developers/applications), create an application, open "Bot", and copy the token (Reset Token).
2. On the same "Bot" page switch on **Message Content Intent**. This is a privileged intent: without it Discord refuses the connection (close code 4014) and the console says so. The bot asks for nothing else than server messages and their content.
3. Invite the bot: OAuth2 → URL Generator, scope `bot`, bot permissions "View Channels" and "Send Messages" (permission value 3072). The link looks like `https://discord.com/oauth2/authorize?client_id=APPLICATION_ID&scope=bot&permissions=3072`.
4. Put the token into the file `plugins/VystormChat/discord-token.txt` (one line), or into the environment variable `VYSTORM_CHAT_DISCORD_TOKEN`. `discord.bot-token` in `config.yml` works too, but the file keeps the token out of the config and its backups.
5. Turn on developer mode in Discord (User settings → Advanced), right-click the Discord channel → Copy Channel ID, and put it into `discord.channels.global.discord-id` (in quotes).
6. Set `discord.bot.enabled: true` and run `/vchat reload`. `/vchat discord status` shows `ready` when the bot is connected.

What the bot does: messages in a mapped Discord channel show in the chat channel as `[Discord] Name: text` (`discord.bot.format`). They pass the word filter, link filter and anti-spam like a player without bypass rights; only players who may read the channel and have not hidden it see them. Files show as "[file]", with the link only when links to that site are allowed. Replies, mentions and Discord emoji become plain text, markdown is removed. Messages from bots and webhooks (also the bridge's own posts) are ignored, so nothing loops. `discord.blocked-users` keeps single Discord users out. A channel that only has a `discord-id` and no webhook is posted to by the bot as `**Name**: text`. Optional: `discord.bot.player-count.enabled` shows the player count as the bot's status.

**Which channels take part.** Only chat channels listed under `discord.channels`, and only channels with scope `global`. Private messages and local, world, party, city, faction and region chats never leave the server. A channel whose read permission not everyone has by default (the staff channel) needs `allow-restricted: true`; the trade channel uses Core's trade permission, which everyone has, so it can be bridged like global. Muted players, blocked messages and censored words reach Discord the way players see them: not at all, or censored. Nothing a player writes pings @everyone, @here or a role; `discord.user-mentions.enabled` allows pinging Discord users by their id mention.

**Other extras.** `discord.invite.enabled` with `discord.invite.link` gives players `/discord` with a clickable invite (left out when another plugin owns `/discord`). `discord.reports.enabled` with `discord.reports.webhook` posts every new player report to a staff channel as well.

**Limits and safety.** Discord's rate limits are kept per webhook and channel, and a "too many requests" answer makes that channel wait as long as Discord asks. Waiting messages stay in a queue of `discord.queue-size` (200); further messages are dropped and the console says how many. A deleted webhook or a wrong token stops that channel until `/vchat reload`. Nothing of this runs on the server thread. The token and the webhook URLs never appear in the console, in `/vadmin config` or in `/vchat discord status`.

**Together with DiscordSRV.** `relay.only-channels` works as before: the channels listed there stay in the server chat, where DiscordSRV sees them. If you keep DiscordSRV, do not bridge the same channel in both; the console warns once when both are active.

**Privacy.** With the bridge on, these leave the server: the player's name, the player's UUID inside the avatar URL (Discord loads the head picture from that address, by default mc-heads.net), the text of messages in bridged channels after the filters, the event lines you switched on, and with `discord.reports.enabled` the reporter, the reported player and the reason. They go to Discord through the webhooks and the bot you set up. From Discord, the plugin receives the messages of the channels the bot can see, and shows only those of mapped channels.

**Not included.** Linking Minecraft and Discord accounts, Discord slash commands, roles or rank sync, and console channels.

## Player reports and moderation cases

Players can use `/vchat report PLAYER REASON` or the chat menu to submit a report. Reports are explicitly unverified allegations and never trigger automatic punishment. Staff with `vystormchat.moderate` can open `/vchat reports` to inspect the reason, add timestamped notes, resolve or reopen cases and browse their history. This feature stores the submitted description; it does not secretly copy private conversations.

Staff who also have `vystormchat.mute` can confirm a timed mute (up to seven days) or unmute through the existing moderation engine. Exemption permissions still apply. History records the request before attempting moderation; the current restriction shows the outcome, so a crash does not falsely imply a completed punishment. Mute changes reach disk before successful moderation feedback is sent.

Reports and notes are stored atomically in `reports.yml`. A player can have 10 open reports, must wait one minute between submissions and cannot duplicate an open report about the same player. Cases expire after 30 days; cases older than 60 days are pruned on the next submission. Storage is bounded to 1000 cases with 100 history entries each. No new change is applied after a failed save, and corrupt report files are retained read-only. All new messages are available in all seven bundled languages.

## License

Copyright (c) 2026 Esoren / Vystorm

Versions after 1.15.0 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.15.0 and earlier were released under the MIT License ([LICENSE-MIT-until-1.15.0.txt](LICENSE-MIT-until-1.15.0.txt)) and stay available under those terms.
