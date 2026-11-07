-- 비교 분석(AC22)·매핑 조회(AC27) — 2026-11-07 스파이크(docs/spikes/2026-11-07_design-code-mapping.md) 후 확정

-- 1) Figma 연결이 페이지인지 섹션인지. 기존 연결은 모두 페이지 링크였다
ALTER TABLE `projects`
  ADD COLUMN `figma_scope` varchar(10) DEFAULT NULL;
UPDATE `projects` SET `figma_scope` = 'PAGE' WHERE `figma_page_id` IS NOT NULL;
ALTER TABLE `projects`
  ADD CONSTRAINT `projects_chk_figma_scope` CHECK (`figma_scope` IS NULL OR `figma_scope` IN ('PAGE', 'SECTION'));

-- 2) Figma 프레임·노드 ↔ 코드 파일·컴포넌트. node_id가 NULL이면 프레임 자체의 짝(프레임 → 파일)
CREATE TABLE `design_code_mappings` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `run_id` bigint NOT NULL,
  `frame_id` varchar(50) NOT NULL,
  `frame_name` varchar(255) NOT NULL,
  `node_id` varchar(50) DEFAULT NULL,
  `node_name` varchar(255) DEFAULT NULL,
  `code_path` varchar(500) NOT NULL,
  `code_symbol` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_design_code_mappings_run_order` (`run_id`, `frame_name`, `frame_id`, `node_name`, `node_id`),
  KEY `idx_design_code_mappings_project` (`project_id`),
  CONSTRAINT `fk_design_code_mappings_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `fk_design_code_mappings_run` FOREIGN KEY (`run_id`) REFERENCES `project_runs` (`id`),
  CONSTRAINT `design_code_mappings_chk_node` CHECK ((`node_id` IS NULL AND `node_name` IS NULL AND `code_symbol` IS NULL)
    OR (`node_id` IS NOT NULL AND `node_name` IS NOT NULL AND `code_symbol` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
