package com.acjb.server.dto;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi ParseResponse.data — context는 IssueReportContext, missing은 되물어야 할 필드 이름(AC8) */
public record ParseResponse(Context context, List<String> missing) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Context(String screen, String element, String symptom, List<String> reproSteps, String platformScope) {
    }
}
