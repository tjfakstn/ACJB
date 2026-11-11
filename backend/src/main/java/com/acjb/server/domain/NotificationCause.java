package com.acjb.server.domain;

/**
 * issue_created 알림이 생긴 계기(2026-11-11) — 종류는 하나로 두고 화면 문구만 나눈다(시안 "새 QA 문제" / "담당 팀 배정").
 * 다른 종류의 알림에는 쓰지 않는다(null)
 */
public enum NotificationCause {
    /** 담당 팀을 지정해 등록했다 */
    REGISTERED,
    /** 등록된 이슈에 담당 팀을 새로 지정하거나 바꿨다 */
    TEAM_ASSIGNED
}
