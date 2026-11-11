package com.acjb.server.domain;

/**
 * 정해진 종류 외의 알림을 만들지 않는다(SPEC 6.1 N5).
 * RESOLVED(재검증 pass → 담당 팀)·RUN_FAILED(비교 분석·첫 QA·Re-QA 실패 → 실행한 사람)는 알림 UI 시안(NTF, 2026-11-11)에 맞춰 추가했다
 */
public enum NotificationEvent {
    ISSUE_CREATED, FIXED, RECHECK_FAILED, RESOLVED, RUN_FAILED
}
