-- 2026-11-07 Figma 최종 통합 UI에 맞춘 흐름 재정의(docs/decision_log.md, SPEC AC6·AC21~AC25, db-schema).
-- design_code_mappings는 스파이크로 컬럼을 확정한 뒤 다음 마이그레이션에서 추가한다.

-- 1) 이슈 상태에 IN_PROGRESS(수정 중)
ALTER TABLE `qa_issues` DROP CHECK `qa_issues_chk_4`;
ALTER TABLE `qa_issues`
  ADD CONSTRAINT `qa_issues_chk_4` CHECK (`status` IN ('NEEDS_CHECK', 'IN_PROGRESS', 'FIXED', 'NEEDS_RECHECK', 'RESOLVED'));
ALTER TABLE `status_histories` DROP CHECK `status_histories_chk_1`;
ALTER TABLE `status_histories`
  ADD CONSTRAINT `status_histories_chk_1` CHECK (`status` IN ('NEEDS_CHECK', 'IN_PROGRESS', 'FIXED', 'NEEDS_RECHECK', 'RESOLVED'));

-- 2) 프로젝트의 Figma·GitHub 연결(AC21) — 접근 확인에 성공했을 때만 채운다
ALTER TABLE `projects`
  ADD COLUMN `figma_url` varchar(500) DEFAULT NULL,
  ADD COLUMN `figma_file_key` varchar(100) DEFAULT NULL,
  ADD COLUMN `figma_file_name` varchar(255) DEFAULT NULL,
  ADD COLUMN `figma_connected_at` datetime(6) DEFAULT NULL,
  ADD COLUMN `github_url` varchar(500) DEFAULT NULL,
  ADD COLUMN `github_repo` varchar(200) DEFAULT NULL,
  ADD COLUMN `github_default_branch` varchar(100) DEFAULT NULL,
  ADD COLUMN `github_connected_at` datetime(6) DEFAULT NULL;

-- 3) 비교 분석·첫 QA 실행 기록(AC22·AC23)
CREATE TABLE `project_runs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `kind` varchar(10) NOT NULL,
  `status` varchar(10) NOT NULL,
  `requested_by_id` bigint NOT NULL,
  `commit_sha` varchar(40) DEFAULT NULL,
  `created_count` int NOT NULL DEFAULT 0,
  `dropped_count` int NOT NULL DEFAULT 0,
  `error_message` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_project_runs_project_kind` (`project_id`, `kind`, `created_at` DESC, `id` DESC),
  CONSTRAINT `fk_project_runs_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `fk_project_runs_requested_by` FOREIGN KEY (`requested_by_id`) REFERENCES `members` (`id`),
  CONSTRAINT `project_runs_chk_kind` CHECK (`kind` IN ('ANALYSIS', 'FIRST_QA')),
  CONSTRAINT `project_runs_chk_status` CHECK (`status` IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 4) 이슈 출처(manual / first_qa)와 만든 첫 QA 실행
ALTER TABLE `qa_issues`
  ADD COLUMN `source` varchar(10) NOT NULL DEFAULT 'MANUAL',
  ADD COLUMN `first_qa_run_id` bigint DEFAULT NULL,
  ADD KEY `idx_qa_issues_project_source` (`project_id`, `source`),
  ADD CONSTRAINT `fk_qa_issues_first_qa_run` FOREIGN KEY (`first_qa_run_id`) REFERENCES `project_runs` (`id`),
  ADD CONSTRAINT `qa_issues_chk_source` CHECK (`source` IN ('MANUAL', 'FIRST_QA'));

-- 5) 스타일 차이 출처에 FIRST_QA, 코드 값을 확인한 위치(N17)
ALTER TABLE `style_mismatches` DROP CHECK `style_mismatches_chk_1`;
ALTER TABLE `style_mismatches`
  ADD CONSTRAINT `style_mismatches_chk_1` CHECK (`source` IN ('MANUAL', 'SCAN', 'FIRST_QA')),
  ADD COLUMN `code_path` varchar(500) DEFAULT NULL,
  ADD COLUMN `code_line` int DEFAULT NULL;
