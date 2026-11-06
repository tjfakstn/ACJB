package com.acjb.server.dto;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi RecheckSuggestionResponse.data (AC20) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RecheckSuggestionResponse(String issueId, List<Suggestion> suggestions, int searchedCount, int droppedCount) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Suggestion(String screen, String reason, List<Evidence> evidence) {
    }

    /** type: qa_issue / style_mismatch / verification, id: 그 type의 id(문자열) */
    public record Evidence(String type, String id) {
    }
}
