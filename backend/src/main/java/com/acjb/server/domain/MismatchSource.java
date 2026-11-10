package com.acjb.server.domain;

/** StyleMismatch가 어느 경로로 기록됐는지 — 사람이 입력(manual), scan이 추출, 첫 QA가 근거로 저장(first_qa, AC23) */
public enum MismatchSource {
    MANUAL, SCAN, FIRST_QA
}
