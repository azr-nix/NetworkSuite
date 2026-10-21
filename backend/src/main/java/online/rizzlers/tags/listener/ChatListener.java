package online.rizzlers.tags.listener;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import online.rizzlers.tags.models.RoleInfo;
import online.rizzlers.tags.tag.NametagManager;
import online.rizzlers.tags.tag.TagManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;

public class ChatListener implements Listener {
    private final TagManager tagManager;
    private final NametagManager nametagManager;
    private final Plugin plugin;

    public ChatListener(TagManager tagManager, NametagManager nametagManager, Plugin plugin) {
        this.tagManager = tagManager;
        this.nametagManager = nametagManager;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!tagManager.getConfig().isChatEnabled()) return;

        Player player = event.getPlayer();
        RoleInfo role = tagManager.getCachedTag(player.getUniqueId());
        String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message());

        Component formattedChat = tagManager.formatChat(player.getName(), role, rawMessage);
        event.renderer((source, sourceDisplayName, message, viewer) -> formattedChat);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        nametagManager.onPlayerSneakToggle(event.getPlayer(), event.isSneaking());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            tagManager.resolvePlayerTag(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    nametagManager.onPlayerSneakToggle(player, player.isSneaking());
                }
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        nametagManager.removePlayer(event.getPlayer());
        tagManager.invalidate(event.getPlayer().getUniqueId());
    }
}
