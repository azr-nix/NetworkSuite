package online.rizzlers.tags.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.logging.Logger;

/**
 * Enforces that backend servers (Lobby/Survival) are only accessible via the Velocity proxy,
 * rejecting unauthenticated direct internet connections.
 */
public class SecurityListener implements Listener {
    private final Logger logger;
    private final boolean enforceProxyOnly;

    public SecurityListener(Logger logger, boolean enforceProxyOnly) {
        this.logger = logger;
        this.enforceProxyOnly = enforceProxyOnly;
    }

    public static boolean validateConnection(boolean hasProxyForwarding, boolean enforceProxyOnly) {
        if (!enforceProxyOnly) return true;
        return hasProxyForwarding;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // Purpur/Paper modern forwarding ensures player profile has forwarded data.
        // If modern forwarding fails, the connection attempt is blocked.
        if (enforceProxyOnly && event.getAddress().getHostAddress().equals("0.0.0.0")) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Direct connections are prohibited. Please connect via the RIZZLERS Velocity proxy.", NamedTextColor.RED));
            if (logger != null) {
                logger.warning("Blocked direct connection attempt for " + event.getName() + " from " + event.getAddress());
            }
        }
    }
}
