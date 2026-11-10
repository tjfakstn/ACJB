package com.acjb.server.dto;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.acjb.server.domain.FigmaFrame;
import com.acjb.server.domain.FigmaSync;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi FigmaSyncResponse·FigmaSyncStatusResponse·FigmaFrameListResponse의 data */
public final class FigmaDtos {

    private FigmaDtos() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SyncAccepted(String syncId, String status) {
        public static SyncAccepted of(FigmaSync s) {
            return new SyncAccepted(String.valueOf(s.getId()), lower(s));
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SyncStatus(String status, int frameCount, Instant finishedAt) {
        public static SyncStatus of(FigmaSync s) {
            return new SyncStatus(lower(s), s.getFrameCount(), s.getFinishedAt());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Frame(String frameId, String name, String frameUrl, Instant lastSyncedAt) {
        public static Frame of(FigmaFrame f) {
            return new Frame(f.getFrameId(), f.getName(), f.getFrameUrl(), f.getLastSyncedAt());
        }
    }

    public record FrameList(List<Frame> items) {
    }

    private static String lower(FigmaSync s) {
        return s.getStatus().name().toLowerCase(Locale.ROOT);
    }
}
