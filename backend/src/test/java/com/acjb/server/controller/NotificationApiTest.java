package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Notification;
import com.acjb.server.domain.NotificationCause;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.domain.PlatformScope;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.RunKind;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;
import com.acjb.server.repository.QAIssueRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * 알림함(시안 NTF, 2026-11-11) — 알림을 일으킨 계기·사람, 이슈·실행 요약, 읽음·모두 읽음.
 * 생성 규칙(AC3·AC4·AC11)은 기존 테스트가 보고, 여기서는 새로 더한 칸과 엔드포인트를 본다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationApiTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired ProjectRunRepository runs;
    @Autowired NotificationRepository notifications;

    Project project;
    Project other;
    Member owner;
    Member reporter;
    Member front;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("n-owner@test.dev", "소유자"));
        reporter = members.save(new Member("n-rep@test.dev", "김하늘"));
        front = members.save(new Member("n-front@test.dev", "박프론트"));
        project = projects.save(new Project("n-project", owner));
        other = projects.save(new Project("n-other", owner));
        projectMembers.save(new ProjectMember(project, owner, null));
        projectMembers.save(new ProjectMember(project, reporter, MemberRole.PLANNER));
        projectMembers.save(new ProjectMember(project, front, MemberRole.FRONTEND));
        projectMembers.save(new ProjectMember(other, owner, null));
        projectMembers.save(new ProjectMember(other, front, MemberRole.FRONTEND));
    }

    private ResultActions as(Member m, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.with(oauth2Login().attributes(a -> a.put("email", m.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF));
    }

    private ResultActions inbox(Member m, Project p) throws Exception {
        return as(m, get("/api/v1/projects/" + p.getId() + "/notifications"));
    }

    private QAIssue issueIn(Project p, MemberRole team) {
        return issues.save(new QAIssue(p, "로그인 버튼이 안 눌려요", "홈 / 로그인 버튼", List.of("홈 진입", "로그인 클릭"),
            PlatformScope.COMMON, null, List.of(), null, null, team, reporter));
    }

    @Test
    void 팀을_지정해_등록하면_registered_나중에_지정하면_team_assigned와_일으킨_사람이_담긴다() throws Exception {
        as(reporter, post("/api/v1/projects/" + project.getId() + "/qa-issues").contentType(MediaType.APPLICATION_JSON).content("""
            {"description":"아이콘이 커요","screen_location":"홈 화면","repro_steps":["홈 진입"],"platform_scope":"common","assigned_team":"frontend"}"""))
            .andExpect(status().isCreated());
        QAIssue later = issueIn(project, null);
        as(owner, patch("/api/v1/projects/" + project.getId() + "/qa-issues/" + later.getId())
            .contentType(MediaType.APPLICATION_JSON).content("{\"assigned_team\":\"frontend\"}"))
            .andExpect(status().isOk());

        inbox(front, project)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.unread_count").value(2))
            // 최신순 — 나중에 팀을 지정한 쪽이 먼저
            .andExpect(jsonPath("$.data.items[0].event").value("issue_created"))
            .andExpect(jsonPath("$.data.items[0].cause").value("team_assigned"))
            .andExpect(jsonPath("$.data.items[0].actor_name").value("소유자"))
            .andExpect(jsonPath("$.data.items[0].read_at").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[0].project_id").value(String.valueOf(project.getId())))
            .andExpect(jsonPath("$.data.items[0].issue.id").value(String.valueOf(later.getId())))
            .andExpect(jsonPath("$.data.items[0].issue.status").value("needs_check"))
            .andExpect(jsonPath("$.data.items[0].issue.source").value("manual"))
            .andExpect(jsonPath("$.data.items[0].issue.assigned_team").value("frontend"))
            .andExpect(jsonPath("$.data.items[0].issue.description").value("로그인 버튼이 안 눌려요"))
            .andExpect(jsonPath("$.data.items[0].run").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[1].cause").value("registered"))
            .andExpect(jsonPath("$.data.items[1].actor_name").value("김하늘"));
    }

    @Test
    void 수정_완료와_재검증_pass는_일으킨_사람과_함께_각각_등록자와_담당_팀에게_간다() throws Exception {
        QAIssue issue = issueIn(project, MemberRole.FRONTEND);
        as(front, patch("/api/v1/projects/" + project.getId() + "/qa-issues/" + issue.getId())
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"fixed\"}")).andExpect(status().isOk());
        as(reporter, post("/api/v1/projects/" + project.getId() + "/qa-issues/" + issue.getId() + "/verifications")
            .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"pass\"}")).andExpect(status().isCreated());

        inbox(reporter, project)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].event").value("fixed"))
            .andExpect(jsonPath("$.data.items[0].cause").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[0].actor_name").value("박프론트"));
        inbox(front, project)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].event").value("resolved"))
            .andExpect(jsonPath("$.data.items[0].actor_name").value("김하늘"))
            .andExpect(jsonPath("$.data.items[0].issue.status").value("resolved"));
        assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.RESOLVED)).isEqualTo(1);
    }

    @Test
    void 실행_실패_알림은_이슈_없이_실행_종류와_오류_문구를_담는다() throws Exception {
        ProjectRun run = runs.save(new ProjectRun(project, RunKind.FIRST_QA, owner, Instant.now()));
        run.fail("GitHub 저장소 응답 시간 초과", Instant.now());
        notifications.save(Notification.runFailed(run, "in_app"));

        inbox(owner, project)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].event").value("run_failed"))
            .andExpect(jsonPath("$.data.items[0].qa_issue_id").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[0].issue").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[0].run.id").value(String.valueOf(run.getId())))
            .andExpect(jsonPath("$.data.items[0].run.kind").value("first_qa"))
            .andExpect(jsonPath("$.data.items[0].run.error_message").value("GitHub 저장소 응답 시간 초과"));
        as(owner, get("/api/v1/projects/" + project.getId() + "/notifications?event=run_failed"))
            .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void 읽음은_본인_알림만_처음_읽은_시각을_남기고_남의_알림이나_다른_프로젝트_알림은_404() throws Exception {
        QAIssue issue = issueIn(project, MemberRole.FRONTEND);
        Notification mine = notifications.save(new Notification(issue, NotificationEvent.ISSUE_CREATED, NotificationCause.REGISTERED, front, reporter, "in_app"));
        Notification theirs = notifications.save(new Notification(issue, NotificationEvent.FIXED, reporter, "in_app"));
        Notification elsewhere = notifications.save(new Notification(issueIn(other, MemberRole.FRONTEND), NotificationEvent.ISSUE_CREATED, front, "in_app"));

        String first = as(front, post("/api/v1/projects/" + project.getId() + "/notifications/" + mine.getId() + "/read"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(String.valueOf(mine.getId())))
            .andExpect(jsonPath("$.data.read_at").exists())
            .andReturn().getResponse().getContentAsString();
        // 다시 읽어도 처음 시각 그대로
        String again = as(front, post("/api/v1/projects/" + project.getId() + "/notifications/" + mine.getId() + "/read"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(readAt(again)).isEqualTo(readAt(first));

        as(front, post("/api/v1/projects/" + project.getId() + "/notifications/" + theirs.getId() + "/read")).andExpect(status().isNotFound());
        as(front, post("/api/v1/projects/" + project.getId() + "/notifications/" + elsewhere.getId() + "/read")).andExpect(status().isNotFound());
        as(front, post("/api/v1/projects/" + project.getId() + "/notifications/abc/read")).andExpect(status().isNotFound());
        em.flush();
        em.clear();
        assertThat(notifications.findById(theirs.getId()).orElseThrow().getReadAt()).isNull();
        assertThat(notifications.findById(elsewhere.getId()).orElseThrow().getReadAt()).isNull();
        inbox(front, project).andExpect(jsonPath("$.data.unread_count").value(0));
    }

    @Test
    void 모두_읽음은_이_프로젝트의_내_알림만_바꾸고_바꾼_수를_돌려준다() throws Exception {
        QAIssue issue = issueIn(project, MemberRole.FRONTEND);
        notifications.save(new Notification(issue, NotificationEvent.ISSUE_CREATED, front, "in_app"));
        notifications.save(new Notification(issue, NotificationEvent.RECHECK_FAILED, front, "in_app"));
        Notification theirs = notifications.save(new Notification(issue, NotificationEvent.FIXED, reporter, "in_app"));
        Notification elsewhere = notifications.save(new Notification(issueIn(other, MemberRole.FRONTEND), NotificationEvent.ISSUE_CREATED, front, "in_app"));

        as(front, post("/api/v1/projects/" + project.getId() + "/notifications/read-all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.updated").value(2));
        as(front, post("/api/v1/projects/" + project.getId() + "/notifications/read-all"))
            .andExpect(jsonPath("$.data.updated").value(0));
        inbox(front, project)
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.unread_count").value(0));
        assertThat(notifications.findById(theirs.getId()).orElseThrow().getReadAt()).isNull();
        assertThat(notifications.findById(elsewhere.getId()).orElseThrow().getReadAt()).isNull();
    }

    @Test
    void 비멤버는_읽음_처리도_403이다() throws Exception {
        Member outsider = members.save(new Member("n-out@test.dev", "밖"));
        as(outsider, post("/api/v1/projects/" + project.getId() + "/notifications/read-all")).andExpect(status().isForbidden());
    }

    private static String readAt(String body) {
        int i = body.indexOf("\"read_at\":\"");
        return body.substring(i, body.indexOf('"', i + 11));
    }
}
