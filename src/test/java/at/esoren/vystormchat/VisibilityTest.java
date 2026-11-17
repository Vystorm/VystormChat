package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import at.esoren.vystormchat.Visibility.Reach;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class VisibilityTest {
    private static final UUID OVERWORLD = UUID.randomUUID(), NETHER = UUID.randomUUID();
    private static final Predicate<String> NONE = permission -> false, ALL = permission -> true;

    private static PlayerState at(String name, UUID world, double x, double z) {
        return PlayerState.basic(UUID.randomUUID(), name, world, world == OVERWORLD ? "world" : "world_nether", x, 64, z);
    }

    private static PlayerState grouped(String name, String party, String city, String faction, List<String> regions) {
        return new PlayerState(UUID.randomUUID(), name, null, OVERWORLD, "world", 0, 64, 0, regions, party, "P", city, "C", faction, "F", "", "", "", 0);
    }

    @Test
    void senderAlwaysSeesOwnMessage() {
        Channel staff = TestChannels.one("read-permission: x\nscope: local\nradius: 1");
        PlayerState sender = at("a", OVERWORLD, 0, 0);
        assertEquals(Reach.NORMAL, Visibility.reach(staff, sender, sender, NONE, false, false, false));
    }

    @Test
    void globalNeedsReadPermissionAndListening() {
        Channel channel = TestChannels.one("read-permission: chat.read");
        PlayerState sender = at("a", OVERWORLD, 0, 0), viewer = at("b", NETHER, 5000, 5000);
        assertEquals(Reach.NORMAL, Visibility.reach(channel, sender, viewer, ALL, true, false, false));
        assertEquals(Reach.NONE, Visibility.reach(channel, sender, viewer, NONE, true, false, false));
        assertEquals(Reach.NONE, Visibility.reach(channel, sender, viewer, ALL, false, false, false), "hidden channel");
        assertEquals(Reach.NONE, Visibility.reach(channel, sender, viewer, ALL, true, true, false), "ignored sender");
    }

    @Test
    void localRadiusAndWorld() {
        Channel local = TestChannels.one("scope: local\nradius: 50");
        PlayerState sender = at("a", OVERWORLD, 0, 0);
        assertEquals(Reach.NORMAL, Visibility.reach(local, sender, at("near", OVERWORLD, 30, 40), ALL, true, false, false), "exactly 50 blocks");
        assertEquals(Reach.NONE, Visibility.reach(local, sender, at("far", OVERWORLD, 31, 40), ALL, true, false, false));
        assertEquals(Reach.NONE, Visibility.reach(local, sender, at("nether", NETHER, 0, 0), ALL, true, false, false));
    }

    @Test
    void spyOnlyForSpyChannelsAndNotForIgnored() {
        Channel local = TestChannels.one("scope: local\nradius: 10\nspy: true");
        Channel quiet = TestChannels.one("scope: local\nradius: 10");
        PlayerState sender = at("a", OVERWORLD, 0, 0), far = at("staff", OVERWORLD, 500, 0);
        assertEquals(Reach.SPY, Visibility.reach(local, sender, far, ALL, true, false, true));
        assertEquals(Reach.NONE, Visibility.reach(quiet, sender, far, ALL, true, false, true));
        assertEquals(Reach.NONE, Visibility.reach(local, sender, far, ALL, true, true, true));
        assertEquals(Reach.NORMAL, Visibility.reach(local, sender, at("near", OVERWORLD, 1, 1), ALL, true, false, true), "in range: normal, not spy");
    }

    @Test
    void worldScopeAndWorldList() {
        Channel world = TestChannels.one("scope: world");
        Channel limited = TestChannels.one("worlds: [world]");
        PlayerState sender = at("a", OVERWORLD, 0, 0);
        assertEquals(Reach.NORMAL, Visibility.reach(world, sender, at("b", OVERWORLD, 9999, 0), ALL, true, false, false));
        assertEquals(Reach.NONE, Visibility.reach(world, sender, at("c", NETHER, 0, 0), ALL, true, false, false));
        assertEquals(Reach.NONE, Visibility.reach(limited, sender, at("d", NETHER, 0, 0), ALL, true, false, false));
        assertEquals("channel-wrong-world", Visibility.writeProblem(limited, at("e", NETHER, 0, 0), ALL));
        assertNull(Visibility.writeProblem(limited, sender, ALL));
    }

    @Test
    void groupScopes() {
        Channel party = TestChannels.one("scope: party");
        Channel city = TestChannels.one("scope: city");
        Channel faction = TestChannels.one("scope: faction");
        PlayerState a = grouped("a", "p1", "c1", "f1", List.of()), b = grouped("b", "p1", "c2", "f1", List.of()), loner = grouped("c", null, null, null, List.of());
        assertEquals(Reach.NORMAL, Visibility.reach(party, a, b, ALL, true, false, false));
        assertEquals(Reach.NONE, Visibility.reach(city, a, b, ALL, true, false, false));
        assertEquals(Reach.NORMAL, Visibility.reach(faction, a, b, ALL, true, false, false));
        assertEquals(Reach.NONE, Visibility.reach(party, a, loner, ALL, true, false, false));
        assertEquals("channel-no-party", Visibility.writeProblem(party, loner, ALL));
        assertEquals("channel-no-city", Visibility.writeProblem(city, loner, ALL));
        assertEquals("channel-no-faction", Visibility.writeProblem(faction, loner, ALL));
        Channel forwarded = TestChannels.one("scope: party\nforward-command: partychat");
        assertNull(Visibility.writeProblem(forwarded, loner, ALL), "the forward command checks membership itself");
    }

    @Test
    void regionScopeUsesTheFirstMatchingKind() {
        Channel cities = TestChannels.one("scope: region\nregion-kinds: [city]");
        Channel any = TestChannels.one("scope: region");
        PlayerState sender = grouped("a", null, null, null, List.of("arena:pit", "city:rome"));
        PlayerState sameCity = grouped("b", null, null, null, List.of("city:rome"));
        PlayerState sameArena = grouped("c", null, null, null, List.of("arena:pit"));
        PlayerState nowhere = grouped("d", null, null, null, List.of());
        assertEquals(Reach.NORMAL, Visibility.reach(cities, sender, sameCity, ALL, true, false, false));
        assertEquals(Reach.NONE, Visibility.reach(cities, sender, sameArena, ALL, true, false, false));
        assertEquals(Reach.NORMAL, Visibility.reach(any, sender, sameArena, ALL, true, false, false));
        assertEquals(Reach.NONE, Visibility.reach(any, sender, sameCity, ALL, true, false, false), "any kind: the top region (arena) counts");
        assertEquals("channel-no-region", Visibility.writeProblem(cities, nowhere, ALL));
        assertEquals(Set.of(), cities.worlds());
    }

    @Test
    void writePermission() {
        Channel staff = TestChannels.one("write-permission: staff");
        PlayerState sender = at("a", OVERWORLD, 0, 0);
        assertEquals("channel-no-write", Visibility.writeProblem(staff, sender, NONE));
        assertNull(Visibility.writeProblem(staff, sender, Set.of("staff")::contains));
    }
}
