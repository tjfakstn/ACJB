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
                             java.util.List<String> scope, java.util.List<String> addedScope, java.util.List<String> coveredScope,
                             Integer foundCount, Integer foundDroppedCount, boolean demo, String errorMessage, Instant createdAt,
                             Instant finishedAt) {

    public static ProjectRunView of(ProjectRun r) {
        return of(r, null);
    }

    /** coveredScope — first-qa·re-qa 응답에만: 이 프로젝트에서 지금까지 검사한 범위(AC28, 2026-11-10) */
    public static ProjectRunView of(ProjectRun r, java.util.List<String> coveredScope) {
        boolean reQa = r.getKind() == com.acjb.server.domain.RunKind.RE_QA;
        return new ProjectRunView(String.valueOf(r.getId()), r.getKind().name().toLowerCase(Locale.ROOT),
            r.getStatus().name().toLowerCase(Locale.ROOT), r.getCreatedCount(), r.getDroppedCount(), r.getFailedCount(), values(r.getScope()),
            reQa ? java.util.Objects.requireNonNullElse(values(r.getAddedScope()), java.util.List.of()) : null, coveredScope,
            r.getFoundCount(), r.getFoundDroppedCount(), r.isDemo(), r.getErrorMessage(), r.getCreatedAt(), r.getFinishedAt());
    }

    /** 이제 없는 범위(2026-11-10 이전의 layout·presence)는 보여 주지 않는다 */
    private static java.util.List<String> values(String csv) {
        return csv == null || csv.isBlank() ? null
            : java.util.Arrays.stream(csv.split(",")).map(com.acjb.server.qa.QaScope::parse).flatMap(java.util.Optional::stream)
                .map(com.acjb.server.qa.QaScope::value).toList();
    }
}
