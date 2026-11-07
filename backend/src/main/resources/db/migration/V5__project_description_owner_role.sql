-- 2026-11-07 시안 PRJ에 맞춤(docs/decision_log.md "프로젝트 만들기·목록을 시안 PRJ에 맞춤").
-- 프로젝트 설명(선택, 200자)과, 만든 사람이 역할 없이 시작할 수 있게 project_members.role을 NULL 허용으로.
ALTER TABLE `projects` ADD COLUMN `description` varchar(200) DEFAULT NULL;
ALTER TABLE `project_members` MODIFY COLUMN `role` varchar(20) NULL;
