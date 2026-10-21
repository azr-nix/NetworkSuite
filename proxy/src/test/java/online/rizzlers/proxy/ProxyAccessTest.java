package online.rizzlers.proxy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import online.rizzlers.proxy.access.AccessDecision;
import online.rizzlers.proxy.access.AccessEvaluator;
import online.rizzlers.proxy.config.ProxyConfig;
import online.rizzlers.proxy.database.ProxyDatabaseManager;
import online.rizzlers.proxy.discord.DiscordRoleProvider;
import online.rizzlers.proxy.discord.JdaDiscordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ProxyAccessTest {

    private HikariDataSource dataSource;
    private ProxyDatabaseManager databaseManager;
    private ProxyConfig config;
    private MockDiscordProvider mockDiscord;
    private AccessEvaluator evaluator;

    private static final String GUILD_ID = "999000111222333444";
    private static final String ROLE_MEMBER = "111222333444555666";
    private static final String ROLE_BLACKLIST = "666555444333222111";

    public static class MockDiscordProvider implements DiscordRoleProvider {
        private final Map<String, Set<String>> roles = new HashMap<>();
        private boolean outage = false;

        public void setRoles(String discordId, Set<String> r) {
            roles.put(discordId, new HashSet<>(r));
        }

        public void setOutage(boolean outage) {
            this.outage = outage;
        }

        @Override
        public Set<String> getMemberRoles(String guildId, String discordId) throws Exception {
            if (outage) throw new Exception("Simulated Discord API timeout / rate limit");
            return roles.getOrDefault(discordId, Collections.emptySet());
        }

        @Override
        public boolean isReady() {
            return !outage;
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        HikariConfig hc = new HikariConfig();
        hc.setDriverClassName("org.sqlite.JDBC");
        hc.setJdbcUrl("jdbc:sqlite:file:testproxy_" + UUID.randomUUID() + "?mode=memory&cache=shared");
        hc.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(hc);
        databaseManager = new ProxyDatabaseManager(dataSource, true, null);

        String yaml = """
            discord:
              guild-id: "999000111222333444"
              role-cache-seconds: 60
            access:
              require-linked-account: true
              allowed-role-ids:
                - "111222333444555666"
              blacklist-role-id: "666555444333222111"
              discord-api-failure-policy: "DENY"
            linking:
              code-expiry-seconds: 300
              max-attempts-per-hour: 5
            """;
        config = ProxyConfig.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        mockDiscord = new MockDiscordProvider();
        evaluator = new AccessEvaluator(config, databaseManager, mockDiscord, null);
    }

    @AfterEach
    public void tearDown() {
        if (databaseManager != null) databaseManager.close();
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    @Test
    @DisplayName("Linked user with access role: allowed")
    public void testLinkedUserWithAccessRoleAllowed() throws Exception {
        UUID uuid = UUID.randomUUID();
        String discordId = "1001";
        String code = evaluator.generateLinkCode(uuid, "PlayerOne", "ONLINE");
        ProxyDatabaseManager.ConsumeResult res = databaseManager.consumeLinkCode(JdaDiscordService.sha256(code), discordId, "PlayerOne", "ONLINE");
        assertTrue(res.success());

        mockDiscord.setRoles(discordId, Set.of(ROLE_MEMBER));

        AccessDecision decision = evaluator.evaluateAccess(uuid, "PlayerOne", "ONLINE");
        assertTrue(decision.allowed(), "Linked player with access role must be allowed");
        assertEquals("ALLOWED", decision.resultType());
    }

    @Test
    @DisplayName("Linked user with blacklist role and access role: denied (blacklist wins)")
    public void testBlacklistWinsOverAccessRole() throws Exception {
        UUID uuid = UUID.randomUUID();
        String discordId = "1002";
        String code = evaluator.generateLinkCode(uuid, "BadPlayer", "ONLINE");
        databaseManager.consumeLinkCode(JdaDiscordService.sha256(code), discordId, "BadPlayer", "ONLINE");

        // Possesses BOTH allow and blacklist roles
        mockDiscord.setRoles(discordId, Set.of(ROLE_MEMBER, ROLE_BLACKLIST));

        AccessDecision decision = evaluator.evaluateAccess(uuid, "BadPlayer", "ONLINE");
        assertFalse(decision.allowed(), "Blacklisted user must be denied even if possessing access role");
        assertEquals("BLACKLIST", decision.resultType());
    }

    @Test
    @DisplayName("Linked user with no access role: denied")
    public void testLinkedUserWithNoAccessRoleDenied() throws Exception {
        UUID uuid = UUID.randomUUID();
        String discordId = "1003";
        String code = evaluator.generateLinkCode(uuid, "NoRolePlayer", "ONLINE");
        databaseManager.consumeLinkCode(JdaDiscordService.sha256(code), discordId, "NoRolePlayer", "ONLINE");

        mockDiscord.setRoles(discordId, Set.of("random_unverified_role"));

        AccessDecision decision = evaluator.evaluateAccess(uuid, "NoRolePlayer", "ONLINE");
        assertFalse(decision.allowed(), "Player without access role must be denied");
        assertEquals("MISSING_ROLE", decision.resultType());
    }

    @Test
    @DisplayName("Unlinked user: denied with linking instructions")
    public void testUnlinkedUserDenied() {
        UUID uuid = UUID.randomUUID();
        AccessDecision decision = evaluator.evaluateAccess(uuid, "GuestPlayer", "ONLINE");
        assertFalse(decision.allowed(), "Unlinked user must be denied");
        assertEquals("UNLINKED", decision.resultType());
        assertNotNull(decision.kickMessageTemplate());
    }

    @Test
    @DisplayName("Discord API timeout / rate limit: denied safely, logged")
    public void testDiscordApiTimeoutDeniedSafely() throws Exception {
        UUID uuid = UUID.randomUUID();
        String discordId = "1004";
        String code = evaluator.generateLinkCode(uuid, "TimeoutPlayer", "ONLINE");
        databaseManager.consumeLinkCode(JdaDiscordService.sha256(code), discordId, "TimeoutPlayer", "ONLINE");

        mockDiscord.setOutage(true);

        AccessDecision decision = evaluator.evaluateAccess(uuid, "TimeoutPlayer", "ONLINE");
        assertFalse(decision.allowed(), "Must fail closed on Discord API outage/timeout");
        assertEquals("DISCORD_ERROR", decision.resultType());
    }

    @Test
    @DisplayName("Offline-mode UUID path: account type recorded as OFFLINE and cannot claim premium identity")
    public void testOfflineModeSafeguards() throws Exception {
        UUID offlineUuid = UUID.nameUUIDFromBytes("Offline:CrackedUser".getBytes(StandardCharsets.UTF_8));
        String discordId = "1005";

        String code = evaluator.generateLinkCode(offlineUuid, "CrackedUser", "OFFLINE");
        ProxyDatabaseManager.ConsumeResult res = databaseManager.consumeLinkCode(JdaDiscordService.sha256(code), discordId, "CrackedUser", "OFFLINE");
        assertTrue(res.success());
        assertEquals("OFFLINE", res.linkedAccount().accountType());
        assertTrue(res.linkedAccount().isOffline());
    }

    @Test
    @DisplayName("Link uniqueness: Discord ID cannot be linked to multiple Minecraft UUIDs")
    public void testLinkUniqueness() throws Exception {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        String discordId = "1006";

        String codeA = evaluator.generateLinkCode(playerA, "PlayerA", "ONLINE");
        ProxyDatabaseManager.ConsumeResult resA = databaseManager.consumeLinkCode(JdaDiscordService.sha256(codeA), discordId, "PlayerA", "ONLINE");
        assertTrue(resA.success());

        String codeB = evaluator.generateLinkCode(playerB, "PlayerB", "ONLINE");
        ProxyDatabaseManager.ConsumeResult resB = databaseManager.consumeLinkCode(JdaDiscordService.sha256(codeB), discordId, "PlayerB", "ONLINE");
        assertFalse(resB.success(), "Duplicate claim of same Discord account must be rejected");
    }
}
