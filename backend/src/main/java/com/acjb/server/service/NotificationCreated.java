package com.acjb.server.service;

/** 알림 한 건이 저장됐다는 사건(AC37) — 커밋된 뒤에만 스트림으로 내보낸다 */
public record NotificationCreated(Long projectId, Long recipientId, Long notificationId) {
}
