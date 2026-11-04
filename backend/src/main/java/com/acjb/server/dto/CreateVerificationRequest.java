package com.acjb.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * POST /verifications body. 재현 절차는 받지 않는다(AC5) — repro_steps 등 정의되지 않은 필드가 와도 무시하고
 * 원본을 그대로 쓴다(N6, 골든 케이스 ng-006). verified_by·verified_at도 받지 않는다(N8).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateVerificationRequest(String result, String scope) {
}
