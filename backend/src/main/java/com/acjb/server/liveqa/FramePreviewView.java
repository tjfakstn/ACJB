package com.acjb.server.liveqa;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi FramePreview (AC32) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FramePreviewView(String frameId, String nodeId, String imageUrl, int width, int height, NodeBoxView nodeBox) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NodeBoxView(double xRatio, double yRatio, double wRatio, double hRatio) {
    }

    static FramePreviewView of(FramePreviewService.FramePreview p) {
        FramePreviewService.NodeBox b = p.nodeBox();
        return new FramePreviewView(p.frameId(), p.nodeId(), p.imageUrl(), p.width(), p.height(),
            b == null ? null : new NodeBoxView(b.xRatio(), b.yRatio(), b.wRatio(), b.hRatio()));
    }
}
