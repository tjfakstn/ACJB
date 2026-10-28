package com.acjb.server.domain;

/** needs_check → fixed → (pass) resolved / (fail) needs_recheck → fixed. 이 4개 외의 값을 추가하지 않는다(SPEC 6.1 N11) */
public enum IssueStatus {
    NEEDS_CHECK, FIXED, NEEDS_RECHECK, RESOLVED
}
