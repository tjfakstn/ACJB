-- 2026-11-10 실시간 QA 워크스페이스(docs/decision_log.md, SPEC AC29~AC31·N19).

-- 1) 배포 주소 — 소유자가 입력한 값. 비어 있으면 GitHub 최근 성공 배포 주소를 읽어 쓴다(저장하지 않는다)
ALTER TABLE `projects`
  ADD COLUMN `deploy_url` varchar(1000) DEFAULT NULL,
  ADD COLUMN `deploy_url_updated_at` datetime(6) DEFAULT NULL;

-- 2) 캡처 — 서버 브라우저 캡처(AUTO) 또는 사용자 첨부(UPLOAD). v1은 파일 저장소 없이 DB에 둔다
CREATE TABLE `qa_captures` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `kind` varchar(10) NOT NULL,
  `content_type` varchar(20) NOT NULL,
  `page_url` varchar(1500) DEFAULT NULL,
  `width` int NOT NULL,
  `height` int NOT NULL,
  `image` longblob NOT NULL,
  `created_by_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_qa_captures_project` (`project_id`),
  CONSTRAINT `fk_qa_captures_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `fk_qa_captures_created_by` FOREIGN KEY (`created_by_id`) REFERENCES `members` (`id`),
  CONSTRAINT `qa_captures_chk_kind` CHECK (`kind` IN ('AUTO', 'UPLOAD'))
);

-- 3) 수동 QA 위치(핀) — 이슈 1건에 0..1. 핀 번호와 요청 id는 프로젝트 안에서 유일하다(멱등 등록)
CREATE TABLE `manual_qa_pins` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `qa_issue_id` bigint NOT NULL,
  `pin_number` int NOT NULL,
  `client_request_id` varchar(64) NOT NULL,
  `title` varchar(100) NOT NULL,
  `page_path` varchar(500) NOT NULL,
  `page_url` varchar(1500) DEFAULT NULL,
  `x_px` int NOT NULL,
  `y_px` int NOT NULL,
  `viewport_width` int NOT NULL,
  `viewport_height` int NOT NULL,
  `location_basis` varchar(20) NOT NULL,
  `capture_status` varchar(20) NOT NULL,
  `capture_id` bigint DEFAULT NULL,
  `severity` varchar(10) DEFAULT NULL,
  `expected_result` varchar(1000) DEFAULT NULL,
  `actual_result` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_manual_qa_pins_issue` (`qa_issue_id`),
  UNIQUE KEY `uk_manual_qa_pins_number` (`project_id`, `pin_number`),
  UNIQUE KEY `uk_manual_qa_pins_request` (`project_id`, `client_request_id`),
  CONSTRAINT `fk_manual_qa_pins_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `fk_manual_qa_pins_issue` FOREIGN KEY (`qa_issue_id`) REFERENCES `qa_issues` (`id`),
  CONSTRAINT `fk_manual_qa_pins_capture` FOREIGN KEY (`capture_id`) REFERENCES `qa_captures` (`id`),
  CONSTRAINT `manual_qa_pins_chk_basis` CHECK (`location_basis` IN ('LIVE_OVERLAY', 'CAPTURE_IMAGE')),
  CONSTRAINT `manual_qa_pins_chk_capture` CHECK (`capture_status` IN ('CAPTURED', 'FAILED', 'USER_ATTACHED', 'NONE')),
  CONSTRAINT `manual_qa_pins_chk_severity` CHECK (`severity` IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW'))
);
