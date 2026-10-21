package online.rizzlers.tags.config;

import online.rizzlers.tags.models.RoleInfo;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class TagConfig {
    private String serverName = "lobby";

    private boolean tagsEnabled = true;
    private int viewingDistanceBlocks = 16;
    private boolean hideWhileSneaking = true;
    private int updateIntervalTicks = 10;
    private String displayMode = "HIGHEST_PRIORITY";
    private boolean chatEnabled = true;
    private String chatFormat = "<gray>{tag}</gray> <white>{player}</white><dark_gray>: </dark_gray>{message}";

    private String dbDriver = "mysql";
    private String dbHost = "127.0.0.1";
    private int dbPort = 3306;
    private String dbName = "rizzlers";
    private String dbUsername = "root";
    private String dbPassword = "";
    private int dbPoolSize = 8;
    private String sqliteFile = "proxy_data.db";

    private final Map<String, RoleInfo> roles = new HashMap<>();

    @SuppressWarnings("unchecked")
    public static TagConfig load(InputStream in) {
        TagConfig config = new TagConfig();
        if (in == null) return config;

        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(in);
        if (data == null) return config;

        config.serverName = getString(data, "server-name", config.serverName);

        Map<String, Object> tags = (Map<String, Object>) data.get("tags");
        if (tags != null) {
            config.tagsEnabled = getBoolean(tags, "enabled", config.tagsEnabled);
            config.viewingDistanceBlocks = getInt(tags, "viewing-distance-blocks", config.viewingDistanceBlocks);
            config.hideWhileSneaking = getBoolean(tags, "hide-while-sneaking", config.hideWhileSneaking);
            config.updateIntervalTicks = getInt(tags, "update-interval-ticks", config.updateIntervalTicks);
            config.displayMode = getString(tags, "display-mode", config.displayMode);

            Map<String, Object> chat = (Map<String, Object>) tags.get("chat");
            if (chat != null) {
                config.chatEnabled = getBoolean(chat, "enabled", config.chatEnabled);
                config.chatFormat = getString(chat, "format", config.chatFormat);
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

        Map<String, Object> rolesMap = (Map<String, Object>) data.get("roles");
        if (rolesMap != null) {
            for (Map.Entry<String, Object> entry : rolesMap.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> roleData) {
                    String tag = roleData.get("tag") != null ? roleData.get("tag").toString() : "[" + entry.getKey().toUpperCase() + "]";
                    String color = roleData.get("color") != null ? roleData.get("color").toString() : "#FFFFFF";
                    int priority = roleData.get("priority") instanceof Number n ? n.intValue() : 0;
                    config.roles.put(entry.getKey().toLowerCase(), new RoleInfo(entry.getKey().toLowerCase(), tag, color, priority));
                }
            }
        }

        // Add default member tag if absent
        if (!config.roles.containsKey("member")) {
            config.roles.put("member", new RoleInfo("member", "[MEMBER]", "#BFC7D5", 10));
        }

        return config;
    }

    public static TagConfig loadFromFile(File file) {
        if (!file.exists()) return new TagConfig();
        try (InputStream in = new FileInputStream(file)) {
            return load(in);
        } catch (Exception e) {
            return new TagConfig();
        }
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

    public String getServerName() { return serverName; }
    public boolean isTagsEnabled() { return tagsEnabled; }
    public int getViewingDistanceBlocks() { return viewingDistanceBlocks; }
    public void setViewingDistanceBlocks(int dist) { this.viewingDistanceBlocks = dist; }
    public boolean isHideWhileSneaking() { return hideWhileSneaking; }
    public int getUpdateIntervalTicks() { return updateIntervalTicks; }
    public String getDisplayMode() { return displayMode; }
    public boolean isChatEnabled() { return chatEnabled; }
    public String getChatFormat() { return chatFormat; }
    public String getDbDriver() { return dbDriver; }
    public String getDbHost() { return dbHost; }
    public int getDbPort() { return dbPort; }
    public String getDbName() { return dbName; }
    public String getDbUsername() { return dbUsername; }
    public String getDbPassword() { return dbPassword; }
    public int getDbPoolSize() { return dbPoolSize; }
    public String getSqliteFile() { return sqliteFile; }
    public Map<String, RoleInfo> getRoles() { return Collections.unmodifiableMap(roles); }
}
