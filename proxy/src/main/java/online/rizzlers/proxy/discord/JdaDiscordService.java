package online.rizzlers.proxy.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import online.rizzlers.proxy.config.ProxyConfig;
import online.rizzlers.proxy.database.ProxyDatabaseManager;
import online.rizzlers.proxy.models.AuditEvent;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class JdaDiscordService extends ListenerAdapter implements DiscordRoleProvider, AutoCloseable {
    private final ProxyConfig config;
    private final ProxyDatabaseManager databaseManager;
    private final Logger logger;
    private JDA jda;
    private boolean ready = false;

    public JdaDiscordService(ProxyConfig config, ProxyDatabaseManager databaseManager, Logger logger) {
        this.config = config;
        this.databaseManager = databaseManager;
        this.logger = logger;
        initJda();
    }

    private void initJda() {
        String token = config.getBotToken();
        if (token == null || token.isBlank() || token.contains("YOUR_")) {
            if (logger != null) logger.warning("Discord bot token not configured. Discord service running in mock/degraded mode.");
            return;
        }

        try {
            this.jda = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_MEMBERS))
                    .addEventListeners(this)
                    .build();

            this.jda.updateCommands().addCommands(
                    Commands.slash("link", "Link your Discord account to RIZZLERS Minecraft")
                            .addOption(OptionType.STRING, "code", "6-character linking code from /plog link", true),
                    Commands.slash("unlink", "Unlink your Discord account from RIZZLERS")
            ).queue(
                    s -> { if (logger != null) logger.info("Registered Discord /link and /unlink slash commands."); },
                    e -> { if (logger != null) logger.warning("Could not register Discord slash commands: " + e.getMessage()); }
            );

            this.ready = true;
            if (logger != null) logger.info("Discord JDA client connected successfully.");
        } catch (Exception e) {
            if (logger != null) logger.severe("Failed to initialize JDA: " + e.getMessage());
        }
    }

    @Override
    public Set<String> getMemberRoles(String guildId, String discordId) throws Exception {
        if (!isReady() || jda == null) {
            throw new IllegalStateException("Discord service not connected.");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalStateException("Configured Discord guild (" + guildId + ") not found.");
        }

        Member member;
        try {
            member = guild.retrieveMemberById(discordId).complete();
        } catch (Exception e) {
            throw new IllegalStateException("Member " + discordId + " not found in guild " + guildId + ".", e);
        }

        if (member == null) return Collections.emptySet();

        return member.getRoles().stream()
                .map(ISnowflake::getId)
                .collect(Collectors.toSet());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if ("link".equalsIgnoreCase(event.getName())) {
            OptionMapping codeOpt = event.getOption("code");
            if (codeOpt == null || codeOpt.getAsString().isBlank()) {
                event.reply("Please provide the linking code generated in-game via `/plog link`.")
                        .setEphemeral(true).queue();
                return;
            }

            String code = codeOpt.getAsString().trim().toUpperCase();
            String hash = sha256(code);
            String discordId = event.getUser().getId();
            String discordTag = event.getUser().getName();

            try {
                ProxyDatabaseManager.ConsumeResult result = databaseManager.consumeLinkCode(hash, discordId, discordTag, "UNKNOWN");
                if (result.success()) {
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTitle("✅ RIZZLERS Account Linked")
                            .setDescription("Linked to player **" + result.linkedAccount().minecraftUsername() + "**!")
                            .addField("UUID", result.linkedAccount().minecraftUuid().toString(), false)
                            .setColor(new Color(0x00FF88))
                            .setTimestamp(Instant.now());
                    event.replyEmbeds(eb.build()).setEphemeral(true).queue();

                    AuditEvent audit = AuditEvent.create(
                            result.linkedAccount().minecraftUuid(),
                            result.linkedAccount().minecraftUsername(),
                            discordId,
                            config.getGuildId(),
                            "LINK_SUCCESS",
                            "Linked via Discord /link command",
                            "PROXY"
                    );
                    databaseManager.recordAudit(audit);
                    sendAuditEmbed(audit);
                } else {
                    event.reply("❌ " + result.message()).setEphemeral(true).queue();
                }
            } catch (Exception e) {
                event.reply("❌ Internal database error while linking. Try again later.").setEphemeral(true).queue();
            }
        } else if ("unlink".equalsIgnoreCase(event.getName())) {
            String discordId = event.getUser().getId();
            try {
                boolean success = databaseManager.unlinkAccountByDiscordId(discordId);
                if (success) {
                    event.reply("✅ Your Discord account has been unlinked from RIZZLERS.").setEphemeral(true).queue();
                    AuditEvent audit = AuditEvent.create(null, null, discordId, config.getGuildId(), "UNLINK_SUCCESS", "Unlinked via Discord /unlink", "PROXY");
                    databaseManager.recordAudit(audit);
                    sendAuditEmbed(audit);
                } else {
                    event.reply("ℹ️ No active link found for your Discord account.").setEphemeral(true).queue();
                }
            } catch (Exception e) {
                event.reply("❌ Internal error while unlinking.").setEphemeral(true).queue();
            }
        }
    }

    public void sendAuditEmbed(AuditEvent audit) {
        if (!isReady() || jda == null) return;
        String channelId = config.getLogChannelId();
        if (channelId == null || channelId.isBlank() || channelId.contains("YOUR_")) return;

        try {
            TextChannel ch = jda.getTextChannelById(channelId);
            if (ch == null) return;

            Color color = new Color(0x3399FF);
            String type = audit.eventType().toUpperCase();
            if (type.contains("ALLOW") || type.contains("SUCCESS")) color = new Color(0x00FF88);
            else if (type.contains("DENY") || type.contains("BLACKLIST")) color = new Color(0xFF3333);
            else if (type.contains("ADMIN") || type.contains("CONFIG")) color = new Color(0xFFAA00);

            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("🛡️ RIZZLERS Network Audit: " + audit.eventType())
                    .setColor(color)
                    .setTimestamp(audit.createdAt());

            if (audit.minecraftUsername() != null) {
                eb.addField("Player", audit.minecraftUsername() + (audit.minecraftUuid() != null ? " (`" + audit.minecraftUuid() + "`)" : ""), false);
            }
            if (audit.discordId() != null) {
                eb.addField("Discord", "<@" + audit.discordId() + "> (`" + audit.discordId() + "`)", false);
            }
            if (audit.reason() != null) {
                eb.addField("Reason / Info", audit.reason(), false);
            }
            eb.setFooter("RIZZLERS Network Suite • Velocity Proxy");

            ch.sendMessageEmbeds(eb.build()).queue(null, err -> {});
        } catch (Exception ignored) {}
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    @Override
    public boolean isReady() {
        return ready && jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    @Override
    public void close() {
        if (jda != null) {
            try {
                jda.shutdown();
                if (!jda.awaitShutdown(5, TimeUnit.SECONDS)) jda.shutdownNow();
            } catch (InterruptedException e) {
                jda.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
