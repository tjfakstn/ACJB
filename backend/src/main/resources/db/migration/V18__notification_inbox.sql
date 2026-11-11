-- 알림 UI(시안 NTF, 2026-11-11): 읽음 처리, 재검증 pass·실행 실패 알림, 알림을 일으킨 사람·계기
-- 실행 실패 알림은 이슈가 없으므로 qa_issue_id를 비울 수 있게 하고 project_id를 직접 둔다

ALTER TABLE `notifications`
  ADD COLUMN `project_id` bigint NULL,
  ADD COLUMN `run_id` bigint NULL,
  ADD COLUMN `actor_id` bigint NULL,
  ADD COLUMN `cause` varchar(20) NULL,
  ADD COLUMN `read_at` datetime(6) NULL,
  MODIFY `qa_issue_id` bigint NULL;

UPDATE `notifications` n JOIN `qa_issues` i ON n.qa_issue_id = i.id SET n.project_id = i.project_id;

-- 예전 issue_created의 계기는 기록이 없다 — 이슈 등록과 같은 순간(5초 안)에 생긴 것만 등록 때로 본다
UPDATE `notifications` n JOIN `qa_issues` i ON n.qa_issue_id = i.id
  SET n.cause = IF(ABS(TIMESTAMPDIFF(SECOND, i.created_at, n.created_at)) <= 5, 'REGISTERED', 'TEAM_ASSIGNED')
  WHERE n.event = 'ISSUE_CREATED';

ALTER TABLE `notifications`
  MODIFY `project_id` bigint NOT NULL,
  DROP CHECK `notifications_chk_1`,
  ADD CONSTRAINT `notifications_chk_event` CHECK (`event` IN ('ISSUE_CREATED', 'FIXED', 'RECHECK_FAILED', 'RESOLVED', 'RUN_FAILED')),
  ADD CONSTRAINT `notifications_chk_cause` CHECK (`cause` IS NULL OR `cause` IN ('REGISTERED', 'TEAM_ASSIGNED')),
  -- 실행 실패만 이슈 없이 실행을 가리킨다
  ADD CONSTRAINT `notifications_chk_target` CHECK ((`event` = 'RUN_FAILED' AND `run_id` IS NOT NULL AND `qa_issue_id` IS NULL)
                                                 OR (`event` <> 'RUN_FAILED' AND `qa_issue_id` IS NOT NULL)),
  ADD CONSTRAINT `fk_notifications_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  ADD CONSTRAINT `fk_notifications_run` FOREIGN KEY (`run_id`) REFERENCES `project_runs` (`id`),
  ADD CONSTRAINT `fk_notifications_actor` FOREIGN KEY (`actor_id`) REFERENCES `members` (`id`),
  -- 받는 사람 인덱스(idx_notifications_recipient_created)는 recipient FK가 쓰므로 그대로 둔다
  ADD KEY `idx_notifications_project_recipient_created` (`project_id`, `recipient_id`, `created_at` DESC, `id` DESC);
