package com.acjb.server.service;

import org.springframework.stereotype.Component;

import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Notification;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;

/**
 * 담당 팀 알림(AC3 issue_created·AC11 recheck_failed, 2026-11-08) — 그 프로젝트에서 역할이 그 팀인 멤버 각각에게 정확히 1건씩.
 * 팀이 없거나 그 역할 멤버가 없으면 아무것도 만들지 않는다. 호출하는 쪽의 트랜잭션 안에서 저장한다
 */
@Component
public class TeamNotifier {

    private static final String IN_APP = "in_app";

    private final ProjectMemberRepository projectMembers;
    private final NotificationRepository notifications;

    public TeamNotifier(ProjectMemberRepository projectMembers, NotificationRepository notifications) {
        this.projectMembers = projectMembers;
        this.notifications = notifications;
    }

    public void notifyTeam(QAIssue issue, MemberRole team, NotificationEvent event) {
        if (team == null) {
            return;
        }
        for (ProjectMember pm : projectMembers.findTeam(issue.getProject().getId(), team)) {
            notifications.save(new Notification(issue, event, pm.getMember(), IN_APP));
        }
    }
}
