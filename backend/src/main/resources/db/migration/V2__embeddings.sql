-- V2: 재검증 범위 제안(AC20) 검색용 임베딩 (2026-11-06, 설계 docs/design-recheck-suggestions.md 4절)
-- source_id는 세 테이블(qa_issues·style_mismatches·verifications)을 가리키는 다형 참조라 FK를 걸지 않는다.
-- 원본은 v1에서 삭제되지 않는다. vector는 float32 little-endian 배열.

CREATE TABLE `embeddings` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `source_type` varchar(20) NOT NULL,
  `source_id` bigint NOT NULL,
  `model` varchar(40) NOT NULL,
  `vector` blob NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_embeddings_source` (`source_type`,`source_id`,`model`),
  KEY `idx_embeddings_project_model` (`project_id`,`model`),
  CONSTRAINT `fk_embeddings_project_id` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `embeddings_chk_1` CHECK ((`source_type` in (_utf8mb4'QA_ISSUE',_utf8mb4'STYLE_MISMATCH',_utf8mb4'VERIFICATION')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
