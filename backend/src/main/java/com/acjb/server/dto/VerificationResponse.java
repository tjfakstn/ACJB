package com.acjb.server.dto;

import java.time.Instant;
import java.util.Locale;

import com.acjb.server.domain.Verification;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi Verification — source(manual/re_qa)·observed_value는 2026-11-08 자동 Re-QA(AC28) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VerificationResponse(String id, String qaIssueId, String result, String scope, String source, String observedValue,
                                   String verifiedBy, Instant verifiedAt) {

    public static VerificationResponse of(Verification v) {
        return new VerificationResponse(String.valueOf(v.getId()), String.valueOf(v.getIssue().getId()),
            v.getResult().name().toLowerCase(Locale.ROOT), v.getScope(), v.getSource().name().toLowerCase(Locale.ROOT), v.getObservedValue(),
            String.valueOf(v.getVerifiedBy().getId()),
            v.getVerifiedAt());
    }
}
