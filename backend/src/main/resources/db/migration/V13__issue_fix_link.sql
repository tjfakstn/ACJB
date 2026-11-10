-- 2026-11-10 이슈 상세 코드 추적(SPEC AC34). 담당 팀이 붙이는 수정 PR·커밋 주소 — 이슈에 하나, GitHub는 읽기만 한다
ALTER TABLE `qa_issues`
  ADD COLUMN `fix_link_url` varchar(500) DEFAULT NULL,
  ADD COLUMN `fix_link_by_id` bigint DEFAULT NULL,
  ADD COLUMN `fix_link_at` datetime(6) DEFAULT NULL,
  ADD CONSTRAINT `fk_qa_issues_fix_link_by` FOREIGN KEY (`fix_link_by_id`) REFERENCES `members` (`id`);
