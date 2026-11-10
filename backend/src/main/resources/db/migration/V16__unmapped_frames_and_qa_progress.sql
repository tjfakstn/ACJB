-- 2026-11-10
-- 1) 비교 분석이 코드 파일을 찾지 못한 화면(프레임) — 매핑 화면에 "확인해 볼 곳"으로만 보여 준다(SPEC AC27). 이슈를 만들지 않는다
-- 2) 첫 QA·Re-QA(새로 더한 범위) 비교 진행률 — 비교한 Figma 요소 수/전체 요소 수(실행 화면 "N/M개 비교")
ALTER TABLE `project_runs`
  ADD COLUMN `unmapped_frames` mediumtext DEFAULT NULL,
  ADD COLUMN `progress_done` int DEFAULT NULL,
  ADD COLUMN `progress_total` int DEFAULT NULL;
