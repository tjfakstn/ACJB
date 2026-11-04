package com.acjb.server.dto;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.acjb.server.domain.Environment;
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
    List<StatusHistoryView> statusHistory,
    String assignedTo,
    String reportedBy,
    Instant createdAt,
    Instant updatedAt
) {

    /** 지연 로딩 컬렉션을 읽으므로 트랜잭션 안에서 호출한다 */
    public static QaIssueResponse of(QAIssue issue, List<StatusHistory> history) {
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
            history.stream().map(StatusHistoryView::of).toList(),
            idOf(issue.getAssignedTo()),
            idOf(issue.getReportedBy()),
            issue.getCreatedAt(),
            issue.getUpdatedAt());
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
    public record StyleMismatchView(String id, String frameId, String screen, String property, String expectedValue, String actualValue) {
        static StyleMismatchView of(StyleMismatch m) {
            return m == null ? null
                : new StyleMismatchView(String.valueOf(m.getId()), m.getFrameId(), m.getScreen(), lower(m.getProperty()),
                    m.getExpectedValue(), m.getActualValue());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatusHistoryView(String status, String changedBy, Instant changedAt) {
        static StatusHistoryView of(StatusHistory h) {
            return new StatusHistoryView(lower(h.getStatus()), idOf(h.getChangedBy()), h.getChangedAt());
        }
    }
}
