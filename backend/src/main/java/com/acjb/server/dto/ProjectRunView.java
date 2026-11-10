package com.acjb.server.dto;

import java.time.Instant;
import java.util.Locale;

import com.acjb.server.domain.ProjectRun;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi ProjectRun — 비교 분석·첫 QA·Re-QA 실행 한 건 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProjectRunView(String id, String kind, String status, int createdCount, int droppedCount, Integer failedCount,
                             java.util.List<String> scope, boolean demo, String errorMessage, Instant createdAt, Instant finishedAt) {

    public static ProjectRunView of(ProjectRun r) {
        // 이제 없는 범위(2026-11-10 이전의 layout·presence)는 보여 주지 않는다
        java.util.List<String> scope = r.getScope() == null || r.getScope().isBlank() ? null
            : java.util.Arrays.stream(r.getScope().split(",")).map(com.acjb.server.qa.QaScope::parse).flatMap(java.util.Optional::stream)
                .map(com.acjb.server.qa.QaScope::value).toList();
        return new ProjectRunView(String.valueOf(r.getId()), r.getKind().name().toLowerCase(Locale.ROOT),
            r.getStatus().name().toLowerCase(Locale.ROOT), r.getCreatedCount(), r.getDroppedCount(), r.getFailedCount(), scope, r.isDemo(),
            r.getErrorMessage(), r.getCreatedAt(), r.getFinishedAt());
    }
}
