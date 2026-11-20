package at.esoren.vystormchat;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.schedule.TaskGroup;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Vystorm Chat: channels (global, local, world, staff, trade, party, city, faction, region and own ones), private
 * messages with reply, ignore and social spy, MiniMessage formats with hover cards, item/inventory/position
 * showcase, clickable links, mentions, and moderation (anti-spam, word and link filter, mutes, clear chat, slow
 * mode, rotating chat log). Built on Vystorm Core: language files, menus, settings, config editor, task groups,
 * atomic files, regions and the trade channel.
 */
public final class ChatPlugin extends JavaPlugin {
    private static ChatPlugin instance;

    /** The Core hub; set first thing in onEnable. */
    private API core;
    private Lang lang;
    private TaskGroup tasks;
    private Hooks hooks;
    private States states;
    private ProfileStore profiles;
    private MuteStore mutes;
    private ChatLog chatLog;
    private ChatService service;
    private PrivateMessages messages;
    private Showcase showcase;
    private ChatMenu menu;
    private ChatPanels panels;
    private Reports reports;
    private ExecutorService io;
    private final Set<String> aliasCommands = new HashSet<>();
    private volatile boolean enabled;

    static ChatPlugin instance() { return instance; }

    /** Minimum Vystorm Core: placeholders through api.placeholders(). */
    static final String MIN_CORE = "0.65.0";

    /** The Core hub, for the classes of this plugin that hold the plugin. */
    API core() { return core; }

    @Override
    public void onEnable() {
        if (!CoreApi.atLeast(MIN_CORE)) {
            getLogger().severe("Vystorm Core " + MIN_CORE + " or newer is required; please update Core first.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = CoreApi.api().orElseThrow();

        instance = this;
        if (!core.compat().server().hasDialogs()) getLogger().info("Native dialogs unavailable; chat prompts are used for messages, ignores and moderation.");
        saveDefaultConfig();
        lang = core.lang().register(this);
        registerTutorial();
        tasks = core.tasks().of(this, "chat");
        io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VystormChat-io");
            thread.setDaemon(true);
            return thread;
        });
        hooks = new Hooks(this, core);
        states = new States(hooks, core);
        profiles = new ProfileStore(getDataFolder().toPath().resolve("players"), getLogger(), core.data().files());
        mutes = new MuteStore(getDataFolder().toPath().resolve("mutes.yml"), getLogger(), io, core.data().files());
        mutes.load();
        chatLog = new ChatLog(getDataFolder().toPath().resolve("logs"), getLogger(), ZoneId.systemDefault());
        service = new ChatService(this);
        messages = new PrivateMessages(this);
        showcase = new Showcase();
        menu = new ChatMenu(this);
        reports = new Reports(this, new ReportStore(getDataFolder().toPath().resolve("reports.yml"), getLogger(), core.data().files()));
        announcements = new Announcements(this, getDataFolder().toPath().resolve("announcements.yml"));
        load();
        chatLog.start();
        enabled = true;

        var manager = getServer().getPluginManager();
        manager.registerEvents(new ChatListener(this), this);
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent", false, getClassLoader());
            manager.registerEvents(new PaperChatListener(this), this);
        } catch (ClassNotFoundException spigot) {
            manager.registerEvents(new BukkitChatListener(this), this);
        }
        manager.registerEvents(new Menu.Clicks(this, core.compat().inventory()), this);
        bubbles = new SpeechBubbles(this);
        manager.registerEvents(bubbles, this);
        manager.registerEvents(new Showcase.Clicks(core.compat().inventory()), this);
        TradeBridge.register(this);
        registerCommands();
        registerAliases();

        for (Player player : getServer().getOnlinePlayers()) {
            profiles.get(player.getUniqueId());
            states.join(player);
        }
        tasks.timer(10L, 10L, () -> states.fast(getServer().getOnlinePlayers()));
        tasks.timer(40L, 100L, () -> states.slow(getServer().getOnlinePlayers()));
        tasks.timer(100L, 100L, profiles::flush);
        tasks.timer(1200L, 1200L, showcase::cleanup);
        tasks.timer(400L, 400L, announcements::tick);

