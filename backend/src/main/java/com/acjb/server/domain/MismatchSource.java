package com.acjb.server.domain;

/** StyleMismatch가 어느 경로로 기록됐는지 — 사람이 입력(manual)했는지, scan이 추출했는지 */
public enum MismatchSource {
    MANUAL, SCAN
}
