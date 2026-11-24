-- 2026-11-20 수동 QA를 서버 캡처 위에서 등록(SPEC AC30·AC31, location_basis PAGE_CAPTURE)
-- 배포 화면은 다른 도메인 iframe이라 스크롤·요소를 읽을 수 없어 핀이 화면 조작을 따라가지 못했다.
-- 등록할 때 서버가 전체 페이지를 찍고 그때의 요소 위치를 함께 남겨, 핀을 그 캡처 위의 좌표와 요소로 저장한다.
ALTER TABLE `qa_captures`
  ADD COLUMN `full_page` tinyint(1) NOT NULL DEFAULT 0,
  ADD COLUMN `viewport_height` int DEFAULT NULL,
  ADD COLUMN `elements` mediumtext DEFAULT NULL;

ALTER TABLE `manual_qa_pins` DROP CHECK `manual_qa_pins_chk_basis`;
ALTER TABLE `manual_qa_pins` ADD CONSTRAINT `manual_qa_pins_chk_basis`
  CHECK (`location_basis` IN ('LIVE_OVERLAY', 'CAPTURE_IMAGE', 'PAGE_CAPTURE', 'NONE'));

ALTER TABLE `manual_qa_pins`
  ADD COLUMN `element_selector` varchar(500) DEFAULT NULL,
  ADD COLUMN `element_text` varchar(200) DEFAULT NULL,
  ADD COLUMN `element_x` int DEFAULT NULL,
  ADD COLUMN `element_y` int DEFAULT NULL,
  ADD COLUMN `element_width` int DEFAULT NULL,
  ADD COLUMN `element_height` int DEFAULT NULL;
