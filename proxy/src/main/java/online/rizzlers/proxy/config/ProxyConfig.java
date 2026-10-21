package online.rizzlers.proxy.config;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ProxyConfig {
    private String botToken = "";
    private String guildId = "";
    private String logChannelId = "";
    private int roleCacheSeconds = 60;

    private String accessEnforcement = "NETWORK";
    private boolean requireLinkedAccount = true;
    private List<String> allowedRoleIds = new ArrayList<>();
    private String blacklistRoleId = "";
    private String blacklistDenialMessage = "<red><bold>ACCESS DENIED</bold></red><newline><red>Your linked Discord account is blacklisted from RIZZLERS.</red>";
    private String unlinkedMessage = "<red>You must link your Discord account before joining RIZZLERS.<newline><yellow>Run <bold>/plog link</bold> in-game or visit our Discord.</yellow></red>";
    private String missingAccessRoleMessage = "<red>Access denied: You do not have the required verified Discord role.</red>";
    private String discordApiFailurePolicy = "DENY";

    private String dbDriver = "mysql";
    private String dbHost = "127.0.0.1";
    private int dbPort = 3306;
    private String dbName = "rizzlers";
    private String dbUsername = "root";
    private String dbPassword = "";
    private int dbPoolSize = 8;
    private String sqliteFile = "proxy_data.db";

    private int codeExpirySeconds = 300;
    private int maxAttemptsPerHour = 5;
    private boolean requireDiscordConfirmation = true;

    @SuppressWarnings("unchecked")
    public static ProxyConfig load(InputStream in) {
        ProxyConfig config = new ProxyConfig();
        if (in == null) return config;

        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(in);
        if (data == null) return config;

        Map<String, Object> discord = (Map<String, Object>) data.get("discord");
        if (discord != null) {
            String tokenEnv = getString(discord, "bot-token-env", "RIZZLERS_DISCORD_TOKEN");
            String envToken = System.getenv(tokenEnv);
            if (envToken != null && !envToken.isBlank()) {
                config.botToken = envToken.trim();
            } else {
                config.botToken = getString(discord, "bot-token", "");
            }
            config.guildId = sanitize(getString(discord, "guild-id", ""));
            config.logChannelId = sanitize(getString(discord, "log-channel-id", ""));
            config.roleCacheSeconds = getInt(discord, "role-cache-seconds", config.roleCacheSeconds);
        }

        Map<String, Object> access = (Map<String, Object>) data.get("access");
        if (access != null) {
            config.accessEnforcement = getString(access, "enforcement", config.accessEnforcement);
            config.requireLinkedAccount = getBoolean(access, "require-linked-account", config.requireLinkedAccount);
            config.blacklistRoleId = sanitize(getString(access, "blacklist-role-id", ""));
            config.blacklistDenialMessage = getString(access, "blacklist-denial-message", config.blacklistDenialMessage);
            config.unlinkedMessage = getString(access, "unlinked-message", config.unlinkedMessage);
            config.missingAccessRoleMessage = getString(access, "missing-access-role-message", config.missingAccessRoleMessage);
            config.discordApiFailurePolicy = getString(access, "discord-api-failure-policy", config.discordApiFailurePolicy).toUpperCase();

            Object rolesObj = access.get("allowed-role-ids");
            if (rolesObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        String s = sanitize(item.toString());
                        if (!s.isBlank()) config.allowedRoleIds.add(s);
                    }
                }
            }
        }

        Map<String, Object> db = (Map<String, Object>) data.get("database");
        if (db != null) {
            config.dbDriver = getString(db, "driver", config.dbDriver).toLowerCase();
            config.dbHost = getString(db, "host", config.dbHost);
            config.dbPort = getInt(db, "port", config.dbPort);
            config.dbName = getString(db, "name", config.dbName);

            String userEnv = getString(db, "username-env", "RIZZLERS_DB_USER");
            String envUser = System.getenv(userEnv);
            config.dbUsername = (envUser != null && !envUser.isBlank()) ? envUser : getString(db, "username", config.dbUsername);

            String passEnv = getString(db, "password-env", "RIZZLERS_DB_PASSWORD");
            String envPass = System.getenv(passEnv);
            config.dbPassword = (envPass != null && !envPass.isBlank()) ? envPass : getString(db, "password", "");

            config.dbPoolSize = getInt(db, "pool-size", config.dbPoolSize);
            config.sqliteFile = getString(db, "sqlite-file", config.sqliteFile);
        }

        Map<String, Object> linking = (Map<String, Object>) data.get("linking");
        if (linking != null) {
            config.codeExpirySeconds = getInt(linking, "code-expiry-seconds", config.codeExpirySeconds);
            config.maxAttemptsPerHour = getInt(linking, "max-attempts-per-hour", config.maxAttemptsPerHour);
            config.requireDiscordConfirmation = getBoolean(linking, "require-discord-confirmation", config.requireDiscordConfirmation);
        }

        return config;
    }

    public static ProxyConfig loadFromFile(File file) {
        if (!file.exists()) return new ProxyConfig();
        try (InputStream in = new FileInputStream(file)) {
            return load(in);
        } catch (Exception e) {
            return new ProxyConfig();
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.startsWith("YOUR_") ? "" : s;
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object o = map.get(key);
        return o != null ? o.toString().trim() : def;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean def) {
        Object o = map.get(key);
        if (o instanceof Boolean b) return b;
        if (o != null) return Boolean.parseBoolean(o.toString().trim());
        return def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object o = map.get(key);
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try { return Integer.parseInt(o.toString().trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    public String getBotToken() { return botToken; }
    public String getGuildId() { return guildId; }
    public void setGuildId(String guildId) { this.guildId = guildId; }
    public String getLogChannelId() { return logChannelId; }
    public void setLogChannelId(String logChannelId) { this.logChannelId = logChannelId; }
    public int getRoleCacheSeconds() { return roleCacheSeconds; }
    public String getAccessEnforcement() { return accessEnforcement; }
    public boolean isRequireLinkedAccount() { return requireLinkedAccount; }
    public List<String> getAllowedRoleIds() { return Collections.unmodifiableList(allowedRoleIds); }
    public void addAllowedRoleId(String roleId) { if (!allowedRoleIds.contains(roleId)) allowedRoleIds.add(roleId); }
    public void removeAllowedRoleId(String roleId) { allowedRoleIds.remove(roleId); }
    public String getBlacklistRoleId() { return blacklistRoleId; }
    public void setBlacklistRoleId(String blacklistRoleId) { this.blacklistRoleId = blacklistRoleId; }
    public String getBlacklistDenialMessage() { return blacklistDenialMessage; }
    public String getUnlinkedMessage() { return unlinkedMessage; }
    public String getMissingAccessRoleMessage() { return missingAccessRoleMessage; }
    public String getDiscordApiFailurePolicy() { return discordApiFailurePolicy; }
    public String getDbDriver() { return dbDriver; }
    public String getDbHost() { return dbHost; }
    public int getDbPort() { return dbPort; }
    public String getDbName() { return dbName; }
    public String getDbUsername() { return dbUsername; }
    public String getDbPassword() { return dbPassword; }
    public int getDbPoolSize() { return dbPoolSize; }
    public String getSqliteFile() { return sqliteFile; }
    public int getCodeExpirySeconds() { return codeExpirySeconds; }
    public int getMaxAttemptsPerHour() { return maxAttemptsPerHour; }
    public boolean isRequireDiscordConfirmation() { return requireDiscordConfirmation; }
}
