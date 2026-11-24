package com.acjb.server.liveqa;

import java.time.Instant;
import java.util.Locale;

import com.acjb.server.domain.QaCapture;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi QaCapture — 이미지 바이트는 담지 않는다(GET .../image로 따로 읽는다) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record QaCaptureView(String id, String kind, String pageUrl, int width, int height, Instant createdAt, boolean fullPage,
                            Integer viewportHeight) {

    /** width·height는 이미지 크기. 전체 페이지 캡처(full_page)면 height는 페이지 높이, viewport_height는 브라우저 화면 높이 */
    static QaCaptureView of(QaCapture c) {
        return new QaCaptureView(String.valueOf(c.getId()), c.getKind().name().toLowerCase(Locale.ROOT), c.getPageUrl(), c.getWidth(),
            c.getHeight(), c.getCreatedAt(), c.isFullPage(), c.getViewportHeight());
    }
}
