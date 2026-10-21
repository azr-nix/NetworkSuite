-- Initial MariaDB/MySQL schema. Review before production.
CREATE TABLE IF NOT EXISTS linked_accounts (
  minecraft_uuid VARCHAR(36) PRIMARY KEY,
  minecraft_username VARCHAR(16) NOT NULL,
  account_type ENUM('ONLINE','OFFLINE') NOT NULL,
  discord_id VARCHAR(20) NOT NULL UNIQUE,
  link_status ENUM('LINKED','UNLINKED','RESTRICTED') NOT NULL DEFAULT 'LINKED',
  linked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen TIMESTAMP NULL DEFAULT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS link_codes (
  code_hash CHAR(64) PRIMARY KEY,
  minecraft_uuid VARCHAR(36) NOT NULL,
  discord_id VARCHAR(20) NULL,
  expires_at TIMESTAMP NOT NULL,
  consumed_at TIMESTAMP NULL DEFAULT NULL,
  attempts INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_link_codes_uuid (minecraft_uuid),
  INDEX idx_link_codes_expiry (expires_at)
);

CREATE TABLE IF NOT EXISTS discord_role_cache (
  discord_id VARCHAR(20) NOT NULL,
  guild_id VARCHAR(20) NOT NULL,
  role_id VARCHAR(20) NOT NULL,
  checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (discord_id, guild_id, role_id)
);

CREATE TABLE IF NOT EXISTS role_rules (
  rule_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  guild_id VARCHAR(20) NOT NULL,
  role_id VARCHAR(20) NOT NULL,
  rule_type ENUM('ACCESS_ALLOW','ACCESS_DENY','DISPLAY_TAG') NOT NULL,
  tag_name VARCHAR(32) NULL,
  tag_color VARCHAR(16) NULL,
  priority INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uq_role_rule (guild_id, role_id, rule_type)
);

CREATE TABLE IF NOT EXISTS ingame_ranks (
  minecraft_uuid VARCHAR(36) NOT NULL,
  rank_key VARCHAR(32) NOT NULL,
  assigned_by VARCHAR(36) NOT NULL,
  assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NULL DEFAULT NULL,
  PRIMARY KEY (minecraft_uuid, rank_key)
);

CREATE TABLE IF NOT EXISTS access_audit (
  audit_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  minecraft_uuid VARCHAR(36) NULL,
  minecraft_username VARCHAR(16) NULL,
  discord_id VARCHAR(20) NULL,
  guild_id VARCHAR(20) NULL,
  event_type VARCHAR(40) NOT NULL,
  reason VARCHAR(255) NULL,
  server_name VARCHAR(32) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_audit_created (created_at),
  INDEX idx_audit_uuid (minecraft_uuid)
);
