package online.rizzlers.tags.tag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import online.rizzlers.tags.config.TagConfig;
import online.rizzlers.tags.database.TagDatabaseManager;
import online.rizzlers.tags.models.RoleInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TagManager {
    private final TagConfig config;
    private final TagDatabaseManager databaseManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, RoleInfo> cachedActiveTags = new ConcurrentHashMap<>();

    public TagManager(TagConfig config, TagDatabaseManager databaseManager) {
        this.config = config;
        this.databaseManager = databaseManager;
    }

    public RoleInfo getHighestPriorityTag(Set<String> activeRoleOrRankKeys) {
        Map<String, RoleInfo> configuredRoles = config.getRoles();
        List<RoleInfo> matching = new ArrayList<>();

        for (String key : activeRoleOrRankKeys) {
            String lower = key.toLowerCase();
            RoleInfo role = configuredRoles.get(lower);
            if (role != null) {
                matching.add(role);
            }
        }

        if (matching.isEmpty()) {
            return configuredRoles.getOrDefault("member", new RoleInfo("member", "[MEMBER]", "#BFC7D5", 10));
        }

        // Sort descending by priority (RoleInfo implements Comparable: higher priority first)
        Collections.sort(matching);
        return matching.get(0);
    }

    public RoleInfo resolvePlayerTag(UUID uuid) {
        Set<String> rolesAndRanks = databaseManager.getPlayerRolesAndRanks(uuid);
        RoleInfo highest = getHighestPriorityTag(rolesAndRanks);
        cachedActiveTags.put(uuid, highest);
        return highest;
    }

    public RoleInfo getCachedTag(UUID uuid) {
        return cachedActiveTags.computeIfAbsent(uuid, this::resolvePlayerTag);
    }

    public void invalidate(UUID uuid) {
        cachedActiveTags.remove(uuid);
    }

    public Component formatChat(String playerName, RoleInfo role, String rawUserMessage) {
        if (rawUserMessage == null) rawUserMessage = "";
        // Security safeguard: strictly escape user message tags to prevent MiniMessage injection
        String safeMessage = miniMessage.escapeTags(rawUserMessage);

        String tagFormatted = String.format("<color:%s><bold>%s</bold></color>", role.color(), role.tag());
        String format = config.getChatFormat()
                .replace("{tag}", tagFormatted)
                .replace("{player}", playerName)
                .replace("{message}", safeMessage);

        try {
            return miniMessage.deserialize(format);
        } catch (Exception e) {
            return Component.text("[" + role.tag() + "] " + playerName + ": " + rawUserMessage);
        }
    }

    public TagConfig getConfig() {
        return config;
    }

    public TagDatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
