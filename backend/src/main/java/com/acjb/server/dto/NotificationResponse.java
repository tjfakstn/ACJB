package com.acjb.server.dto;

import java.time.Instant;
import java.util.Locale;

import com.acjb.server.domain.ManualQaPin;
import com.acjb.server.domain.Notification;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.QAIssue;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * openapi Notification — 받는 사람은 DB에서 recipient_id, API에서는 to_member_id.
 * 알림 패널(시안 NTF, 2026-11-11)이 이슈를 따로 조회하지 않고 문구를 만들 수 있게 이슈·실행 요약을 함께 담는다
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NotificationResponse(String id, String projectId, String qaIssueId, String event, String cause, String channel,
                                   String toMemberId, String actorId, String actorName, Instant createdAt, Instant readAt,
                                   IssueSummary issue, RunSummary run) {

    /** 화면 제목은 클라이언트가 이슈 목록과 같은 규칙으로 만든다(수동 QA 제목, 스타일 차이 속성, 설명) */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IssueSummary(String id, String status, String source, String assignedTeam, String description,
                               String screenLocation, String mismatchProperty, String manualTitle, Integer pinNumber) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunSummary(String id, String kind, String errorMessage) {
    }

    public static NotificationResponse of(Notification n) {
        return of(n, null);
    }

    public static NotificationResponse of(Notification n, ManualQaPin pin) {
        QAIssue i = n.getIssue();
        ProjectRun r = n.getRun();
        IssueSummary issue = i == null ? null : new IssueSummary(String.valueOf(i.getId()), lower(i.getStatus()), lower(i.getSource()),
            lower(i.getAssignedTeam()), i.getDescription(), i.getScreenLocation(),
            i.getStyleMismatch() == null ? null : lower(i.getStyleMismatch().getProperty()),
            pin == null ? null : pin.getTitle(), pin == null ? null : pin.getPinNumber());
        RunSummary run = r == null ? null : new RunSummary(String.valueOf(r.getId()), lower(r.getKind()), r.getErrorMessage());
        return new NotificationResponse(String.valueOf(n.getId()), String.valueOf(n.getProject().getId()),
            i == null ? null : String.valueOf(i.getId()), lower(n.getEvent()), lower(n.getCause()), n.getChannel(),
            String.valueOf(n.getRecipient().getId()), n.getActor() == null ? null : String.valueOf(n.getActor().getId()),
            n.getActor() == null ? null : n.getActor().getName(), n.getCreatedAt(), n.getReadAt(), issue, run);
    }

    private static String lower(Enum<?> e) {
        return e == null ? null : e.name().toLowerCase(Locale.ROOT);
    }
}
