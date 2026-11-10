-- 2026-11-07 Figma는 페이지 공유 링크 하나를 받는다(docs/decision_log.md). 분석할 페이지를 저장한다.
ALTER TABLE `projects`
  ADD COLUMN `figma_page_id` varchar(50) DEFAULT NULL,
  ADD COLUMN `figma_page_name` varchar(255) DEFAULT NULL;
