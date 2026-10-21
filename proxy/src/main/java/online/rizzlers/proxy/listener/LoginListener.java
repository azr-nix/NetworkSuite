package online.rizzlers.proxy.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import online.rizzlers.proxy.access.AccessDecision;
import online.rizzlers.proxy.access.AccessEvaluator;
import online.rizzlers.proxy.config.ProxyConfig;
import online.rizzlers.proxy.database.ProxyDatabaseManager;
import online.rizzlers.proxy.discord.JdaDiscordService;
import online.rizzlers.proxy.models.AuditEvent;

import java.util.UUID;
import java.util.logging.Logger;

public class LoginListener {
    private final ProxyConfig config;
    private final AccessEvaluator accessEvaluator;
    private final ProxyDatabaseManager databaseManager;
    private final JdaDiscordService discordService;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public LoginListener(ProxyConfig config,
                         AccessEvaluator accessEvaluator,
                         ProxyDatabaseManager databaseManager,
                         JdaDiscordService discordService,
                         Logger logger) {
        this.config = config;
        this.accessEvaluator = accessEvaluator;
        this.databaseManager = databaseManager;
        this.discordService = discordService;
        this.logger = logger;
    }

    @Subscribe(order = PostOrder.FIRST)
    public EventTask onLogin(LoginEvent event) {
        return EventTask.async(() -> {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            String username = player.getUsername();
            String accountType = player.isOnlineMode() ? "ONLINE" : "OFFLINE";

            try {
                AccessDecision decision = accessEvaluator.evaluateAccess(uuid, username, accountType);

                if (decision.allowed()) {
                    event.setResult(ResultedEvent.ComponentResult.allowed());
                    AuditEvent audit = AuditEvent.create(uuid, username, null, config.getGuildId(), "LOGIN_ALLOW", decision.internalReason(), "PROXY");
                    databaseManager.recordAudit(audit);
                    if (discordService != null) discordService.sendAuditEmbed(audit);
                } else {
                    Component kickMsg = miniMessage.deserialize(decision.kickMessageTemplate());
                    event.setResult(ResultedEvent.ComponentResult.denied(kickMsg));
                    AuditEvent audit = AuditEvent.create(uuid, username, null, config.getGuildId(), "LOGIN_DENY", decision.internalReason(), "PROXY");
                    databaseManager.recordAudit(audit);
                    if (discordService != null) discordService.sendAuditEmbed(audit);
                }
            } catch (Throwable t) {
                if (logger != null) logger.severe("Unexpected exception evaluating login for " + username + ": " + t.getMessage());
                if ("DENY".equalsIgnoreCase(config.getDiscordApiFailurePolicy())) {
                    event.setResult(ResultedEvent.ComponentResult.denied(miniMessage.deserialize(config.getMissingAccessRoleMessage())));
                }
            }
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        AuditEvent audit = AuditEvent.create(player.getUniqueId(), player.getUsername(), null, config.getGuildId(), "QUIT", "Disconnected from proxy", "PROXY");
        databaseManager.recordAudit(audit);
    }
}
