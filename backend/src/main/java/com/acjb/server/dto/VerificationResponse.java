package com.acjb.server.dto;

import java.time.Instant;
import java.util.Locale;

import com.acjb.server.domain.Verification;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi Verification */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VerificationResponse(String id, String qaIssueId, String result, String scope, String verifiedBy, Instant verifiedAt) {

    public static VerificationResponse of(Verification v) {
        return new VerificationResponse(String.valueOf(v.getId()), String.valueOf(v.getIssue().getId()),
            v.getResult().name().toLowerCase(Locale.ROOT), v.getScope(), String.valueOf(v.getVerifiedBy().getId()),
            v.getVerifiedAt());
    }
}
