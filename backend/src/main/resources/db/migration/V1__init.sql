-- V1: 2026-11-05 시점 전체 스키마 (14개 테이블)
-- Flyway 도입 전까지는 Hibernate ddl-auto: update가 엔티티를 보고 테이블을 만들었다.
-- 이 파일은 그때 Hibernate가 만든 구조를 그대로 옮긴 것이다(ddl-auto: create → mysqldump --no-data). FK·UK 이름만 읽기 쉽게 바꿨다.
-- 이후 스키마 변경은 이 파일을 고치지 말고 V2__설명.sql처럼 새 파일을 추가한다(이미 적용된 파일을 고치면 체크섬이 달라져 서버가 뜨지 않는다).
-- 테이블 정의 설명은 docs/db-schema.md

CREATE TABLE `members` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `email` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_members_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `projects` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_id` bigint NOT NULL,
  `name` varchar(100) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_projects_owner` (`owner_id`),
  CONSTRAINT `fk_projects_owner` FOREIGN KEY (`owner_id`) REFERENCES `members` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `project_members` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `joined_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  `project_id` bigint NOT NULL,
  `role` varchar(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_members_project_member` (`project_id`,`member_id`),
  KEY `fk_project_members_member` (`member_id`),
  CONSTRAINT `fk_project_members_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `fk_project_members_member` FOREIGN KEY (`member_id`) REFERENCES `members` (`id`),
  CONSTRAINT `project_members_chk_1` CHECK ((`role` in (_utf8mb4'DESIGNER',_utf8mb4'DEVELOPER',_utf8mb4'PLANNER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `invitations` (
  `accepted_at` datetime(6) DEFAULT NULL,
  `accepted_member_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `invited_by_id` bigint NOT NULL,
  `project_id` bigint NOT NULL,
  `status` varchar(10) NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  `email` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invitations_token_hash` (`token_hash`),
  KEY `idx_invitations_project_status` (`project_id`,`status`,`created_at` DESC,`id` DESC),
  KEY `idx_invitations_email_status` (`email`,`status`),
  KEY `fk_invitations_accepted_member` (`accepted_member_id`),
  KEY `fk_invitations_invited_by` (`invited_by_id`),
  CONSTRAINT `fk_invitations_invited_by` FOREIGN KEY (`invited_by_id`) REFERENCES `members` (`id`),
  CONSTRAINT `fk_invitations_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `fk_invitations_accepted_member` FOREIGN KEY (`accepted_member_id`) REFERENCES `members` (`id`),
  CONSTRAINT `invitations_chk_1` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'ACCEPTED',_utf8mb4'CANCELED',_utf8mb4'EXPIRED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `figma_frames` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_synced_at` datetime(6) NOT NULL,
  `project_id` bigint NOT NULL,
  `frame_id` varchar(50) NOT NULL,
  `figma_file_key` varchar(100) NOT NULL,
  `frame_url` varchar(500) NOT NULL,
  `name` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_figma_frames_project_frame` (`project_id`,`frame_id`),
  CONSTRAINT `fk_figma_frames_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `figma_syncs` (
  `frame_count` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `requested_by_id` bigint NOT NULL,
  `status` varchar(10) NOT NULL,
  `figma_file_key` varchar(100) NOT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `frame_ids` json DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_figma_syncs_project` (`project_id`),
  KEY `fk_figma_syncs_requested_by` (`requested_by_id`),
  CONSTRAINT `fk_figma_syncs_requested_by` FOREIGN KEY (`requested_by_id`) REFERENCES `members` (`id`),
  CONSTRAINT `fk_figma_syncs_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `figma_syncs_chk_1` CHECK ((`status` in (_utf8mb4'QUEUED',_utf8mb4'RUNNING',_utf8mb4'DONE',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `style_scans` (
  `mismatch_count` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `requested_by_id` bigint NOT NULL,
  `status` varchar(10) NOT NULL,
  `frame_id` varchar(50) NOT NULL,
  `node_id` varchar(50) NOT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `selector` varchar(500) NOT NULL,
  `target_url` varchar(1000) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_style_scans_project` (`project_id`),
  KEY `fk_style_scans_requested_by` (`requested_by_id`),
  CONSTRAINT `fk_style_scans_requested_by` FOREIGN KEY (`requested_by_id`) REFERENCES `members` (`id`),
  CONSTRAINT `fk_style_scans_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `style_scans_chk_1` CHECK ((`status` in (_utf8mb4'QUEUED',_utf8mb4'RUNNING',_utf8mb4'DONE',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `style_mismatches` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `scan_id` bigint DEFAULT NULL,
  `source` varchar(10) NOT NULL,
  `property` varchar(20) NOT NULL,
  `frame_id` varchar(50) DEFAULT NULL,
  `node_id` varchar(50) DEFAULT NULL,
  `selector` varchar(500) DEFAULT NULL,
  `screen` varchar(1000) DEFAULT NULL,
  `actual_value` varchar(255) NOT NULL,
  `expected_value` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_style_mismatches_project_created` (`project_id`,`created_at` DESC,`id` DESC),
  KEY `idx_style_mismatches_project_frame` (`project_id`,`frame_id`),
  KEY `idx_style_mismatches_project_property` (`project_id`,`property`),
  KEY `fk_style_mismatches_scan` (`scan_id`),
  CONSTRAINT `fk_style_mismatches_scan` FOREIGN KEY (`scan_id`) REFERENCES `style_scans` (`id`),
  CONSTRAINT `fk_style_mismatches_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `style_mismatches_chk_1` CHECK ((`source` in (_utf8mb4'MANUAL',_utf8mb4'SCAN'))),
  CONSTRAINT `style_mismatches_chk_2` CHECK ((`property` in (_utf8mb4'COLOR',_utf8mb4'FONT_WEIGHT',_utf8mb4'FONT_FAMILY',_utf8mb4'RADIUS',_utf8mb4'SHADOW',_utf8mb4'SPACING',_utf8mb4'TEXT')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `qa_issues` (
  `assigned_to_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `reported_by_id` bigint NOT NULL,
  `style_mismatch_id` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `env_deploy_stage` varchar(10) DEFAULT NULL,
  `env_platform` varchar(10) DEFAULT NULL,
  `platform_scope` varchar(10) NOT NULL,
  `status` varchar(20) NOT NULL,
  `env_browser` varchar(100) DEFAULT NULL,
  `env_device` varchar(100) DEFAULT NULL,
  `figma_link` varchar(500) DEFAULT NULL,
  `description` text NOT NULL,
  `screen_location` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_qa_issues_project_created` (`project_id`,`created_at` DESC,`id` DESC),
  KEY `idx_qa_issues_project_status` (`project_id`,`status`),
  KEY `idx_qa_issues_assigned_to` (`assigned_to_id`),
  KEY `idx_qa_issues_reported_by` (`reported_by_id`),
  KEY `fk_qa_issues_style_mismatch` (`style_mismatch_id`),
  CONSTRAINT `fk_qa_issues_reported_by` FOREIGN KEY (`reported_by_id`) REFERENCES `members` (`id`),
  CONSTRAINT `fk_qa_issues_assigned_to` FOREIGN KEY (`assigned_to_id`) REFERENCES `members` (`id`),
  CONSTRAINT `fk_qa_issues_style_mismatch` FOREIGN KEY (`style_mismatch_id`) REFERENCES `style_mismatches` (`id`),
  CONSTRAINT `fk_qa_issues_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `qa_issues_chk_1` CHECK ((`env_deploy_stage` in (_utf8mb4'LOCAL',_utf8mb4'STAGING',_utf8mb4'PRODUCTION'))),
  CONSTRAINT `qa_issues_chk_2` CHECK ((`env_platform` in (_utf8mb4'AOS',_utf8mb4'IOS',_utf8mb4'WEB'))),
  CONSTRAINT `qa_issues_chk_3` CHECK ((`platform_scope` in (_utf8mb4'COMMON',_utf8mb4'AOS',_utf8mb4'IOS'))),
  CONSTRAINT `qa_issues_chk_4` CHECK ((`status` in (_utf8mb4'NEEDS_CHECK',_utf8mb4'FIXED',_utf8mb4'NEEDS_RECHECK',_utf8mb4'RESOLVED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `qa_issue_repro_steps` (
  `step_order` int NOT NULL,
  `qa_issue_id` bigint NOT NULL,
  `step` varchar(500) NOT NULL,
  PRIMARY KEY (`step_order`,`qa_issue_id`),
  KEY `fk_qa_issue_repro_steps_qa_issue` (`qa_issue_id`),
  CONSTRAINT `fk_qa_issue_repro_steps_qa_issue` FOREIGN KEY (`qa_issue_id`) REFERENCES `qa_issues` (`id`),
  CONSTRAINT `qa_issue_repro_steps_chk_1` CHECK ((`step_order` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `qa_issue_screenshots` (
  `position` int NOT NULL,
  `qa_issue_id` bigint NOT NULL,
  `url` varchar(1000) NOT NULL,
  PRIMARY KEY (`position`,`qa_issue_id`),
  KEY `fk_qa_issue_screenshots_qa_issue` (`qa_issue_id`),
  CONSTRAINT `fk_qa_issue_screenshots_qa_issue` FOREIGN KEY (`qa_issue_id`) REFERENCES `qa_issues` (`id`),
  CONSTRAINT `qa_issue_screenshots_chk_1` CHECK ((`position` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `status_histories` (
  `changed_at` datetime(6) NOT NULL,
  `changed_by_id` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `qa_issue_id` bigint NOT NULL,
  `status` varchar(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_status_histories_issue_changed` (`qa_issue_id`,`changed_at`,`id`),
  KEY `fk_status_histories_changed_by` (`changed_by_id`),
  CONSTRAINT `fk_status_histories_qa_issue` FOREIGN KEY (`qa_issue_id`) REFERENCES `qa_issues` (`id`),
  CONSTRAINT `fk_status_histories_changed_by` FOREIGN KEY (`changed_by_id`) REFERENCES `members` (`id`),
  CONSTRAINT `status_histories_chk_1` CHECK ((`status` in (_utf8mb4'NEEDS_CHECK',_utf8mb4'FIXED',_utf8mb4'NEEDS_RECHECK',_utf8mb4'RESOLVED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `verifications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `qa_issue_id` bigint NOT NULL,
  `verified_at` datetime(6) NOT NULL,
  `verified_by_id` bigint NOT NULL,
  `result` varchar(10) NOT NULL,
  `scope` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_verifications_issue_verified` (`qa_issue_id`,`verified_at` DESC,`id` DESC),
  KEY `fk_verifications_verified_by` (`verified_by_id`),
  CONSTRAINT `fk_verifications_qa_issue` FOREIGN KEY (`qa_issue_id`) REFERENCES `qa_issues` (`id`),
  CONSTRAINT `fk_verifications_verified_by` FOREIGN KEY (`verified_by_id`) REFERENCES `members` (`id`),
  CONSTRAINT `verifications_chk_1` CHECK ((`result` in (_utf8mb4'PASS',_utf8mb4'FAIL')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `notifications` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `qa_issue_id` bigint NOT NULL,
  `recipient_id` bigint NOT NULL,
  `channel` varchar(20) NOT NULL,
  `event` varchar(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_notifications_recipient_created` (`recipient_id`,`created_at` DESC,`id` DESC),
  KEY `fk_notifications_qa_issue` (`qa_issue_id`),
  CONSTRAINT `fk_notifications_qa_issue` FOREIGN KEY (`qa_issue_id`) REFERENCES `qa_issues` (`id`),
  CONSTRAINT `fk_notifications_recipient` FOREIGN KEY (`recipient_id`) REFERENCES `members` (`id`),
  CONSTRAINT `notifications_chk_1` CHECK ((`event` in (_utf8mb4'ISSUE_CREATED',_utf8mb4'FIXED',_utf8mb4'RECHECK_FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
