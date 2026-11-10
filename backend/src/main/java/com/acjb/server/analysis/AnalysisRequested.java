package com.acjb.server.analysis;

/** 비교 분석 요청이 커밋된 뒤 AnalysisJob을 깨우는 이벤트 */
public record AnalysisRequested(Long runId) {
}
