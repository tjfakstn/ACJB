package com.acjb.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi DesignCodeMappingListResponse.data — AC27 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MappingListResponse(ProjectRunView run, String commitSha, List<FrameMapping> items, List<UnmappedFrame> unmappedFrames) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FrameMapping(String frameId, String frameName, String codePath, List<NodeMapping> nodes) {
    }

    /** 비교 분석이 코드 파일을 찾지 못한 화면 — 이슈가 아니라 확인해 볼 곳(2026-11-10). section은 속한 Figma 섹션 이름(없으면 null) */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UnmappedFrame(String frameId, String frameName, String section) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NodeMapping(String nodeId, String nodeName, String codePath, String codeSymbol) {
    }
}
