package com.acjb.server.dto;

import com.acjb.server.domain.Project;

/** openapi Project */
public record ProjectResponse(String id, String name) {

    public static ProjectResponse of(Project p) {
        return new ProjectResponse(String.valueOf(p.getId()), p.getName());
    }
}
