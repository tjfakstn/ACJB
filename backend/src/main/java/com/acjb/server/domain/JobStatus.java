package com.acjb.server.domain;

/** Figma 동기화·scan 같은 비동기 작업의 상태(openapi `status`: queued/running/done/failed) */
public enum JobStatus {
    QUEUED, RUNNING, DONE, FAILED
}
