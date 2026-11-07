package com.acjb.server.dto;

import com.acjb.server.domain.Project;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi Project — owner_id는 멤버 제거 버튼을 보여줄지 판단용 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectResponse(String id, String name, String description, String ownerId, java.time.Instant createdAt) {

    public static ProjectResponse of(Project p) {
        return new ProjectResponse(String.valueOf(p.getId()), p.getName(), p.getDescription(), String.valueOf(p.getOwner().getId()),
            p.getCreatedAt());
    }
}
