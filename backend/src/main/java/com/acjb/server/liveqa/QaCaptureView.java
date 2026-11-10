package com.acjb.server.liveqa;

import java.time.Instant;
import java.util.Locale;

import com.acjb.server.domain.QaCapture;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi QaCapture — 이미지 바이트는 담지 않는다(GET .../image로 따로 읽는다) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record QaCaptureView(String id, String kind, String pageUrl, int width, int height, Instant createdAt) {

    static QaCaptureView of(QaCapture c) {
        return new QaCaptureView(String.valueOf(c.getId()), c.getKind().name().toLowerCase(Locale.ROOT), c.getPageUrl(), c.getWidth(),
            c.getHeight(), c.getCreatedAt());
    }
}
