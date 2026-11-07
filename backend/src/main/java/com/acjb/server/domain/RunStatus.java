package com.acjb.server.domain;

/** project_runs.status. 스타일 scan의 JobStatus(done)와 달리 openapi ProjectRun은 succeeded를 쓴다 */
public enum RunStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED
}
