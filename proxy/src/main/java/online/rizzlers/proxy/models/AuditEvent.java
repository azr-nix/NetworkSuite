package online.rizzlers.proxy.models;

import java.time.Instant;
import java.util.UUID;

public record AuditEvent(
        long auditId,
        UUID minecraftUuid,
        String minecraftUsername,
        String discordId,
        String guildId,
        String eventType,
        String reason,
        String serverName,
        Instant createdAt
) {
    public static AuditEvent create(UUID uuid, String username, String discordId, String guildId, String eventType, String reason, String serverName) {
        return new AuditEvent(0, uuid, username, discordId, guildId, eventType, reason, serverName, Instant.now());
    }
}
