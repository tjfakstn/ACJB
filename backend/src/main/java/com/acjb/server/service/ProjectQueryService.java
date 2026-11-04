package com.acjb.server.service;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.dto.ItemsResponse;
import com.acjb.server.dto.MemberResponse;
import com.acjb.server.dto.NotificationListResponse;
import com.acjb.server.dto.NotificationResponse;
import com.acjb.server.dto.ProjectResponse;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;

/** 조회 전용 API — 프로젝트 목록·멤버 목록(AC15), 내 알림(AC14). 아무것도 쓰지 않는다 */
@Service
@Transactional(readOnly = true)
public class ProjectQueryService {

    private final ProjectMemberRepository projectMembers;
    private final NotificationRepository notifications;

    public ProjectQueryService(ProjectMemberRepository projectMembers, NotificationRepository notifications) {
        this.projectMembers = projectMembers;
        this.notifications = notifications;
    }

    /**
     * AC15 — 로그인한 구글 이메일이 Member인 프로젝트만. Member로 등록되지 않은 이메일이면 빈 목록이다
     * (403이 아니다 — "속한 프로젝트가 없다"는 정상 응답이고, 다른 프로젝트의 존재도 드러내지 않는다)
     */
    public ItemsResponse<ProjectResponse> myProjects(String email) {
        if (email == null || email.isBlank()) {
            return new ItemsResponse<>(List.of());
        }
        return new ItemsResponse<>(projectMembers.findProjectsOfMemberEmail(email).stream().map(ProjectResponse::of).toList());
    }

    /** AC15 — 프로젝트 멤버 여부는 Security 단계(AC12)에서 이미 확인했다 */
    public ItemsResponse<MemberResponse> members(Long projectId) {
        return new ItemsResponse<>(projectMembers.findMembersOfProject(projectId).stream().map(MemberResponse::of).toList());
    }

    /** AC14 — 요청자에게 온 이 프로젝트의 알림만. event는 정해진 3개 값만 받는다 */
    public NotificationListResponse myNotifications(Long projectId, Member me, String event) {
        NotificationEvent filter = event == null ? null : parseEvent(event);
        List<NotificationResponse> items = notifications.findInbox(projectId, me.getId(), filter).stream()
            .map(NotificationResponse::of).toList();
        return new NotificationListResponse(items, items.size());
    }

    private static NotificationEvent parseEvent(String raw) {
        for (NotificationEvent e : NotificationEvent.values()) {
            if (e.name().toLowerCase(Locale.ROOT).equals(raw)) {
                return e;
            }
        }
        throw ApiException.badRequest("event 값이 올바르지 않습니다: " + raw + " (issue_created, fixed, recheck_failed)");
    }
}