        registerMenu();
        registerSettings();
        registerPlayerMenu();
        registerConfigEditor();
        declareSwitches(core.settings().config(), this, lang);
        try { registerPlaceholders(core.placeholders()); }
        catch (RuntimeException failure) { getLogger().log(Level.WARNING, "Placeholders not registered", failure); }
        getLogger().info("Vystorm Chat " + getDescription().getVersion() + ": " + service.settings().channels.size() + " channels");
    }

    @Override
    public void onDisable() {
        enabled = false;
        if (panels != null) { panels.close(); panels = null; }
        if (bubbles != null) bubbles.removeAll();
        if (tasks != null) tasks.close();
        if (profiles != null) profiles.close();
        if (mutes != null) mutes.saveNow();
        if (io != null) {
            io.shutdown();
            try { io.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (chatLog != null) chatLog.close();
        if (showcase != null) showcase.clear();
    }

    /** The YAML error of config.yml found by the last load ("line L, column C: ..."), null when it parsed. */
    private String configProblem;
    private boolean configLoaded;

    String configProblem() { return configProblem; }

    /**
     * Reads config.yml for start and reload. A file with a YAML error is never saved over (Bukkit hands back an empty
     * config, and saving the keyword defaults wrote a file with only those three keys, 1.17.1): the file stays
     * byte-identical, the error is logged once and false is returned. At start getConfig() then holds the jar defaults.
     */
    boolean readConfig() {
        configProblem = YamlCheck.problem(new java.io.File(getDataFolder(), "config.yml"));
        if (configProblem != null) {
            getLogger().severe("config.yml has a YAML error at " + configProblem + ". The file was left unchanged; "
                    + (configLoaded ? "the last working settings stay in use." : "the default settings are used.") + " Fix it and run /vchat reload.");
            return false;
        }
        reloadConfig();
        if (Settings.addKeywordDefaults(getConfig())) saveConfig();
        return true;
    }

    /** (Re)reads config.yml and the language files. */
    void load() {
        if (!readConfig() && configLoaded) return; // a broken config.yml on reload: keep the last good settings
        configLoaded = true;
        if (lang != null) lang.reload();
        Settings settings = new Settings(getConfig(), getLogger());
        settings.dropMissingForwards(command -> core.compat().platform().getCommandMap().getCommand(command) != null, getLogger());
        hooks.refresh();
        states.regions(settings.regionLookup);
        service.configure(settings);
        mutes.enabled(settings.mutesEnabled);
        chatLog.configure(settings.logEnabled, settings.logMaxMegabytes, settings.logKeepDays);
    }

    /** The tutorial in /vmenu unless tutorial.enabled is false; read once, because Core lists tutorials when a plugin starts. */
    void registerTutorial() {
        if (!getConfig().getBoolean(Settings.Feature.TUTORIAL.path, true)) return;
        try { core.registerTutorial(this); } catch (RuntimeException noTutorial) { getLogger().fine("Tutorial not registered: " + noTutorial); }
    }

    /** Every switch in /vadmin features with its one-line description in the admin's language. */
    static void declareSwitches(Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface config, org.bukkit.plugin.Plugin plugin, Lang lang) {
        for (Settings.Feature feature : Settings.Feature.values()) {
            config.feature(plugin, feature.path, lang.ref(feature.label()), lang.ref(feature.description()));
            if (feature.needsRestart()) config.activation(plugin, feature.path, Vystorm.vystorm_core._uiMethods.settings.SettingPolicy.Activation.RESTART);
        }
    }

    /** The command trees, registered once; commands of switched-off features stay out of help and tab completion (asked live). */
    private void registerCommands() {
        for (var spec : Commands.specs(this)) core.command().register(this, lang(), spec);
    }

    /** The namespace {@code chat}; every value reads the immutable snapshots of the chat thread, so all are async-safe. */
    void registerPlaceholders(Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface registry) {
        ChatPlaceholders.register(registry, this, new ChatPlaceholders.Source() {
            @Override public Settings settings() { return service == null ? null : service.settings(); }
            @Override public Profile profile(java.util.UUID player) { return profiles == null ? null : profiles.cached(player); }
            @Override public PlayerState state(java.util.UUID player) { return states == null ? null : states.get(player); }
            @Override public MuteStore.Mute mute(java.util.UUID player, long now) { return mutes == null ? null : mutes.active(player, now); }
            @Override public java.util.UUID partner(java.util.UUID player) { return messages == null ? null : messages.partner(player); }
        }, key -> lang == null ? key : lang.ref(key));
    }

    /**
     * Reload command: config, language, new channel commands, settings page. Help and tab completion ask the switches
     * live; the command list is sent again on every reload because new channel aliases may have been added.
     */
    void reload() {
        load();
        registerAliases();
        registerSettings();
        try { core.command().refresh(); }
        catch (RuntimeException failure) { getLogger().log(Level.WARNING, "Command list not sent again", failure); }
    }

    // ------------------------------------------------------------------ accessors

    Lang lang() { return lang; }

    TaskGroup tasks() { return tasks; }

    Hooks hooks() { return hooks; }

    States states() { return states; }

    ProfileStore profiles() { return profiles; }

    MuteStore mutes() { return mutes; }

    ChatLog chatLog() { return chatLog; }

    ChatService service() { return service; }

    PrivateMessages messages() { return messages; }

    Showcase showcase() { return showcase; }

    ChatMenu menu() { return menu; }
    boolean openPanel(Player player, String name, String arg) {
        return panels != null && panels.open(player, name, arg);
    }
    void forgetPanels(java.util.UUID player) { if (panels != null) panels.forget(player); }
    Reports reports() { return reports; }
    private Announcements announcements;
    Announcements announcements() { return announcements; }

    /**
     * Runs {@code work} on the server thread and waits for it (at most 3 seconds). On the server thread it runs
     * directly. Returns {@code fallback} when the server is stopping or too slow.
     */
    <T> T onMain(Supplier<T> work, T fallback) {
        if (Bukkit.isPrimaryThread()) return work.get();
        if (!enabled) return fallback;
        try {
            Future<T> future = getServer().getScheduler().callSyncMethod(this, work::get);
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception failed) {
            getLogger().log(Level.FINE, "Server thread did not answer in time", failed);
            return fallback;
        }
    }

    // ------------------------------------------------------------------ feature switches

    /** Whether the feature is switched off in config.yml (read on every call, so a reload applies at once). */
    boolean off(Settings.Feature feature) { return !service.settings().enabled(feature); }

    /** True when the feature is switched off; the sender is told so. */
    boolean refuse(CommandSender to, Settings.Feature feature) {
        if (!off(feature)) return false;
        lang.send(to, "feature-off");
        return true;
    }

    // ------------------------------------------------------------------ actions shared by commands and menus

    /** The channel the player writes in: their focus if still allowed, else the default channel. */
    Channel focus(Player player) {
        Settings settings = service.settings();
        Profile profile = profiles.cached(player.getUniqueId());
        Channel channel = profile == null ? null : settings.channel(profile.focus());
        if (channel != null && channel.writable(player::hasPermission)) return channel;
        return settings.defaultChannel();
    }

    void focus(Player player, Channel channel) {
        if (!channel.writable(player::hasPermission)) { lang.send(player, "channel-no-write", "channel", channel.displayName()); return; }
        Profile profile = profiles.get(player.getUniqueId());
        profile.focus(channel.id());
        if (!profile.listening(channel)) profile.listen(channel, true);
        lang.send(player, "channel-focused", "channel", channel.displayName());
        settingsChanged(player);
    }

    void listen(Player player, Channel channel, boolean on) {
        if (refuse(player, Settings.Feature.CHANNEL_CHOICE)) return;
        if (!channel.leavable()) { lang.send(player, "channel-not-leavable", "channel", channel.displayName()); return; }
        if (on && !channel.readable(player::hasPermission)) { lang.send(player, "channel-no-read", "channel", channel.displayName()); return; }
        profiles.get(player.getUniqueId()).listen(channel, on);
        lang.send(player, on ? "channel-shown" : "channel-hidden", "channel", channel.displayName());
        settingsChanged(player);
    }

    /** Sends one message into a channel from a command (also /vtrade); server thread. */
    void sendTo(Player player, Channel channel, String text, ChatService.Source source) {
        ChatService.Outcome outcome = service.prepare(player, channel, text, source, new ArrayList<>(getServer().getOnlinePlayers()));
        switch (outcome) {
            case ChatService.Denied denied -> lang.send(player, denied.key(), denied.placeholders());
            case ChatService.Ignored ignored -> { }
            case ChatService.Forward forward -> player.performCommand(forward.command() + " " + forward.text());
            case ChatService.Deliver deliver -> service.deliver(deliver.delivery());
        }
    }

    void openSettings(Player player) {
        if (refuse(player, Settings.Feature.CHAT_MENU)) return;
        try {
            if (!core.settings().openSection(player, "vystormchat:chat")) lang.send(player, "settings-unavailable");
        } catch (RuntimeException unavailable) {
            lang.send(player, "settings-unavailable");
        }
    }

    void toggleSpy(Player player) {
        if (refuse(player, Settings.Feature.SOCIAL_SPY)) return;
        if (!player.hasPermission("vystormchat.spy")) { lang.send(player, "no-permission"); return; }
        Profile profile = profiles.get(player.getUniqueId());
        profile.spy(!profile.spy());
        lang.send(player, profile.spy() ? "spy-on" : "spy-off");
        settingsChanged(player);
    }

    /** {@code /ignore NAME}: toggles. */
    void ignoreCommand(Player player, String name) {
        if (refuse(player, Settings.Feature.IGNORE)) return;
        OfflinePlayer target = offline(name);
        if (target == null) { lang.send(player, "player-unknown", "player", name); return; }
        toggleIgnore(player, target);
    }

    void toggleIgnore(Player player, OfflinePlayer target) {
        if (refuse(player, Settings.Feature.IGNORE)) return;
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        if (target.getUniqueId().equals(player.getUniqueId())) { lang.send(player, "ignore-self"); return; }
        Profile profile = profiles.get(player.getUniqueId());
        if (!profile.ignores(target.getUniqueId()) && target.getPlayer() != null && target.getPlayer().hasPermission("vystormchat.ignore.exempt")) {
            lang.send(player, "ignore-exempt", "player", name);
            return;
        }
        boolean now = profile.toggleIgnore(target.getUniqueId());
        lang.send(player, now ? "ignore-on" : "ignore-off", "player", name);
    }

    /** A player by name: online first, then players who were on this server before (no web lookup). */
    OfflinePlayer offline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = core.compat().platform().getOfflinePlayerIfCached(name);
        return cached != null && (cached.hasPlayedBefore() || cached.isOnline()) ? cached : null;
    }

    void clearChat(CommandSender by) {
        if (refuse(by, Settings.Feature.CLEAR_CHAT)) return;
        if (!by.hasPermission("vystormchat.clearchat")) { lang.send(by, "no-permission"); return; }
        int lines = service.settings().clearLines;
        Component blank = Component.text(" ");
        for (Player player : getServer().getOnlinePlayers()) {
            if (!player.hasPermission("vystormchat.clearchat.exempt")) for (int i = 0; i < lines; i++) core.compat().platform().sendMessage(player, blank);
            lang.send(player, "chat-cleared", "player", by.getName());
        }
        lang.send(by instanceof Player ? getServer().getConsoleSender() : by, "chat-cleared", "player", by.getName());
        chatLog.log("moderation", by.getName() + " cleared the chat");
    }

    void slowMode(CommandSender by, Channel channel, int seconds) {
        if (refuse(by, Settings.Feature.SLOW_MODE)) return;
        if (!by.hasPermission("vystormchat.slowmode")) { lang.send(by, "no-permission"); return; }
        service.slowMode(channel == null ? null : channel.id(), seconds);
        String where = channel == null ? lang.plain(by, "all-channels") : channel.displayName();
        for (Player player : getServer().getOnlinePlayers()) {
            if (seconds > 0) lang.send(player, "slow-mode-on", "time", Durations.format(seconds * 1000L), "channel", channel == null ? lang.plain(player, "all-channels") : where);
            else lang.send(player, "slow-mode-off", "channel", channel == null ? lang.plain(player, "all-channels") : where);
        }
        if (!(by instanceof Player)) lang.send(by, seconds > 0 ? "slow-mode-on" : "slow-mode-off", "time", Durations.format(seconds * 1000L), "channel", where);
        chatLog.log("moderation", by.getName() + " set slow mode " + seconds + "s (" + where + ")");
    }

    /** @param millis 0 = until unmuted */
    void mute(CommandSender by, OfflinePlayer target, long millis, String reason) {
        if (!by.hasPermission("vystormchat.mute")) { lang.send(by, "no-permission"); return; }
        if (refuse(by, Settings.Feature.MUTES)) return;
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        Player online = target.getPlayer();
        if (online != null && online.hasPermission("vystormchat.mute.exempt") && !(by instanceof org.bukkit.command.ConsoleCommandSender)) {
            lang.send(by, "mute-exempt", "player", name);
            return;
        }
        long now = System.currentTimeMillis();
        MuteStore.Mute mute = new MuteStore.Mute(target.getUniqueId(), name, now, millis <= 0 ? 0 : now + millis, reason == null ? "" : reason, by.getName());
        if (!mutes.changeDurably(target.getUniqueId(), mute)) { lang.send(by, "reports.result-unavailable"); return; }
        String time = millis <= 0 ? lang.plain(by, "permanent") : Durations.format(millis);
        String shownReason = mute.reason().isBlank() ? "-" : mute.reason();
        lang.send(by, "mute-done", "player", name, "time", time, "reason", shownReason);
        if (online != null) {
            ChatService.Denied denied = service.muted(mute, now);
            lang.send(online, denied.key(), denied.placeholders());
        }
        for (Player staff : getServer().getOnlinePlayers())
            if (staff != by && staff.hasPermission("vystormchat.notify"))
                lang.send(staff, "staff-muted", "player", name, "by", by.getName(), "time", millis <= 0 ? lang.plain(staff, "permanent") : Durations.format(millis), "reason", shownReason);
        chatLog.log("moderation", by.getName() + " muted " + name + " (" + time + "): " + shownReason);
    }

    void unmute(CommandSender by, OfflinePlayer target) {
        if (!by.hasPermission("vystormchat.mute")) { lang.send(by, "no-permission"); return; }
        if (refuse(by, Settings.Feature.MUTES)) return;
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        if (mutes.active(target.getUniqueId(), System.currentTimeMillis()) == null) { lang.send(by, "not-muted", "player", name); return; }
        if (!mutes.changeDurably(target.getUniqueId(), null)) { lang.send(by, "reports.result-unavailable"); return; }
        lang.send(by, "unmute-done", "player", name);
        Player online = target.getPlayer();
        if (online != null && online != by) lang.send(online, "unmuted");
        chatLog.log("moderation", by.getName() + " unmuted " + name);
    }

    // ------------------------------------------------------------------ registrations with Core

    /** Channel commands from the config (/g, /l ...), added to the server's command map; existing commands win. */
    private void registerAliases() {
        var map = core.compat().platform().getCommandMap();
        for (Channel channel : service.settings().channels.values()) {
            for (String alias : channel.aliases()) {
                if (aliasCommands.contains(alias)) continue;
                Command command = new Command(alias, "Write in a chat channel", "/" + alias + " [message]", List.of()) {
                    @Override
                    public boolean execute(CommandSender sender, String label, String[] args) {
                        aliasCommand(sender, alias, args);
                        return true;
                    }
                };
                command.setPermission("vystormchat.use");
                if (map.register(getName().toLowerCase(Locale.ROOT), command)) aliasCommands.add(alias);
                else {
                    aliasCommands.add(alias);
                    getLogger().info("/" + alias + " already belongs to another plugin; use /" + getName().toLowerCase(Locale.ROOT) + ":" + alias);
                }
            }
        }
    }

    private void aliasCommand(CommandSender sender, String alias, String[] args) {
        if (!(sender instanceof Player player)) { lang.send(sender, "player-only"); return; }
        if (refuse(player, Settings.Feature.CHAT)) return;
        Channel channel = null;
        for (Channel candidate : service.settings().channels.values()) if (candidate.aliases().contains(alias)) channel = candidate;
        if (channel == null) { lang.send(player, "channel-unknown", "channel", alias); return; }
        if (args.length == 0) focus(player, channel);
        else sendTo(player, channel, String.join(" ", args), ChatService.Source.COMMAND);
    }

    private void registerMenu() {
        try {
            ChatPanels created = new ChatPanels(this);
            created.register();
            panels = created;
            return;
        } catch (RuntimeException failure) {
            getLogger().log(Level.FINE, "Core panels unavailable; using chest menus", failure);
        }
        try {
            core.gui().registerMenu(this, "chat", lang.ref("vmenu-chat"), org.bukkit.Material.WRITABLE_BOOK,
                    player -> !off(Settings.Feature.CHAT_MENU) && player.hasPermission("vystormchat.use"), menu::open,
                    Vystorm.vystorm_core._uiMethods.menu.MenuCategory.COMMUNITY.group(), lang.ref("vmenu-chat-desc"));
        } catch (RuntimeException failure) {
            getLogger().log(Level.FINE, "No Core main menu entry", failure);
        }
    }

    private void registerPlayerMenu() {
        ChatMenuEntries.register(this);
    }

    /** Personal settings in /vsettings: sounds, timestamps, private messages and one toggle per channel. */
    private SpeechBubbles bubbles;
    SpeechBubbles bubbles() { return bubbles; }

    private void registerSettings() {
        try {
            var settingsApi = core.settings();
            // only our own section: the config editor's sections belong to this plugin too and must stay
            if (settingsApi instanceof Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry registry) registry.unregister(this, "chat");
            Settings current = service.settings();
            if (!current.enabled(Settings.Feature.CHAT_MENU)) return; // chat-menu.enabled off: no page in /vsettings
            List<Setting> list = new ArrayList<>();
            if (current.enabled(Settings.Feature.MENTIONS)) list.add(Setting.toggle("mention-sound", lang.ref("settings.mention-sound"), lang.ref("settings.mention-sound-desc"),
                    p -> profiles.get(p.getUniqueId()).mentionSound(), (p, v) -> { profiles.get(p.getUniqueId()).mentionSound(v); return null; }));
            if (current.enabled(Settings.Feature.PRIVATE_MESSAGES)) list.add(Setting.toggle("message-sound", lang.ref("settings.message-sound"), lang.ref("settings.message-sound-desc"),
                    p -> profiles.get(p.getUniqueId()).messageSound(), (p, v) -> { profiles.get(p.getUniqueId()).messageSound(v); return null; }));
            if (bubbles != null && bubbles.enabled()) list.add(Setting.toggle("bubbles", lang.ref("settings.bubbles"), lang.ref("settings.bubbles-desc"),
                    p -> bubbles.wants(p), (p, v) -> { bubbles.setWants(p, v); return null; }));
            if (current.enabled(Settings.Feature.TIMESTAMPS)) list.add(Setting.toggle("timestamps", lang.ref("settings.timestamps"), lang.ref("settings.timestamps-desc"),
                    p -> profiles.get(p.getUniqueId()).timestamps(), (p, v) -> { profiles.get(p.getUniqueId()).timestamps(v); return null; }));
            if (current.enabled(Settings.Feature.PRIVATE_MESSAGES)) list.add(Setting.toggle("allow-messages", lang.ref("settings.allow-messages"), lang.ref("settings.allow-messages-desc"),
                    p -> profiles.get(p.getUniqueId()).allowMessages(), (p, v) -> { profiles.get(p.getUniqueId()).allowMessages(v); return null; }));
            if (current.enabled(Settings.Feature.SOCIAL_SPY)) list.add(Setting.toggle("spy", lang.ref("settings.spy"), lang.ref("settings.spy-desc"),
                    p -> profiles.get(p.getUniqueId()).spy(), (p, v) -> { profiles.get(p.getUniqueId()).spy(v); return null; }).permission("vystormchat.spy"));
            for (Channel channel : service.settings().channels.values()) {
                if (!channel.leavable() || list.size() >= 47 || !current.enabled(Settings.Feature.CHANNEL_CHOICE)) continue;
                // shipped channels have translated labels, own channels show their display name
                String label = lang.has("settings.show." + channel.id()) ? lang.ref("settings.show." + channel.id()) : channel.displayName();
                Setting toggle = Setting.toggle("show-" + channel.id(), label,
                        lang.ref("settings.show-channel-desc"),
                        p -> profiles.get(p.getUniqueId()).listening(channel),
                        (p, v) -> { profiles.get(p.getUniqueId()).listen(channel, v); return null; });
                list.add(channel.readPermission().isEmpty() ? toggle : toggle.permission(channel.readPermission()));
            }
            settingsApi.register(this, SettingsSection.player("chat", lang.ref("settings.title"), lang.ref("settings.desc"), list));
        } catch (RuntimeException failure) {
            getLogger().log(Level.WARNING, "Could not register the chat settings in /vsettings", failure);
        }
    }

    void settingsChanged(Player player) {
        try { core.settings().changed(player); } catch (RuntimeException ignored) { }
    }

    private void registerConfigEditor() {
        core.settings().config().register(this, "config.yml", this::reload);
    }
}
