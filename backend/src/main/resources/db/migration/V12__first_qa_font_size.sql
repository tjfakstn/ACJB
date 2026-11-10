-- 2026-11-10 첫 QA 실제 비교기(docs/spikes/2026-11-10_first-qa-comparator.md, SPEC 4절·AC23).
-- StyleMismatch.property에 FONT_SIZE를 더한다. 첫 QA의 node_id는 매핑 노드 안 하위 요소 id(인스턴스 하위 "I1:2;3:4" 포함)일 수 있다
ALTER TABLE `style_mismatches` DROP CHECK `style_mismatches_chk_2`;
ALTER TABLE `style_mismatches` ADD CONSTRAINT `style_mismatches_chk_2`
  CHECK (`property` IN ('COLOR', 'FONT_WEIGHT', 'FONT_SIZE', 'FONT_FAMILY', 'RADIUS', 'SHADOW', 'SPACING', 'TEXT'));
-- 인스턴스 하위 id는 중첩되면 길어진다
ALTER TABLE `style_mismatches` MODIFY `node_id` varchar(200) DEFAULT NULL;
