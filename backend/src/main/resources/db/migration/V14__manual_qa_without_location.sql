-- 2026-11-10 수동 QA 위치 없이 등록(SPEC AC31, location_basis NONE) — 배포 화면이 없으면 좌표·화면 크기·경로가 없다
ALTER TABLE `manual_qa_pins`
  MODIFY `page_path` varchar(500) DEFAULT NULL,
  MODIFY `x_px` int DEFAULT NULL,
  MODIFY `y_px` int DEFAULT NULL,
  MODIFY `viewport_width` int DEFAULT NULL,
  MODIFY `viewport_height` int DEFAULT NULL;
ALTER TABLE `manual_qa_pins` DROP CHECK `manual_qa_pins_chk_basis`;
ALTER TABLE `manual_qa_pins` ADD CONSTRAINT `manual_qa_pins_chk_basis` CHECK (`location_basis` IN ('LIVE_OVERLAY', 'CAPTURE_IMAGE', 'NONE'));
-- 위치가 있는 등록은 좌표·화면 크기·경로가 모두 있고, 위치 없는 등록은 좌표·화면 크기가 모두 없다
ALTER TABLE `manual_qa_pins` ADD CONSTRAINT `manual_qa_pins_chk_location` CHECK (
  (`location_basis` = 'NONE' AND `x_px` IS NULL AND `y_px` IS NULL AND `viewport_width` IS NULL AND `viewport_height` IS NULL)
  OR (`location_basis` <> 'NONE' AND `x_px` IS NOT NULL AND `y_px` IS NOT NULL AND `viewport_width` IS NOT NULL
      AND `viewport_height` IS NOT NULL AND `page_path` IS NOT NULL));
