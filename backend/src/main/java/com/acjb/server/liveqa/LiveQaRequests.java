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

    /** 스니펫이 알려 준 요소(live_element, 2026-11-20) — 상자는 페이지 맨 위 기준 픽셀 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ElementInput(String selector, String text, BoxInput box) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BoxInput(Integer x, Integer y, Integer width, Integer height) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    /** full_page(2026-11-20): true면 페이지 전체를 찍고 요소 위치를 함께 남긴다 — 수동 QA를 캡처 위에서 등록할 때 */
    public record CaptureRequest(String pagePath, ViewportInput viewport, Boolean fullPage) {
        public CaptureRequest(String pagePath, ViewportInput viewport) {
            this(pagePath, viewport, null);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScreenLinkRequest(String pagePath) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManualQaRequest(String clientRequestId, String title, String description, String pagePath, AnchorInput anchor,
                                  ViewportInput viewport, String locationBasis, String captureId, String captureStatus, String severity,
                                  String expectedResult, String actualResult, String figmaFrameId,
                                  java.util.List<String> reproSteps, String platform, String platformScope, ElementInput element) {
        public ManualQaRequest(String clientRequestId, String title, String description, String pagePath, AnchorInput anchor,
                               ViewportInput viewport, String locationBasis, String captureId, String captureStatus, String severity,
                               String expectedResult, String actualResult, String figmaFrameId,
                               java.util.List<String> reproSteps, String platform, String platformScope) {
            this(clientRequestId, title, description, pagePath, anchor, viewport, locationBasis, captureId, captureStatus, severity,
                expectedResult, actualResult, figmaFrameId, reproSteps, platform, platformScope, null);
        }
    }
}
