## 1.17.0 — 2026-11-18

- Requires Vystorm Core 0.65.0 or newer. Update Core before you update this plugin.
- Every feature can now be switched on and off in `config.yml`. All switches are on by default, so a server that updates keeps working as before; the new keys are added to your existing `config.yml` with their comments. Each switch is listed in Core's `/vadmin features` with a one-line description in seven languages and applies at once, without a restart (only `tutorial.enabled` needs one).
  - The whole plugin: `chat.enabled`. Off: Vystorm Chat leaves the server chat alone (no channels, formats, filters or mutes) and every command of it except `/vchat reload` is switched off. `/msg` and its aliases then go to the vanilla `/msg` at once, without a restart; `/reply` has no vanilla counterpart and answers the off message.
  - Channels: `quick-prefixes.enabled`, `paid-channels.enabled` (off: every channel is free), `channel-choice.enabled` (off: everybody hears the auto-join channels; the players' choices are kept), `family-links.enabled` (party, city, faction and region chats, forwarded channels and `/vtrade` through the trade channel).
  - Looks: `name-formats.enabled`, `hover-cards.enabled`, `timestamps.enabled`, `player-formatting.enabled`.
  - Links: `links.enabled`. Off: links are plain text, neither clickable nor censored or blocked. Before this version the link filter could not be turned off.
  - Moderation: `social-spy.enabled`, `clear-chat.enabled`, `slow-mode.enabled`.
  - Other: `chat-menu.enabled` (chat menu, moderation menu and the page in `/vsettings`), `relay.enabled` (off: no message stays in the server chat, so plugins like DiscordSRV see no chat), `tutorial.enabled` (needs a restart).
  - Keys that already existed keep their names and are now listed as switches too: `notify-nobody-heard`, `client-tabs`, `placeholderapi`, `console-log`, `mentions.plain-names`, `mentions.action-bar`, `moderation.notify-staff`.
- A switched-off feature answers one shared message, "This feature is switched off on this server.", its commands are hidden from help and tab completion, and its buttons leave the chat menu, the moderation menu and `/vsettings`.
- Tab completion and help follow a switch at once, without registering the command again.
- Placeholders: the plugin registers its values with Vystorm Core under the namespace `chat`: the channel the player writes in and its name, the mute and its time left, party, city and faction, the Vault prefix, suffix and group, the number of ignored players, social spy and the last private message partner. Every value also answers off the server thread, so scoreboards, tab lists and chat formats can show it. Examples: `%vystorm_chat_channel_name%` (the channel the player writes in) and `{chat:muted}`. `/vadmin placeholders chat` lists them. A value of a switched-off feature is empty.
- The off messages of keyword alerts, emoji codes and announcements are now the shared message.
- README: the compatibility line now says what the build checks, and the stale release note was removed.

## 1.16.0 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.15.0 — 2026-11-16

- Four more features can be switched off on their own in `config.yml` (and in Core's feature switches): private messages (`private-messages.enabled`), ignore lists (`ignore.enabled`), mutes (`mutes.enabled`) and player reports with the case history (`reports.enabled`). All four stay on by default.
- A feature that is off answers its commands and menu buttons with "This feature is turned off on this server." and its buttons leave the menus. Nothing is deleted: saved cases, mutes and ignore lists stay on disk and apply again when the switch is turned back on. While mutes are off nobody is muted; while ignore lists are off they have no effect.

## 1.14.0 — 2026-11-15

- Rebuild the chat overview, ignored players, moderation, active mutes, player reports, case history and read-only inventory showcase as Core Panels with paged selection, details and localized controls.
- Keep every original chest view as the fallback for older Core or unavailable panels. No item-moving menus exist in this plugin; no view remains chest-only.

## 1.13.0 — 2026-11-12

- Announcement planner: `/vchat announcements` (permission `vystormchat.announce`) opens a dialog listing every timed broadcast. Add one with a text (MiniMessage or & codes), an interval in minutes and optionally one world; edit, pause, resume, send now or delete it with a click. Stored in `announcements.yml`; setting `announcements.enabled`, also in the first-run setup.

## 1.12.0 — 2026-11-11

- Speech bubbles: messages in the global, local and world channels float above the writer's head for five seconds, seen by recipients within 24 blocks. Players switch them off in `/vsettings` → Chat; servers set channels, time, radius and width under `bubbles.*`. The bubbles are never saved with the world.

## 1.11.0 — 2026-11-11

- Short tutorial in the main menu (/vmenu → Tutorials) in all seven languages.

## 1.10.0 — 2026-11-10

- First-run setup: when the plugin is installed for the first time, admins answer a few questions or take the recommended settings, with a preview before anything is saved (needs Vystorm Core 0.54.0 or newer).
- All settings, including extra configuration files, appear in Core's new `/vadmin config` menu.

## 1.9.0 - Player reports and moderation cases

Players can use `/vchat report PLAYER REASON` or the chat menu to submit a report. Reports are explicitly unverified allegations and never trigger automatic punishment. Staff with `vystormchat.moderate` can open `/vchat reports` to inspect the reason, add timestamped notes, resolve or reopen cases and browse their history. This feature stores the submitted description; it does not secretly copy private conversations.

Staff who also have `vystormchat.mute` can confirm a timed mute (up to seven days) or unmute through the existing moderation engine. Exemption permissions still apply. History records the request before attempting moderation; the current restriction shows the outcome, so a crash does not falsely imply a completed punishment. Mute changes reach disk before successful moderation feedback is sent.

Reports and notes are stored atomically in `reports.yml`. A player can have 10 open reports, must wait one minute between submissions and cannot duplicate an open report about the same player. Cases expire after 30 days; cases older than 60 days are pruned on the next submission. Storage is bounded to 1000 cases with 100 history entries each. No new change is applied after a failed save, and corrupt report files are retained read-only. All new messages are available in all seven bundled languages.

## 1.8.0 - Spigot and CraftBukkit compatibility

- Requires Core 0.51.0 or newer.
- Bukkit async chat preserves recipient filters, cancellation and private-channel visibility; component delivery avoids duplicate native string messages. Paper events stay isolated. Inventory showcase callbacks use Core and console rendering omits client-only channel tags.
- 101 automated checks passed (97 regular and 4 Paper 1.20.6 checks). Two actual protocol clients on official Spigot 26.3 and CraftBukkit 26.3 verified exactly-once public delivery, staff-channel privacy from a non-operator, switching back to global, and readable console logs. These are targeted checks, not exhaustive third-party chat relay acceptance.

## 1.7.0 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

## 1.6.0 (2026-11-05)

- Add `/vchat keywords test TEXT` for a private, read-only preview of the first literal match within the configured limit. Testing preserves pause state, saved phrases and cooldown; it does not broadcast, apply chat filters, expand emojis or play sounds. English, German and Simplified Chinese commands and feedback.

# Changelog

## 1.5.0 (2026-11-03)

- Runs on Paper 1.20.6 – 26.x. Plugin and tests now target Java 21; the server may require a newer Java version.
- Requires Vystorm Core 0.48.0: portable dialogs select native forms where available and chat prompts on older servers. Message, ignore and moderation actions remain available; no chat feature is disabled.
- Inventory menus and read-only inventory showcases use Core's compatibility adapter for the InventoryView class/interface change.
- Unknown configured channel icons retain their oak-sign fallback; sounds absent on older servers cannot play.
- Offline verification: `compat1206` compiles all main sources against the local 1.20.6 libraries; `test1206` exercises feature detection, chat fallback loading and inventory access against the old API. Both run as part of `check`.

## 1.4.0 (2026-11-03)

- New: persistent personal keyword alerts. `/vchat keywords` lists phrases; `add PHRASE`, `remove PHRASE`, `pause` and `resume` manage them through Core commands in English, German and Simplified Chinese. Literal case-insensitive substring matches in received channel messages trigger an action-bar hint and the existing optional mention sound. Normal mentions take priority; self messages, spy copies, private messages and externally forwarded channels do not trigger alerts.
- Config: `keyword-alerts.enabled`, `max-keywords` (default 10, maximum 20) and `cooldown-seconds` (default 10, per recipient). Missing keys are added to existing configs without replacing owner values. Permission: `vystormchat.keywords` (everyone). Phrases and pause state use the existing atomic player-profile storage.

## 1.3.0 (2026-11-02)

- New: `/vchat replylock` keeps `/r` aimed at the current private-message partner, even when someone else messages you or you message another player. Repeat to unlock; logout clears the lock. Works with the console too, keeps existing privacy and moderation checks, and includes English, German and Simplified Chinese texts. Uses existing permissions; no new config keys.
- Fixed: private-message aliases (tell, w, whisper, m, pm, dm) are omitted from Core command registration when already occupied, including aliases of vanilla commands. Core still handles localized alias collisions.

## 1.2.0 (unreleased)

- New: emoji codes. `:heart:`, `:star:`, `:smile:`, `:check:`, `:skull:`, `:coffee:` and more turn into ❤ ★ ☺ ✔ ☠ ☕ in channel messages and private messages (any letter case; unknown codes and normal colons stay as written). `/vchat emojis` (`/vchat 表情`) lists every code. Codes and symbols are set in `config.yml` under `emojis.codes`, `emojis.enabled` switches them off; permission `vystormchat.emoji` (default: everyone). Filters and anti-spam see the message as typed.

## 1.1.0 (unreleased)

Needs Vystorm Core 0.45.0.

- Simplified Chinese (zh_cn) translation.
- All ten commands (`/vchat`, `/channel`, `/msg`, `/reply`, `/ignore`, `/socialspy`, `/mute`, `/unmute`, `/clearchat`, `/slowmode`) now run through Vystorm Core's command system: subcommands, usage, help and tab completion in the player's language (`/channel liste`, `/频道 列表`, `/slowmode aus`), generated `help` per command and entries in `/vhelp`. New localized root commands from the language files, e.g. `/kanal`, `/nachricht`, `/flüstern`, `/antworten`, `/ignorieren`, `/stumm`, `/langsam` (German) and `/频道`, `/私聊`, `/回复`, `/禁言` (Chinese); Core skips any that another plugin already uses. All previous names and aliases stay. Muted players are also blocked from the localized names of muted commands (`/nachricht` like `/msg`). The commands are no longer declared in plugin.yml; `/vchat help` is now Core's generated help.

## 1.0.0 (2026-10-30)

- First release.
- Channels from `config.yml`: global, local (radius), world, trade, staff, party, city, faction and any number of own channels. Each has a format, tag, colour, read and write permission, world list, radius, cooldown, optional Vault cost, show/hide, opt-in join, a quick prefix (`!hello` goes to global) and own commands (`/g`, `/l`, `/sc` ...). Scope `region` chats with everyone in the same Vystorm Core region (city, estate, arena ...).
- Core integration instead of competition: Core's `/vtrade` runs through the trade channel (`core-trade: true`) with the same format, filters, mutes and hidden-channel setting; the party channel hands messages to Vystorm Parties' `/partychat`, so `/pc` and the channel are one chat; city and faction channels find members through VystormCityClaim and Vystorm-Factions (guarded, optional). Messages are tagged for the chat tabs of the Vystorm client mod.
- Private messages: `/msg`, `/r`, `/ignore` (with menu), `/socialspy`, "allow private messages" setting, hidden players count as offline, sound for the receiver.
- Formats in MiniMessage with placeholders, permission-based name formats, Vault prefix/suffix/group, PlaceholderAPI (results inserted safely). Hover card on every name (rank, world, play time, party, city, faction) with click to message; timestamp hover on request.
- `[item]`, `[inv]` and `[pos]` showcase: item tooltip, read-only inventory view, clickable coordinates (Core chat actions).
- Mentions: `@Name` and plain names, highlighted for the mentioned player, sound and action bar (each player can switch the sound off), `@everyone`/`@here` with permission, ping cooldown.
- Player formatting only with permissions (colour, hex, decorations, magic, gradient, `&` codes); never clicks, hovers or other tags.
- Moderation: anti-spam (repeat, flood, caps, repeated letters), word filter (whole words, look-alikes, spaced letters, regex; censor or block), link filter with allowed domains (also bare domains and IPs), mutes with time and reason (also for offline players, blocks configured commands, checked before party chat), `/clearchat`, `/slowmode` per channel or for all, staff notes, rotating chat log with daily files, size limit and clean-up.
- Menus: chat menu in `/vmenu` (Community) and `/channel` with channels, private message dialog, reply, ignore list, settings, social spy, moderation page with clear chat, slow mode and mutes; entries in Core's player menu (message, ignore, mute).
- Personal settings in `/vsettings` → Chat: mention sound, private message sound, timestamps, allow private messages, social spy and one show/hide toggle per channel.
- Built on Vystorm Core: language files (`lang/en.yml`, `lang/de.yml`), menus, dialogs, settings, config editor (`/vadmin config`), task groups, atomic files, regions, party register and chat actions.
- Never blocks the server thread: chat runs on Paper's async chat thread with snapshots of player positions and groups; file writes run on background threads; payments and item copies hop to the server thread with a time limit.

### Fixed
- `moderation.notify-staff: false` is respected: staff no longer get filter notes when it is switched off.
- "Reply" in the chat menu after the console wrote to you now answers the console instead of "Console is not online".
- Chat menu shows the private message, reply and ignore buttons only to players with `vystormchat.msg` / `vystormchat.ignore`.
- A chat error no longer sends the message as normal global chat: staff, party, faction and other channel messages stay private; the sender gets an error note.
- Channel messages no longer reach DiscordSRV and other chat plugins: new `relay.only-channels` (default `[global]`) decides which channels stay in the server chat, all others are delivered by Vystorm Chat itself.
- PlaceholderAPI results are inserted as plain text, so player-chosen text (nicknames ...) cannot add clicks, hovers or colours; admins list trusted placeholders in `placeholderapi-trusted`.
- `/mute PLAYER 30min` (a mistyped time) is rejected with a usage note instead of muting permanently; only no time or `perm` mutes until unmuted. The mute dialog accepts `perm` too.
- An unreadable `mutes.yml` was moved aside, but all mute changes afterwards were saved into a new `mutes.yml` (and over the broken file itself when moving it failed). Mutes are now not saved until the next restart, and the console says how to restore the file. A broken player profile that cannot be moved aside is no longer overwritten with defaults.
