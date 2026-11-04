package com.acjb.server.dto;

import java.util.List;

/** openapi NotificationListResponse.data — 페이지네이션은 v1에 없어 total은 items 개수와 같다 */
public record NotificationListResponse(List<NotificationResponse> items, int total) {
}
