package com.acjb.server.dto;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.acjb.server.domain.Invitation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi Invitation 관련 요청·응답. 응답에는 토큰을 담지 않는다(N14) */
public final class InvitationDtos {

    private InvitationDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateRequest(String email) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AcceptRequest(String role) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record View(String id, String projectId, String projectName, String email, String status, String invitedBy,
                       String invitedByName, Instant expiresAt, Instant createdAt) {
        public static View of(Invitation i, Instant now) {
            return new View(String.valueOf(i.getId()), String.valueOf(i.getProject().getId()), i.getProject().getName(),
                i.getEmail(), i.effectiveStatus(now).name().toLowerCase(Locale.ROOT), String.valueOf(i.getInvitedBy().getId()),
                i.getInvitedBy().getName(), i.getExpiresAt(), i.getCreatedAt());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Preview(String projectName, String projectDescription, String invitedByName, String email, String status,
                          Instant expiresAt) {
        public static Preview of(Invitation i, Instant now) {
            return new Preview(i.getProject().getName(), i.getProject().getDescription(), i.getInvitedBy().getName(), i.getEmail(),
                i.effectiveStatus(now).name().toLowerCase(Locale.ROOT), i.getExpiresAt());
        }
    }

    public record ViewList(List<View> items) {
    }
}
