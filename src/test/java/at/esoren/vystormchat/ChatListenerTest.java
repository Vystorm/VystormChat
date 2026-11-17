package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** A failing pipeline never falls back to the server chat; only relay channels stay in it. */
class ChatListenerTest {
    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "getName" -> "Steve";
            default -> null;
        });
    }

    private static AsyncChatEvent event(Player sender, Audience... others) {
        Set<Audience> viewers = new HashSet<>(Set.of(others));
        viewers.add(sender);
        Component message = Component.text("staff only: the password is ...");
        return new AsyncChatEvent(true, sender, viewers, ChatRenderer.defaultRenderer(), message, message, null);
    }

    @Test
    void failingPipelineCancelsTheMessageInsteadOfBroadcastingIt() {
        Player sender = player();
        AsyncChatEvent event = event(sender, player(), player());
        AtomicReference<Throwable> reported = new AtomicReference<>();
        PaperChatListener.failClosed(event, () -> { throw new IllegalStateException("hook broke"); }, reported::set);
        assertTrue(event.isCancelled(), "a failed channel message must not go out as normal chat");
        assertTrue(event.viewers().isEmpty());
        assertEquals("hook broke", reported.get().getMessage());

        AsyncChatEvent linkage = event(sender, player());
        PaperChatListener.failClosed(linkage, () -> { throw new NoClassDefFoundError("soft dependency"); }, reported::set);
        assertTrue(linkage.isCancelled());
    }

    @Test
    void workingPipelineLeavesTheEventAlone() {
        AsyncChatEvent event = event(player(), player());
        PaperChatListener.failClosed(event, () -> {}, failure -> { throw new AssertionError("no failure expected"); });
        assertFalse(event.isCancelled());
        assertEquals(2, event.viewers().size());
    }

    @Test
    void onlyRelayChannelsStayInTheServerChat() {
        Settings shipped = new Settings(YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")), Logger.getLogger("test"));
        assertTrue(shipped.relayed(shipped.channel("global")));
        for (String id : List.of("staff", "party", "faction", "city", "local", "trade"))
            assertFalse(shipped.relayed(shipped.channel(id)), id + " must not reach DiscordSRV and other chat listeners");

        YamlConfiguration all = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        all.set("relay.only-channels", List.of("*"));
        Settings everything = new Settings(all, Logger.getLogger("test"));
        assertTrue(everything.relayed(everything.channel("staff")));

        YamlConfiguration old = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        old.set("relay", null);
        Settings missing = new Settings(old, Logger.getLogger("test"));
        assertTrue(missing.relayed(missing.channel("global")), "configs from before the key relay global only");
        assertFalse(missing.relayed(missing.channel("staff")));
    }
}
