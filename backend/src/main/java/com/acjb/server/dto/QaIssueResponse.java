package com.acjb.server.dto;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.acjb.server.domain.Environment;
import com.acjb.server.domain.ManualQaPin;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.StatusHistory;
import com.acjb.server.domain.StyleMismatch;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi QAIssue. id·멤버 id는 문자열로, enum은 소문자 값(needs_check 등)으로 내보낸다 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record QaIssueResponse(
    String id,
    String projectId,
    String description,
    String screenLocation,
    List<String> reproSteps,
    String platformScope,
    EnvironmentView environment,
    List<String> screenshots,
    String figmaLink,
    StyleMismatchView styleMismatch,
    String status,
    String source,
    List<StatusHistoryView> statusHistory,
    String assignedTeam,
    String reportedBy,
    Instant createdAt,
    Instant updatedAt,
    ManualQaView manualQa
) {

    /** 지연 로딩 컬렉션을 읽으므로 트랜잭션 안에서 호출한다. 수동 QA 위치(핀)가 없는 이슈용 */
    public static QaIssueResponse of(QAIssue issue, List<StatusHistory> history) {
        return of(issue, history, null);
    }

    /** pin — 실시간 QA 워크스페이스에서 위치를 찍어 등록한 이슈면 그 핀(AC31), 아니면 null */
    public static QaIssueResponse of(QAIssue issue, List<StatusHistory> history, ManualQaPin pin) {
        return new QaIssueResponse(
            String.valueOf(issue.getId()),
            String.valueOf(issue.getProject().getId()),
            issue.getDescription(),
            issue.getScreenLocation(),
            List.copyOf(issue.getReproSteps()),
            lower(issue.getPlatformScope()),
            EnvironmentView.of(issue.getEnvironment()),
            List.copyOf(issue.getScreenshots()),
            issue.getFigmaLink(),
            StyleMismatchView.of(issue.getStyleMismatch()),
            lower(issue.getStatus()),
            lower(issue.getSource()),
            history.stream().map(StatusHistoryView::of).toList(),
            lower(issue.getAssignedTeam()),
            idOf(issue.getReportedBy()),
            issue.getCreatedAt(),
            issue.getUpdatedAt(),
            ManualQaView.of(pin));
    }

    static String lower(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    static String idOf(Member member) {
        return member == null ? null : String.valueOf(member.getId());
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EnvironmentView(String platform, String device, String browser, String deployStage) {
        static EnvironmentView of(Environment env) {
            return env == null ? null
                : new EnvironmentView(lower(env.getPlatform()), env.getDevice(), env.getBrowser(), lower(env.getDeployStage()));
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StyleMismatchView(String id, String frameId, String nodeId, String screen, String property, String expectedValue,
                                    String actualValue) {
        public static StyleMismatchView of(StyleMismatch m) {
            return m == null ? null
                : new StyleMismatchView(String.valueOf(m.getId()), m.getFrameId(), m.getNodeId(), m.getScreen(), lower(m.getProperty()),
                    m.getExpectedValue(), m.getActualValue());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatusHistoryView(String status, String changedBy, Instant changedAt) {
        static StatusHistoryView of(StatusHistory h) {
            return new StatusHistoryView(lower(h.getStatus()), idOf(h.getChangedBy()), h.getChangedAt());
        }
    }

    /** openapi ManualQaPin — 비율은 등록 때 화면 크기 기준(소수 넷째 자리) */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManualQaView(int pinNumber, String title, String pagePath, String pageUrl, AnchorView anchor, ViewportView viewport,
                               String locationBasis, String captureStatus, String captureId, String severity, String expectedResult,
                               String actualResult, FigmaFrameView figmaFrame) {
        static ManualQaView of(ManualQaPin p) {
            if (p == null) {
                return null;
            }
            return new ManualQaView(p.getPinNumber(), p.getTitle(), p.getPagePath(), p.getPageUrl(),
                // 위치 없이 등록(none)하면 좌표·화면 크기가 없다
                p.getXPx() == null ? null
                    : new AnchorView(p.getXPx(), p.getYPx(), ratio(p.getXPx(), p.getViewportWidth()), ratio(p.getYPx(), p.getViewportHeight())),
                p.getViewportWidth() == null ? null : new ViewportView(p.getViewportWidth(), p.getViewportHeight()),
                lower(p.getLocationBasis()), lower(p.getCaptureStatus()),
                p.getCapture() == null ? null : String.valueOf(p.getCapture().getId()), lower(p.getSeverity()), p.getExpectedResult(),
                p.getActualResult(), p.getFigmaFrameId() == null ? null : new FigmaFrameView(p.getFigmaFrameId(), p.getFigmaFrameName()));
        }

        private static double ratio(int v, int size) {
            return Math.round((double) v / size * 10000) / 10000.0;
        }
    }

    /** 수동 QA를 등록할 때 고른 기준 Figma 프레임(AC33) */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FigmaFrameView(String frameId, String frameName) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnchorView(int xPx, int yPx, double xRatio, double yRatio) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ViewportView(int width, int height) {
    }
}
