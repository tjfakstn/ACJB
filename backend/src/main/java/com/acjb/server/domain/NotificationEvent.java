package com.acjb.server.domain;

/** 정해진 3개 외의 알림 종류를 만들지 않는다(SPEC 6.1 N5) */
public enum NotificationEvent {
    ISSUE_CREATED, FIXED, RECHECK_FAILED
}
