package com.acjb.server.service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.domain.ManualQaPin;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Notification;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.dto.NotificationListResponse;
import com.acjb.server.dto.NotificationResponse;
import com.acjb.server.repository.ManualQaPinRepository;
import com.acjb.server.repository.NotificationRepository;

/**
 * 알림함(AC14) — 조회·읽음·모두 읽음(2026-11-11). 프로젝트 멤버 검사는 경로 보안(AC12)이 먼저 한다.
 * 받는 사람 본인의 알림만 읽음으로 바꿀 수 있고, 남의 알림은 404로 숨긴다(N10)
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final ManualQaPinRepository pins;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, ManualQaPinRepository pins, Clock clock) {
        this.notifications = notifications;
        this.pins = pins;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationListResponse inbox(Long projectId, Member me, String event) {
        NotificationEvent filter = event == null ? null : parseEvent(event);
        List<Notification> found = notifications.findInbox(projectId, me.getId(), filter);
        // 수동 QA 제목·핀 번호(QA-M###)는 핀에 있다 — 한 번에 읽는다
        List<Long> issueIds = found.stream().filter(n -> n.getIssue() != null).map(n -> n.getIssue().getId()).distinct().toList();
        Map<Long, ManualQaPin> pinByIssue = issueIds.isEmpty() ? Map.of()
            : pins.findByIssueIdIn(issueIds).stream().collect(Collectors.toMap(p -> p.getIssue().getId(), Function.identity(), (a, b) -> a));
        List<NotificationResponse> items = found.stream()
            .map(n -> NotificationResponse.of(n, n.getIssue() == null ? null : pinByIssue.get(n.getIssue().getId())))
            .toList();
        int unread = (int) found.stream().filter(n -> n.getReadAt() == null).count();
        return new NotificationListResponse(items, items.size(), unread);
    }

    /** 한 건 읽음 — 이미 읽었으면 그대로 돌려준다 */
    @Transactional
    public NotificationResponse markRead(Long projectId, String notificationId, Member me) {
        Long id = parseId(notificationId);
        Notification n = id == null ? null : notifications.findByIdAndProjectIdAndRecipientId(id, projectId, me.getId()).orElse(null);
        if (n == null) {
            throw ApiException.notFound("알림을 찾을 수 없습니다.");
        }
        n.markRead(clock.instant());
        ManualQaPin pin = n.getIssue() == null ? null : pins.findByIssueId(n.getIssue().getId()).orElse(null);
        return NotificationResponse.of(n, pin);
    }

    /** 이 프로젝트에서 내 알림을 모두 읽음으로 — 바꾼 수를 돌려준다 */
    @Transactional
    public Map<String, Integer> markAllRead(Long projectId, Member me) {
        return Map.of("updated", notifications.markAllRead(projectId, me.getId(), clock.instant()));
    }

    private static NotificationEvent parseEvent(String raw) {
        for (NotificationEvent e : NotificationEvent.values()) {
            if (e.name().toLowerCase(Locale.ROOT).equals(raw)) {
                return e;
            }
        }
        throw ApiException.badRequest("event 값이 올바르지 않습니다: " + raw + " (issue_created, fixed, recheck_failed, resolved, run_failed)");
    }

    private static Long parseId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
