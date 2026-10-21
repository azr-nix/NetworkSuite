package online.rizzlers.tags.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import online.rizzlers.tags.config.TagConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public class TagDatabaseManager implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final boolean isSqlite;
    private final Logger logger;

    public TagDatabaseManager(TagConfig config, File dataFolder, Logger logger) throws SQLException {
        this.logger = logger;
        this.isSqlite = "sqlite".equalsIgnoreCase(config.getDbDriver());

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("RizzlersTagsPool");

        if (isSqlite) {
            String sqlitePath;
            if (":memory:".equals(config.getSqliteFile())) {
                sqlitePath = ":memory:";
            } else {
                File dbFile = new File(dataFolder, config.getSqliteFile());
                if (!dbFile.getParentFile().exists()) dbFile.getParentFile().mkdirs();
                sqlitePath = dbFile.getAbsolutePath();
            }
            hc.setDriverClassName("org.sqlite.JDBC");
            hc.setJdbcUrl("jdbc:sqlite:" + sqlitePath);
            hc.setMaximumPoolSize(1);
            hc.setConnectionTimeout(30000);
        } else {
            hc.setDriverClassName("org.mariadb.jdbc.Driver");
            String url = String.format("jdbc:mariadb://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                    config.getDbHost(), config.getDbPort(), config.getDbName());
            hc.setJdbcUrl(url);
            hc.setUsername(config.getDbUsername());
            hc.setPassword(config.getDbPassword());
            hc.setMaximumPoolSize(config.getDbPoolSize());
            hc.setMinimumIdle(2);
            hc.setConnectionTimeout(10000);
        }

        this.dataSource = new HikariDataSource(hc);
        initTables();
    }

    public TagDatabaseManager(HikariDataSource dataSource, boolean isSqlite, Logger logger) throws SQLException {
        this.dataSource = dataSource;
        this.isSqlite = isSqlite;
        this.logger = logger;
        initTables();
    }

    private void initTables() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
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
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public Set<String> getPlayerRolesAndRanks(UUID uuid) {
        Set<String> keys = new HashSet<>();
        keys.add("member"); // default base rank

        // 1. Fetch in-game ranks
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT rank_key FROM ingame_ranks WHERE minecraft_uuid = ? AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString("rank_key").toLowerCase());
                }
            }
        } catch (SQLException e) {
            if (logger != null) logger.warning("Could not query in-game ranks: " + e.getMessage());
        }

        // 2. Fetch linked discord_id
        String discordId = null;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT discord_id FROM linked_accounts WHERE minecraft_uuid = ? AND link_status = 'LINKED' LIMIT 1")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) discordId = rs.getString("discord_id");
            }
        } catch (SQLException e) {
            if (logger != null) logger.warning("Could not query linked discord account: " + e.getMessage());
        }

        // 3. If linked, fetch discord role cache and map via role_rules
        if (discordId != null) {
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                     SELECT r.tag_name, c.role_id
                     FROM discord_role_cache c
                     LEFT JOIN role_rules r ON c.role_id = r.role_id AND r.rule_type = 'DISPLAY_TAG'
                     WHERE c.discord_id = ?
                 """)) {
                ps.setString(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tagName = rs.getString("tag_name");
                        if (tagName != null && !tagName.isBlank()) {
                            keys.add(tagName.toLowerCase());
                        }
                        // Also add role_id in case config maps direct snowflake to role
                        keys.add(rs.getString("role_id"));
                    }
                }
            } catch (SQLException e) {
                if (logger != null) logger.warning("Could not query discord role cache: " + e.getMessage());
            }
        }

        return keys;
    }

    public boolean grantIngameRank(UUID uuid, String rankKey, String assignedBy) throws SQLException {
        String sql;
        if (isSqlite) {
            sql = "INSERT INTO ingame_ranks (minecraft_uuid, rank_key, assigned_by) VALUES (?, ?, ?) ON CONFLICT(minecraft_uuid, rank_key) DO UPDATE SET assigned_by = excluded.assigned_by, assigned_at = CURRENT_TIMESTAMP";
        } else {
            sql = "INSERT INTO ingame_ranks (minecraft_uuid, rank_key, assigned_by) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE assigned_by = VALUES(assigned_by), assigned_at = CURRENT_TIMESTAMP";
        }
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, rankKey.toLowerCase());
            ps.setString(3, assignedBy);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean revokeIngameRank(UUID uuid, String rankKey) throws SQLException {
        String sql = "DELETE FROM ingame_ranks WHERE minecraft_uuid = ? AND rank_key = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, rankKey.toLowerCase());
            return ps.executeUpdate() > 0;
        }
    }

    public List<String> getIngameRanks(UUID uuid) {
        List<String> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT rank_key FROM ingame_ranks WHERE minecraft_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getString("rank_key"));
            }
        } catch (SQLException ignored) {}
        return list;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }
}
