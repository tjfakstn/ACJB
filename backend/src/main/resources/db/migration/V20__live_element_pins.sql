-- 2026-11-20 스니펫(embed.js)으로 실시간 배포 화면의 요소를 골라 등록(SPEC AC31, location_basis LIVE_ELEMENT)
-- 핀은 그 요소(CSS 경로·글자·상자)에 붙어 스크롤·화면 변화를 따라간다. 요소 열은 V19에서 만들었다
ALTER TABLE `manual_qa_pins` DROP CHECK `manual_qa_pins_chk_basis`;
ALTER TABLE `manual_qa_pins` ADD CONSTRAINT `manual_qa_pins_chk_basis`
  CHECK (`location_basis` IN ('LIVE_OVERLAY', 'CAPTURE_IMAGE', 'PAGE_CAPTURE', 'LIVE_ELEMENT', 'NONE'));
