package online.rizzlers.proxy.models;

import java.time.Instant;
import java.util.UUID;

public record LinkCode(
        String codeHash,
        UUID minecraftUuid,
        String discordId,
        Instant expiresAt,
        Instant consumedAt,
        int attempts,
        Instant createdAt
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }
}
