-- 2026-11-10 배포 화면 경로 ↔ Figma 프레임 연결(docs/decision_log.md, SPEC AC33·N19).

-- 1) 이 경로의 화면은 이 프레임을 보고 구현했다 — 소유자가 지정. 프레임 하나에 경로 하나, 한 경로에 여러 프레임(상태별 시안)
CREATE TABLE `screen_frame_links` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `frame_id` varchar(50) NOT NULL,
  `frame_name` varchar(255) NOT NULL,
  `page_path` varchar(500) NOT NULL,
  `updated_by_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_screen_frame_links_frame` (`project_id`, `frame_id`),
  CONSTRAINT `fk_screen_frame_links_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `fk_screen_frame_links_updated_by` FOREIGN KEY (`updated_by_id`) REFERENCES `members` (`id`)
);

-- 2) 수동 QA를 등록할 때 사람이 확인한 기준 프레임. 이름은 그때 값(나중에 시안 이름이 바뀌어도 기록은 그대로)
ALTER TABLE `manual_qa_pins`
  ADD COLUMN `figma_frame_id` varchar(50) DEFAULT NULL,
  ADD COLUMN `figma_frame_name` varchar(255) DEFAULT NULL,
  ADD CONSTRAINT `manual_qa_pins_chk_frame` CHECK ((`figma_frame_id` IS NULL AND `figma_frame_name` IS NULL)
    OR (`figma_frame_id` IS NOT NULL AND `figma_frame_name` IS NOT NULL));
