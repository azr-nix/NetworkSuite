package online.rizzlers.proxy.models;

import java.time.Instant;
import java.util.UUID;

public record LinkedAccount(
        UUID minecraftUuid,
        String minecraftUsername,
        String accountType,
        String discordId,
        String linkStatus,
        Instant linkedAt,
        Instant lastSeen,
        Instant updatedAt
) {
    public boolean isLinked() {
        return "LINKED".equalsIgnoreCase(linkStatus);
    }

    public boolean isOffline() {
        return "OFFLINE".equalsIgnoreCase(accountType);
    }
}
