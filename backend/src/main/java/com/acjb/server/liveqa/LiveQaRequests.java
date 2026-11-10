package com.acjb.server.liveqa;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** 실시간 QA 워크스페이스 요청 본문(AC29~AC31). 값 검사는 LiveQaValidation에서 한다 — 빠진 값을 400 메시지로 알려 주려고 */
public final class LiveQaRequests {

    private LiveQaRequests() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeployUrlRequest(String url) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ViewportInput(Integer width, Integer height) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnchorInput(Integer xPx, Integer yPx) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CaptureRequest(String pagePath, ViewportInput viewport) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScreenLinkRequest(String pagePath) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManualQaRequest(String clientRequestId, String title, String description, String pagePath, AnchorInput anchor,
                                  ViewportInput viewport, String locationBasis, String captureId, String captureStatus, String severity,
                                  String expectedResult, String actualResult, String figmaFrameId,
                                  java.util.List<String> reproSteps) {
    }
}
