-- 2026-11-07 GitHub 연결 때 분석할 브랜치를 고른다(docs/decision_log.md). 기존 연결은 기본 브랜치로 채운다.
ALTER TABLE `projects` ADD COLUMN `github_branch` varchar(100) DEFAULT NULL;
UPDATE `projects` SET `github_branch` = `github_default_branch` WHERE `github_repo` IS NOT NULL;
