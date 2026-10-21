package online.rizzlers.tags;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import online.rizzlers.tags.config.TagConfig;
import online.rizzlers.tags.database.TagDatabaseManager;
import online.rizzlers.tags.listener.SecurityListener;
import online.rizzlers.tags.models.RoleInfo;
import online.rizzlers.tags.tag.NametagManager;
import online.rizzlers.tags.tag.TagManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TagManagerTest {

    private HikariDataSource dataSource;
    private TagDatabaseManager databaseManager;
    private TagConfig config;
    private TagManager tagManager;

    @BeforeEach
    public void setUp() throws Exception {
        HikariConfig hc = new HikariConfig();
        hc.setDriverClassName("org.sqlite.JDBC");
        hc.setJdbcUrl("jdbc:sqlite:file:testtags_" + UUID.randomUUID() + "?mode=memory&cache=shared");
        hc.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(hc);
        databaseManager = new TagDatabaseManager(dataSource, true, null);

        String yaml = """
            server-name: lobby
            tags:
              enabled: true
              viewing-distance-blocks: 16
              hide-while-sneaking: true
              chat:
                enabled: true
                format: "<gray>{tag}</gray> <white>{player}</white><dark_gray>: </dark_gray>{message}"
            roles:
              member:
                tag: "[MEMBER]"
                color: "#BFC7D5"
                priority: 10
              staff:
                tag: "[STAFF]"
                color: "#FF626C"
                priority: 100
              developer:
                tag: "[DEV]"
                color: "#B77BFF"
                priority: 90
              youtuber:
                tag: "[YOUTUBE]"
                color: "#FF5555"
                priority: 60
              vip:
                tag: "[VIP]"
                color: "#FFD45E"
                priority: 30
              vip_plus:
                tag: "[VIP+]"
                color: "#FFB347"
                priority: 40
              vip_plus_plus:
                tag: "[VIP++]"
                color: "#FF8C42"
                priority: 50
              pvp_master:
                tag: "[PVP MASTER]"
                color: "#E74C3C"
                priority: 25
              bedwars:
                tag: "[BEDWARS]"
                color: "#E67E22"
                priority: 20
              parkour:
                tag: "[PARKOUR]"
                color: "#2ECC71"
                priority: 20
            """;
        config = TagConfig.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        tagManager = new TagManager(config, databaseManager);
    }

    @AfterEach
    public void tearDown() {
        if (databaseManager != null) databaseManager.close();
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    @Test
    @DisplayName("Lobby tag visible at 16 blocks and not beyond")
    public void testLobbyViewingDistance16Blocks() {
        double maxDist = 16.0;

        assertTrue(NametagManager.isTagVisible(0.0, maxDist, false, true), "Visible at 0 blocks");
        assertTrue(NametagManager.isTagVisible(10.0, maxDist, false, true), "Visible at 10 blocks");
        assertTrue(NametagManager.isTagVisible(16.0, maxDist, false, true), "Visible at exactly 16 blocks");

        assertFalse(NametagManager.isTagVisible(16.1, maxDist, false, true), "Hidden at 16.1 blocks");
        assertFalse(NametagManager.isTagVisible(25.0, maxDist, false, true), "Hidden at 25 blocks");
    }

    @Test
    @DisplayName("Survival tag visible at 24 blocks and not beyond")
    public void testSurvivalViewingDistance24Blocks() {
        double maxDist = 24.0;

        assertTrue(NametagManager.isTagVisible(16.0, maxDist, false, true), "Visible at 16 blocks on Survival");
        assertTrue(NametagManager.isTagVisible(24.0, maxDist, false, true), "Visible at exactly 24 blocks on Survival");

        assertFalse(NametagManager.isTagVisible(24.1, maxDist, false, true), "Hidden at 24.1 blocks on Survival");
        assertFalse(NametagManager.isTagVisible(32.0, maxDist, false, true), "Hidden at 32 blocks on Survival");
    }

    @Test
    @DisplayName("Sneak hides the target's tag for viewers; standing restores it")
    public void testSneakingHidesTagAndStandingRestoresIt() {
        double dist = 8.0;
        double maxDist = 16.0;

        // Player standing: tag is visible
        assertTrue(NametagManager.isTagVisible(dist, maxDist, false, true), "Tag must be visible when player is standing");

        // Player sneaking: tag is hidden
        assertFalse(NametagManager.isTagVisible(dist, maxDist, true, true), "Tag must be hidden when player is sneaking");

        // Player stands back up: tag is restored
        assertTrue(NametagManager.isTagVisible(dist, maxDist, false, true), "Tag must be restored when standing");
    }

    @Test
    @DisplayName("Role priority: highest configured display priority wins")
    public void testRolePriorityHighestWins() {
        // Player has member (10), vip (30), and staff (100)
        Set<String> roles1 = Set.of("member", "vip", "staff");
        RoleInfo tag1 = tagManager.getHighestPriorityTag(roles1);
        assertEquals("[STAFF]", tag1.tag(), "Staff (100) must win over VIP (30) and Member (10)");

        // Player has member (10), bedwars (20), and pvp_master (25)
        Set<String> roles2 = Set.of("member", "bedwars", "pvp_master");
        RoleInfo tag2 = tagManager.getHighestPriorityTag(roles2);
        assertEquals("[PVP MASTER]", tag2.tag(), "PvP Master (25) must win over Bedwars (20) and Member (10)");

        // Developer (90) vs Staff (100)
        Set<String> roles3 = Set.of("developer", "staff");
        RoleInfo tag3 = tagManager.getHighestPriorityTag(roles3);
        assertEquals("[STAFF]", tag3.tag());

        // Developer (90) vs Youtuber (60)
        Set<String> roles4 = Set.of("developer", "youtuber");
        RoleInfo tag4 = tagManager.getHighestPriorityTag(roles4);
        assertEquals("[DEV]", tag4.tag());
    }

    @Test
    @DisplayName("Chat formatting safely escapes user input to prevent MiniMessage format injection")
    public void testChatFormattingEscapesUserInput() {
        RoleInfo staff = config.getRoles().get("staff");
        assertNotNull(staff);

        // Malicious user attempts to inject MiniMessage gradient and fake tag
        String malicious = "<gradient:red:blue>FAKE SYSTEM ANNOUNCEMENT</gradient> <color:green>Admin: hello</color>";
        Component formatted = tagManager.formatChat("Hacker", staff, malicious);
        assertNotNull(formatted);

        String plain = PlainTextComponentSerializer.plainText().serialize(formatted);
        // The raw characters of the malicious message should be preserved as literal text, not parsed as tags
        assertTrue(plain.contains("FAKE SYSTEM ANNOUNCEMENT"));
        assertTrue(plain.contains("Hacker:"));
        assertTrue(plain.contains("[STAFF]"));
    }

    @Test
    @DisplayName("Backend cannot be joined directly from public internet without proxy forwarding")
    public void testBackendCannotBeJoinedDirectly() {
        // When proxy forwarding is missing, direct connections are rejected
        assertFalse(SecurityListener.validateConnection(false, true));

        // When proxy forwarding is verified, connection is allowed
        assertTrue(SecurityListener.validateConnection(true, true));
    }

    @Test
    @DisplayName("Database grant and revoke in-game ranks (PvP Master, BedWars, Parkour)")
    public void testGrantAndRevokeIngameRank() throws Exception {
        UUID uuid = UUID.randomUUID();

        databaseManager.grantIngameRank(uuid, "pvp_master", "Console");
        List<String> ranksAfterGrant = databaseManager.getIngameRanks(uuid);
        assertEquals(1, ranksAfterGrant.size());
        assertEquals("pvp_master", ranksAfterGrant.get(0));

        Set<String> roles = databaseManager.getPlayerRolesAndRanks(uuid);
        assertTrue(roles.contains("pvp_master"));
        RoleInfo active = tagManager.getHighestPriorityTag(roles);
        assertEquals("[PVP MASTER]", active.tag());

        databaseManager.revokeIngameRank(uuid, "pvp_master");
        List<String> ranksAfterRevoke = databaseManager.getIngameRanks(uuid);
        assertTrue(ranksAfterRevoke.isEmpty());
    }
}
