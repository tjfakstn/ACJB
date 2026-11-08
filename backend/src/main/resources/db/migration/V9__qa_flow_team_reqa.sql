-- 2026-11-08 QA 흐름을 시안에 맞춤(docs/decision_log.md, SPEC AC3·AC11·AC13·AC19·AC23·AC28, db-schema).

-- 1) 담당자(assigned_to_id) → 담당 팀(assigned_team). 기존 담당자는 그 사람의 프로젝트 역할로 옮긴다(역할 미지정·제거됐으면 NULL)
ALTER TABLE `qa_issues`
  ADD COLUMN `assigned_team` varchar(10) DEFAULT NULL,
  ADD CONSTRAINT `qa_issues_chk_team` CHECK (`assigned_team` IN ('PLANNER', 'DESIGNER', 'FRONTEND', 'BACKEND'));
UPDATE `qa_issues` i
  JOIN `project_members` pm ON pm.`project_id` = i.`project_id` AND pm.`member_id` = i.`assigned_to_id`
  SET i.`assigned_team` = pm.`role`;
ALTER TABLE `qa_issues` DROP FOREIGN KEY `fk_qa_issues_assigned_to`;
ALTER TABLE `qa_issues` DROP INDEX `idx_qa_issues_assigned_to`, DROP COLUMN `assigned_to_id`;
CREATE INDEX `idx_qa_issues_project_team` ON `qa_issues` (`project_id`, `assigned_team`);

-- 2) 자동 Re-QA 실행(AC28)
ALTER TABLE `project_runs` DROP CHECK `project_runs_chk_kind`;
ALTER TABLE `project_runs`
  ADD CONSTRAINT `project_runs_chk_kind` CHECK (`kind` IN ('ANALYSIS', 'FIRST_QA', 'RE_QA')),
  ADD COLUMN `failed_count` int DEFAULT NULL,
  ADD COLUMN `scope` varchar(100) DEFAULT NULL,
  ADD COLUMN `demo` tinyint(1) NOT NULL DEFAULT 0;

-- 3) 재검증 출처(manual / re_qa)와 Re-QA가 다시 읽은 구현 값
ALTER TABLE `verifications`
  ADD COLUMN `source` varchar(10) NOT NULL DEFAULT 'MANUAL',
  ADD COLUMN `observed_value` varchar(255) DEFAULT NULL,
  ADD COLUMN `re_qa_run_id` bigint DEFAULT NULL,
  ADD CONSTRAINT `verifications_chk_source` CHECK (`source` IN ('MANUAL', 'RE_QA')),
  ADD CONSTRAINT `fk_verifications_re_qa_run` FOREIGN KEY (`re_qa_run_id`) REFERENCES `project_runs` (`id`);
