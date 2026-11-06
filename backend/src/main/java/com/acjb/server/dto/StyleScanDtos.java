package com.acjb.server.dto;

import java.time.Instant;
import java.util.Locale;

import com.acjb.server.domain.StyleScan;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** POST /style-mismatches/scan, GET /style-mismatches/scans/{scanId} */
public final class StyleScanDtos {

    private StyleScanDtos() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScanRequest(String frameId, String nodeId, String targetUrl, String selector) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Accepted(String scanId, String status) {
        public static Accepted of(StyleScan s) {
            return new Accepted(String.valueOf(s.getId()), lower(s));
        }
    }

    /** error_message는 failed일 때만 값이 있다 — 사용자가 selector·URL을 고칠 수 있게 이유를 보여 준다 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Status(String status, int mismatchCount, Instant finishedAt, String errorMessage) {
        public static Status of(StyleScan s) {
            return new Status(lower(s), s.getMismatchCount(), s.getFinishedAt(), s.getErrorMessage());
        }
    }

    private static String lower(StyleScan s) {
        return s.getStatus().name().toLowerCase(Locale.ROOT);
    }
}
