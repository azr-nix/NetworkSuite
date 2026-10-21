package online.rizzlers.tags.tag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import online.rizzlers.tags.config.TagConfig;
import online.rizzlers.tags.models.RoleInfo;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages proximity-based and sneak-sensitive overhead nametag visibility using Vanilla Scoreboard Teams.
 * Compatible with Purpur 26.3, Geyser, and standard client versions.
 */
public class NametagManager {
    private final TagConfig config;
    private final TagManager tagManager;
    private final Logger logger;

    public NametagManager(TagConfig config, TagManager tagManager, Logger logger) {
        this.config = config;
        this.tagManager = tagManager;
        this.logger = logger;
    }

    /**
     * Core visibility evaluation method tested by unit tests.
     *
     * @param distance           Euclidean distance in blocks between viewer and target
     * @param maxDistance        Configured maximum viewing distance (e.g. 16 for Lobby, 24 for Survival)
     * @param isSneaking         Whether the target player is currently sneaking
     * @param hideWhileSneaking  Whether sneak-hiding is enabled in config
     * @return true if the nametag should be visible to the viewer
     */
    public static boolean isTagVisible(double distance, double maxDistance, boolean isSneaking, boolean hideWhileSneaking) {
        if (distance > maxDistance) {
            return false;
        }
        if (isSneaking && hideWhileSneaking) {
            return false;
        }
        return true;
    }

    /**
     * Updates nametag team prefixes for all online players.
     */
    public void updateAll() {
        if (!config.isTagsEnabled()) return;
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();

        for (Player target : Bukkit.getOnlinePlayers()) {
            RoleInfo role = tagManager.getCachedTag(target.getUniqueId());
            updatePlayerTeam(mainBoard, target, role, target.isSneaking());
        }
    }

    /**
     * Updates visibility and team prefix for a specific player on sneak toggle.
     */
    public void onPlayerSneakToggle(Player target, boolean isSneaking) {
        if (!config.isTagsEnabled()) return;
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        RoleInfo role = tagManager.getCachedTag(target.getUniqueId());
        updatePlayerTeam(mainBoard, target, role, isSneaking);
    }

    private void updatePlayerTeam(Scoreboard board, Player player, RoleInfo role, boolean isSneaking) {
        String teamName = ("t_" + player.getName()).substring(0, Math.min(16, "t_" + player.getName().length()));
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
        }

        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }

        if (isSneaking && config.isHideWhileSneaking()) {
            team.prefix(Component.empty());
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.HIDE_FOR_OTHER_TEAMS);
        } else {
            TextColor color = TextColor.fromHexString(role.color());
            Component prefix = Component.text(role.tag() + " ", color != null ? color : TextColor.color(0xFFFFFF));
            team.prefix(prefix);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        }
    }

    public void removePlayer(Player player) {
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = ("t_" + player.getName()).substring(0, Math.min(16, "t_" + player.getName().length()));
        Team team = mainBoard.getTeam(teamName);
        if (team != null) {
            team.unregister();
        }
    }
}
