## Spigot/CraftBukkit release 1.8.0

Core 0.64.0+ is required. 101 automated checks passed (97 regular and 4 Paper 1.20.6 checks). Two actual protocol clients on official Spigot 26.3 and CraftBukkit 26.3 verified exactly-once public delivery, staff-channel privacy from a non-operator, switching back to global, and readable console logs. These are targeted checks, not exhaustive third-party chat relay acceptance.

Public messages remain visible to configured relays; private channels use cancelled events. Native dialogs fall back to Core chat prompts. Existing saved data and seven language bundles are retained.

## 1.9.0 feature validation

Exact release JAR SHA-256: `6e228d8fc712099571dfd192397b502412cc3e0bf0a049dd529fe12a136de013`. Automated checks: test: 107 tests, 0 failures, 0 skipped, test1206: 4 tests, 0 failures, 0 skipped. Older Paper API compilation/compatibility tasks also passed.

Connected-player acceptance on official Spigot 26.3 and CraftBukkit 26.3, including a complete server restart: Actual report command, nonstaff case-list denial, inventory note/resolve and portable prompts, confirmed 60-second mute, real client chat blocked before and after restart, real elapsed expiry and observer receipt after expiry, durable case history. No clock manipulation.

All seven new language namespaces passed key and placeholder checks. Independent review found no unresolved concrete defects. Combined acceptance harness and evidence are secured in `vystorm-chat/integration/free-features-2026-11`. These are targeted tests of the new feature, not exhaustive replay of every existing plugin feature or third-party integration.
