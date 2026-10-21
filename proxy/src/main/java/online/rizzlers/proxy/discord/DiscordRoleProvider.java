package online.rizzlers.proxy.discord;

import java.util.Set;

public interface DiscordRoleProvider {
    Set<String> getMemberRoles(String guildId, String discordId) throws Exception;
    boolean isReady();
}
