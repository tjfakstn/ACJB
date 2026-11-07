-- 2026-11-07 역할 developer를 frontend·backend로 나눈다(docs/decision_log.md).
-- 배포 전이라 기존 DEVELOPER는 로컬 DB에만 있다 — 전부 BACKEND로 바꾼다.
ALTER TABLE `project_members` DROP CHECK `project_members_chk_1`;

UPDATE `project_members` SET `role` = 'BACKEND' WHERE `role` = 'DEVELOPER';

ALTER TABLE `project_members`
  ADD CONSTRAINT `project_members_chk_1` CHECK (`role` IN ('DESIGNER', 'FRONTEND', 'BACKEND', 'PLANNER'));
