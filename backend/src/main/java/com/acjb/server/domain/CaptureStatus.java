package com.acjb.server.domain;

/** 수동 QA의 캡처 상태 — 실패를 숨기지 않는다(N19) */
public enum CaptureStatus {
    CAPTURED, FAILED, USER_ATTACHED, NONE
}
