package com.acjb.server.domain;

/**
 * needs_check → (in_progress) → fixed → (pass) resolved / (fail) needs_recheck → (in_progress) → fixed.
 * in_progress(수정 중)는 2026-11-07 추가(V4). ontology.yaml에 없는 값은 추가하지 않는다(SPEC 6.1 N11)
 */
public enum IssueStatus {
    NEEDS_CHECK, IN_PROGRESS, FIXED, NEEDS_RECHECK, RESOLVED
}
