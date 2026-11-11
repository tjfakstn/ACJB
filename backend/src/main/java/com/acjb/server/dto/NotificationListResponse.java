package com.acjb.server.dto;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi NotificationListResponse.data — 페이지네이션은 v1에 없어 total은 items 개수와 같다. unread_count는 종 아이콘 배지용(2026-11-11) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NotificationListResponse(List<NotificationResponse> items, int total, int unreadCount) {
}
