package online.rizzlers.proxy.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import online.rizzlers.proxy.config.ProxyConfig;
import online.rizzlers.proxy.models.AuditEvent;
import online.rizzlers.proxy.models.LinkCode;
import online.rizzlers.proxy.models.LinkedAccount;
import online.rizzlers.proxy.models.RoleRule;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public class ProxyDatabaseManager implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final boolean isSqlite;
    private final Logger logger;

    public ProxyDatabaseManager(ProxyConfig config, File dataFolder, Logger logger) throws SQLException {
        this.logger = logger;
        this.isSqlite = "sqlite".equalsIgnoreCase(config.getDbDriver());

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("RizzlersProxyPool");

        if (isSqlite) {
            String sqlitePath;
            if (":memory:".equals(config.getSqliteFile())) {
                sqlitePath = ":memory:";
            } else {
                File dbFile = new File(dataFolder, config.getSqliteFile());
                if (!dbFile.getParentFile().exists()) {
                    dbFile.getParentFile().mkdirs();
                }
                sqlitePath = dbFile.getAbsolutePath();
            }
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + sqlitePath);
            hikariConfig.setMaximumPoolSize(1);
            hikariConfig.setConnectionTimeout(30000);
        } else {
            hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");
            String url = String.format("jdbc:mariadb://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                    config.getDbHost(), config.getDbPort(), config.getDbName());
            hikariConfig.setJdbcUrl(url);
            hikariConfig.setUsername(config.getDbUsername());
            hikariConfig.setPassword(config.getDbPassword());
            hikariConfig.setMaximumPoolSize(config.getDbPoolSize());
            hikariConfig.setMinimumIdle(2);
            hikariConfig.setConnectionTimeout(10000);
            hikariConfig.setLeakDetectionThreshold(15000);
        }

        this.dataSource = new HikariDataSource(hikariConfig);
        initTables();
    }

    public ProxyDatabaseManager(HikariDataSource dataSource, boolean isSqlite, Logger logger) throws SQLException {
        this.dataSource = dataSource;
        this.isSqlite = isSqlite;
        this.logger = logger;
        initTables();
    }

    private void initTables() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS linked_accounts (
                    minecraft_uuid VARCHAR(36) PRIMARY KEY,
                    minecraft_username VARCHAR(16) NOT NULL,
                    account_type VARCHAR(16) NOT NULL,
                    discord_id VARCHAR(20) NOT NULL UNIQUE,
                    link_status VARCHAR(16) NOT NULL DEFAULT 'LINKED',
                    linked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_seen TIMESTAMP NULL DEFAULT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS link_codes (
                    code_hash CHAR(64) PRIMARY KEY,
                    minecraft_uuid VARCHAR(36) NOT NULL,
                    discord_id VARCHAR(20) NULL,
                    expires_at TIMESTAMP NOT NULL,
                    consumed_at TIMESTAMP NULL DEFAULT NULL,
                    attempts INT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS discord_role_cache (
                    discord_id VARCHAR(20) NOT NULL,
                    guild_id VARCHAR(20) NOT NULL,
                    role_id VARCHAR(20) NOT NULL,
                    checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (discord_id, guild_id, role_id)
                );
            """);

            String autoInc = isSqlite ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
            stmt.executeUpdate(String.format("""
                CREATE TABLE IF NOT EXISTS role_rules (
                    rule_id %s,
                    guild_id VARCHAR(20) NOT NULL,
                    role_id VARCHAR(20) NOT NULL,
                    rule_type VARCHAR(20) NOT NULL,
                    tag_name VARCHAR(32) NULL,
                    tag_color VARCHAR(16) NULL,
                    priority INT NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT 1,
                    UNIQUE (guild_id, role_id, rule_type)
                );
            """, autoInc));

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ingame_ranks (
                    minecraft_uuid VARCHAR(36) NOT NULL,
                    rank_key VARCHAR(32) NOT NULL,
                    assigned_by VARCHAR(36) NOT NULL,
                    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    expires_at TIMESTAMP NULL DEFAULT NULL,
                    PRIMARY KEY (minecraft_uuid, rank_key)
                );
            """);

            stmt.executeUpdate(String.format("""
                CREATE TABLE IF NOT EXISTS access_audit (
                    audit_id %s,
                    minecraft_uuid VARCHAR(36) NULL,
                    minecraft_username VARCHAR(16) NULL,
                    discord_id VARCHAR(20) NULL,
                    guild_id VARCHAR(20) NULL,
                    event_type VARCHAR(40) NOT NULL,
                    reason VARCHAR(255) NULL,
                    server_name VARCHAR(32) NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
            """, autoInc));
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public Optional<LinkedAccount> getLinkedAccount(UUID uuid) throws SQLException {
        String sql = "SELECT * FROM linked_accounts WHERE minecraft_uuid = ? LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapLinkedAccount(rs));
            }
        }
        return Optional.empty();
    }

    public Optional<LinkedAccount> getLinkedAccountByDiscordId(String discordId) throws SQLException {
        String sql = "SELECT * FROM linked_accounts WHERE discord_id = ? LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapLinkedAccount(rs));
            }
        }
        return Optional.empty();
    }

    public void updateLastSeen(UUID uuid) {
        String sql = "UPDATE linked_accounts SET last_seen = CURRENT_TIMESTAMP WHERE minecraft_uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (logger != null) logger.warning("Could not update last_seen for " + uuid + ": " + e.getMessage());
        }
    }

    public boolean createLinkCode(UUID uuid, String codeHash, Instant expiresAt) throws SQLException {
        String deleteSql = "DELETE FROM link_codes WHERE minecraft_uuid = ? AND consumed_at IS NULL";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }

        String insertSql = "INSERT INTO link_codes (code_hash, minecraft_uuid, expires_at) VALUES (?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, codeHash);
            ps.setString(2, uuid.toString());
            ps.setTimestamp(3, Timestamp.from(expiresAt));
            return ps.executeUpdate() > 0;
        }
    }

    public Optional<LinkCode> getLinkCode(String codeHash) throws SQLException {
        String sql = "SELECT * FROM link_codes WHERE code_hash = ? LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, codeHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapLinkCode(rs));
            }
        }
        return Optional.empty();
    }

    public synchronized ConsumeResult consumeLinkCode(String codeHash, String discordId, String username, String accountType) throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Check if discordId is already linked
                String checkDiscord = "SELECT minecraft_username FROM linked_accounts WHERE discord_id = ? AND link_status = 'LINKED' LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(checkDiscord)) {
                    ps.setString(1, discordId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            conn.rollback();
                            return new ConsumeResult(false, "Discord ID is already linked to " + rs.getString("minecraft_username"), null);
                        }
                    }
                }

                // Fetch code
                String fetchCode = "SELECT * FROM link_codes WHERE code_hash = ? LIMIT 1";
                LinkCode code;
                try (PreparedStatement ps = conn.prepareStatement(fetchCode)) {
                    ps.setString(1, codeHash);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return new ConsumeResult(false, "Invalid or expired link code.", null);
                        }
                        code = mapLinkCode(rs);
                    }
                }

                if (code.isConsumed() || code.isExpired()) {
                    conn.rollback();
                    return new ConsumeResult(false, "Link code is expired or has already been used.", null);
                }

                // Mark code consumed
                String markConsumed = "UPDATE link_codes SET consumed_at = CURRENT_TIMESTAMP WHERE code_hash = ?";
                try (PreparedStatement ps = conn.prepareStatement(markConsumed)) {
                    ps.setString(1, codeHash);
                    ps.executeUpdate();
                }

                // Upsert linked_accounts
                String upsert;
                if (isSqlite) {
                    upsert = """
                        INSERT INTO linked_accounts (minecraft_uuid, minecraft_username, account_type, discord_id, link_status, linked_at, updated_at)
                        VALUES (?, ?, ?, ?, 'LINKED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT(minecraft_uuid) DO UPDATE SET
                            minecraft_username = excluded.minecraft_username,
                            account_type = excluded.account_type,
                            discord_id = excluded.discord_id,
                            link_status = 'LINKED',
                            updated_at = CURRENT_TIMESTAMP;
                    """;
                } else {
                    upsert = """
                        INSERT INTO linked_accounts (minecraft_uuid, minecraft_username, account_type, discord_id, link_status, linked_at, updated_at)
                        VALUES (?, ?, ?, ?, 'LINKED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON DUPLICATE KEY UPDATE
                            minecraft_username = VALUES(minecraft_username),
                            account_type = VALUES(account_type),
                            discord_id = VALUES(discord_id),
                            link_status = 'LINKED',
                            updated_at = CURRENT_TIMESTAMP;
                    """;
                }

                try (PreparedStatement ps = conn.prepareStatement(upsert)) {
                    ps.setString(1, code.minecraftUuid().toString());
                    ps.setString(2, username);
                    ps.setString(3, accountType);
                    ps.setString(4, discordId);
                    ps.executeUpdate();
                }

                conn.commit();
                LinkedAccount linked = new LinkedAccount(code.minecraftUuid(), username, accountType, discordId, "LINKED", Instant.now(), null, Instant.now());
                return new ConsumeResult(true, "Successfully linked Discord to Minecraft account!", linked);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public boolean unlinkAccount(UUID uuid) throws SQLException {
        String sql = "UPDATE linked_accounts SET link_status = 'UNLINKED' WHERE minecraft_uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public boolean unlinkAccountByDiscordId(String discordId) throws SQLException {
        String sql = "UPDATE linked_accounts SET link_status = 'UNLINKED' WHERE discord_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, discordId);
            return ps.executeUpdate() > 0;
        }
    }

    public void cacheDiscordRoles(String discordId, String guildId, Set<String> roleIds) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                String deleteSql = "DELETE FROM discord_role_cache WHERE discord_id = ? AND guild_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                    ps.setString(1, discordId);
                    ps.setString(2, guildId);
                    ps.executeUpdate();
                }

                String insertSql = "INSERT INTO discord_role_cache (discord_id, guild_id, role_id, checked_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    for (String roleId : roleIds) {
                        ps.setString(1, discordId);
                        ps.setString(2, guildId);
                        ps.setString(3, roleId);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            if (logger != null) logger.warning("Could not cache Discord roles: " + e.getMessage());
        }
    }

    public Optional<Set<String>> getCachedDiscordRoles(String discordId, String guildId, int maxAgeSeconds) {
        String sql = "SELECT role_id, checked_at FROM discord_role_cache WHERE discord_id = ? AND guild_id = ?";
        Set<String> roles = new HashSet<>();
        Instant threshold = Instant.now().minusSeconds(maxAgeSeconds);
        boolean foundValid = false;

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, discordId);
            ps.setString(2, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp checkedAt = rs.getTimestamp("checked_at");
                    if (checkedAt != null && checkedAt.toInstant().isAfter(threshold)) {
                        foundValid = true;
                        roles.add(rs.getString("role_id"));
                    }
                }
            }
        } catch (SQLException e) {
            if (logger != null) logger.warning("Could not query role cache: " + e.getMessage());
            return Optional.empty();
        }

        return foundValid ? Optional.of(roles) : Optional.empty();
    }

    public List<RoleRule> getRoleRules(String ruleType) throws SQLException {
        List<RoleRule> rules = new ArrayList<>();
        String sql = "SELECT * FROM role_rules WHERE rule_type = ? AND enabled = 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ruleType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rules.add(mapRoleRule(rs));
            }
        }
        return rules;
    }

    public boolean addRoleRule(String guildId, String roleId, String ruleType, String tagName, String tagColor, int priority) throws SQLException {
        String sql;
        if (isSqlite) {
            sql = "INSERT INTO role_rules (guild_id, role_id, rule_type, tag_name, tag_color, priority, enabled) VALUES (?, ?, ?, ?, ?, ?, 1) "
                    + "ON CONFLICT(guild_id, role_id, rule_type) DO UPDATE SET tag_name = excluded.tag_name, tag_color = excluded.tag_color, priority = excluded.priority, enabled = 1";
        } else {
            sql = "INSERT INTO role_rules (guild_id, role_id, rule_type, tag_name, tag_color, priority, enabled) VALUES (?, ?, ?, ?, ?, ?, 1) "
                    + "ON DUPLICATE KEY UPDATE tag_name = VALUES(tag_name), tag_color = VALUES(tag_color), priority = VALUES(priority), enabled = 1";
        }
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, guildId);
            ps.setString(2, roleId);
            ps.setString(3, ruleType);
            ps.setString(4, tagName);
            ps.setString(5, tagColor);
            ps.setInt(6, priority);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean removeRoleRule(String guildId, String roleId, String ruleType) throws SQLException {
        String sql = "DELETE FROM role_rules WHERE guild_id = ? AND role_id = ? AND rule_type = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, guildId);
            ps.setString(2, roleId);
            ps.setString(3, ruleType);
            return ps.executeUpdate() > 0;
        }
    }

    public void recordAudit(AuditEvent event) {
        String sql = "INSERT INTO access_audit (minecraft_uuid, minecraft_username, discord_id, guild_id, event_type, reason, server_name) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, event.minecraftUuid() != null ? event.minecraftUuid().toString() : null);
            ps.setString(2, event.minecraftUsername());
            ps.setString(3, event.discordId());
            ps.setString(4, event.guildId());
            ps.setString(5, event.eventType());
            ps.setString(6, event.reason());
            ps.setString(7, event.serverName());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (logger != null) logger.warning("Could not record audit log: " + e.getMessage());
        }
    }

    public int countLinkedAccounts() {
        String sql = "SELECT COUNT(*) FROM linked_accounts WHERE link_status = 'LINKED'";
        try (Connection conn = getConnection(); Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private LinkedAccount mapLinkedAccount(ResultSet rs) throws SQLException {
        String uuidStr = rs.getString("minecraft_uuid");
        return new LinkedAccount(
                uuidStr != null ? UUID.fromString(uuidStr) : null,
                rs.getString("minecraft_username"),
                rs.getString("account_type"),
                rs.getString("discord_id"),
                rs.getString("link_status"),
                getInstant(rs, "linked_at"),
                getInstant(rs, "last_seen"),
                getInstant(rs, "updated_at")
        );
    }

    private LinkCode mapLinkCode(ResultSet rs) throws SQLException {
        String uuidStr = rs.getString("minecraft_uuid");
        return new LinkCode(
                rs.getString("code_hash"),
                uuidStr != null ? UUID.fromString(uuidStr) : null,
                rs.getString("discord_id"),
                getInstant(rs, "expires_at"),
                getInstant(rs, "consumed_at"),
                rs.getInt("attempts"),
                getInstant(rs, "created_at")
        );
    }

    private RoleRule mapRoleRule(ResultSet rs) throws SQLException {
        return new RoleRule(
                rs.getLong("rule_id"),
                rs.getString("guild_id"),
                rs.getString("role_id"),
                rs.getString("rule_type"),
                rs.getString("tag_name"),
                rs.getString("tag_color"),
                rs.getInt("priority"),
                rs.getBoolean("enabled")
        );
    }

    private Instant getInstant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }

    public record ConsumeResult(boolean success, String message, LinkedAccount linkedAccount) {}
}
