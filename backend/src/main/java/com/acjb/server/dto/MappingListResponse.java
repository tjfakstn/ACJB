package com.acjb.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi DesignCodeMappingListResponse.data — AC27 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MappingListResponse(ProjectRunView run, String commitSha, List<FrameMapping> items) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FrameMapping(String frameId, String frameName, String codePath, List<NodeMapping> nodes) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NodeMapping(String nodeId, String nodeName, String codePath, String codeSymbol) {
    }
}
