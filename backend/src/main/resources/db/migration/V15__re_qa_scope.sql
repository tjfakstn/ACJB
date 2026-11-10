-- 2026-11-10 Re-QA에서도 검증 범위를 고른다(SPEC AC28) — 새로 더한 범위와 그 범위에서 만든·버린 이슈 수
ALTER TABLE `project_runs`
  ADD COLUMN `added_scope` varchar(100) DEFAULT NULL,
  ADD COLUMN `found_count` int DEFAULT NULL,
  ADD COLUMN `found_dropped_count` int DEFAULT NULL;
