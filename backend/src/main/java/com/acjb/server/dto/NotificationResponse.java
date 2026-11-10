package com.acjb.server.dto;

import java.time.Instant;
import java.util.Locale;

import com.acjb.server.domain.Notification;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi Notification — 받는 사람은 DB에서 recipient_id, API에서는 to_member_id */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NotificationResponse(String id, String qaIssueId, String event, String channel, String toMemberId, Instant createdAt) {

    public static NotificationResponse of(Notification n) {
        return new NotificationResponse(String.valueOf(n.getId()), String.valueOf(n.getIssue().getId()),
            n.getEvent().name().toLowerCase(Locale.ROOT), n.getChannel(), String.valueOf(n.getRecipient().getId()),
            n.getCreatedAt());
    }
}
