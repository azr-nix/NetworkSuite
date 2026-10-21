package online.rizzlers.proxy.models;

import java.time.Instant;

public record RoleRule(
        long ruleId,
        String guildId,
        String roleId,
        String ruleType,
        String tagName,
        String tagColor,
        int priority,
        boolean enabled
) {
    public boolean isBlacklist() {
        return "ACCESS_DENY".equalsIgnoreCase(ruleType) || "BLACKLIST".equalsIgnoreCase(ruleType);
    }

    public boolean isAllow() {
        return "ACCESS_ALLOW".equalsIgnoreCase(ruleType) || "ALLOW".equalsIgnoreCase(ruleType);
    }
}
