package com.acjb.server.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Notification;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.domain.PlatformScope;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;

/**
 * AC15(GET /projects, GET /projects/{projectId}/members)와 AC14(GET /projects/{projectId}/notifications).
 * AC14·AC15 골든 케이스는 아직 없어(사람 승인 필요) SPEC 6절 문장을 그대로 단언으로 옮겼다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LookupApiTest {

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired NotificationRepository notifications;

    Project alpha;   // me·kim 소속
    Project beta;    // me·outsider 소속
    Project gamma;   // outsider만 소속
    Member me;
    Member kim;
    Member outsider;

    @BeforeEach
    void setUp() {
        // 이름 순서와 저장 순서를 일부러 다르게 둔다 — 정렬이 name asc인지 확인하려고
        beta = projects.save(new Project("l-beta", owner()));
        gamma = projects.save(new Project("l-gamma", owner()));
        alpha = projects.save(new Project("l-alpha", owner()));
        me = members.save(new Member("l-me@test.dev", "나나"));
        kim = members.save(new Member("l-kim@test.dev", "가가"));
        outsider = members.save(new Member("l-out@test.dev", "다다"));
        projectMembers.save(new ProjectMember(alpha, me, MemberRole.BACKEND));
        projectMembers.save(new ProjectMember(alpha, kim, MemberRole.DESIGNER));
        projectMembers.save(new ProjectMember(beta, me, MemberRole.BACKEND));
        projectMembers.save(new ProjectMember(beta, outsider, MemberRole.PLANNER));
        projectMembers.save(new ProjectMember(gamma, outsider, MemberRole.PLANNER));
    }

    private ResultActions getAs(String email, String path) throws Exception {
        return mvc.perform(get("/api/v1" + path).with(oauth2Login().attributes(a -> a.put("email", email))));
    }

    private QAIssue issueIn(Project p) {
        return issues.save(new QAIssue(p, "d", "홈", List.of("홈 진입"), PlatformScope.COMMON, null, List.of(), null, null, null, kim));
    }

    // --- AC15: 프로젝트 목록 ---

    @Test
    void 내가_Member인_프로젝트만_name_asc로_반환한다() throws Exception {
        getAs(me.getEmail(), "/projects")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].name").value("l-alpha"))
            .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(alpha.getId())))
            .andExpect(jsonPath("$.data.items[1].name").value("l-beta"));
    }

    @Test
    void Member로_등록되지_않은_구글_계정은_빈_목록이다() throws Exception {
        getAs("stranger@test.dev", "/projects")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 미인증이면_프로젝트_목록도_401() throws Exception {
        mvc.perform(get("/api/v1/projects"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false));
    }

    // --- AC15: 멤버 목록 ---

    @Test
    void 멤버_목록은_그_프로젝트_멤버만_name_asc로_반환한다() throws Exception {
        getAs(me.getEmail(), "/projects/" + alpha.getId() + "/members")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].name").value("가가"))
            .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(kim.getId())))
            .andExpect(jsonPath("$.data.items[0].email").value("l-kim@test.dev"))
            .andExpect(jsonPath("$.data.items[0].role").value("designer"))
            .andExpect(jsonPath("$.data.items[1].name").value("나나"));
    }

    @Test
    void 비멤버는_그_프로젝트_멤버_목록을_볼_수_없다_403() throws Exception {
        getAs(me.getEmail(), "/projects/" + gamma.getId() + "/members")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    // --- AC14: 내 알림 ---

    @Test
    void 내게_온_알림만_created_at_desc_id_desc로_반환한다() throws Exception {
        QAIssue issue = issueIn(alpha);
        Notification first = notifications.save(new Notification(issue, NotificationEvent.ISSUE_CREATED, me, "in_app"));
        notifications.save(new Notification(issue, NotificationEvent.FIXED, kim, "in_app"));   // 남의 알림
        Notification second = notifications.save(new Notification(issue, NotificationEvent.RECHECK_FAILED, me, "in_app"));

        getAs(me.getEmail(), "/projects/" + alpha.getId() + "/notifications")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(second.getId())))
            .andExpect(jsonPath("$.data.items[0].event").value("recheck_failed"))
            .andExpect(jsonPath("$.data.items[0].qa_issue_id").value(String.valueOf(issue.getId())))
            .andExpect(jsonPath("$.data.items[0].to_member_id").value(String.valueOf(me.getId())))
            .andExpect(jsonPath("$.data.items[0].channel").value("in_app"))
            .andExpect(jsonPath("$.data.items[0].created_at").exists())
            .andExpect(jsonPath("$.data.items[1].id").value(String.valueOf(first.getId())));
    }

    @Test
    void 다른_프로젝트에서_온_내_알림은_이_프로젝트_경로에_담지_않는다() throws Exception {
        notifications.save(new Notification(issueIn(beta), NotificationEvent.ISSUE_CREATED, me, "in_app"));
        getAs(me.getEmail(), "/projects/" + alpha.getId() + "/notifications")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));
        getAs(me.getEmail(), "/projects/" + beta.getId() + "/notifications")
            .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void event로_거르고_정해지지_않은_event는_400() throws Exception {
        QAIssue issue = issueIn(alpha);
        notifications.save(new Notification(issue, NotificationEvent.ISSUE_CREATED, me, "in_app"));
        notifications.save(new Notification(issue, NotificationEvent.FIXED, me, "in_app"));
        getAs(me.getEmail(), "/projects/" + alpha.getId() + "/notifications?event=fixed")
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].event").value("fixed"));
        getAs(me.getEmail(), "/projects/" + alpha.getId() + "/notifications?event=read")
            .andExpect(status().isBadRequest());
    }

    @Test
    void 비멤버는_알림_경로도_403이고_미인증은_401() throws Exception {
        getAs(me.getEmail(), "/projects/" + gamma.getId() + "/notifications").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/" + alpha.getId() + "/notifications")).andExpect(status().isUnauthorized());
    }

    /** 프로젝트는 소유자가 있어야 만들 수 있다(AC16·N15) — 테스트마다 소유자용 멤버를 따로 만든다 */
    private Member owner() {
        return members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
    }
}
