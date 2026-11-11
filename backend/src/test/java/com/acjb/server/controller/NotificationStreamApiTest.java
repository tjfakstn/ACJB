package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
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
import com.acjb.server.service.ProjectDeletionService;
import com.acjb.server.service.TeamNotifier;

import jakarta.servlet.http.Cookie;

/**
 * 실시간 알림 스트림(SPEC AC37, 2026-11-11). 알림은 커밋된 뒤에만 보내므로 이 클래스는 트랜잭션으로 감싸지 않고, 끝나면 프로젝트를 지운다
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationStreamApiTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired NotificationRepository notifications;
    @Autowired ProjectDeletionService deletion;
    @Autowired TeamNotifier notifier;
    @Autowired TransactionTemplate tx;

    Project project;
    Project other;
    Member owner;
    Member reporter;
    Member front;
    Member outsider;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        owner = members.save(new Member("st-owner-" + n + "@test.dev", "소유자"));
        reporter = members.save(new Member("st-rep-" + n + "@test.dev", "김하늘"));
        front = members.save(new Member("st-front-" + n + "@test.dev", "박프론트"));
        outsider = members.save(new Member("st-out-" + n + "@test.dev", "외부인"));
        project = projects.save(new Project("stream-" + n, owner));
        other = projects.save(new Project("stream-other-" + n, owner));
        projectMembers.save(new ProjectMember(project, owner, null));
        projectMembers.save(new ProjectMember(project, reporter, MemberRole.PLANNER));
        projectMembers.save(new ProjectMember(project, front, MemberRole.FRONTEND));
        projectMembers.save(new ProjectMember(other, owner, null));
        projectMembers.save(new ProjectMember(other, front, MemberRole.FRONTEND));
    }

    @AfterEach
    void cleanUp() {
        deletion.delete(project.getId(), owner);
        deletion.delete(other.getId(), owner);
        members.deleteAll(List.of(owner, reporter, front, outsider));
    }

    private MockHttpServletRequestBuilder as(Member m, MockHttpServletRequestBuilder req) {
        return req.with(oauth2Login().attributes(a -> a.put("email", m.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private MvcResult open(Member m, Project p) throws Exception {
        return mvc.perform(as(m, get("/api/v1/projects/" + p.getId() + "/notifications/stream")))
            .andExpect(request().asyncStarted())
            .andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private QAIssue issueWithoutTeam() {
        return issues.save(new QAIssue(project, "로그인 버튼이 안 눌려요", "홈 / 로그인 버튼", List.of("홈 진입"),
            PlatformScope.COMMON, null, List.of(), null, null, null, reporter));
    }

    @Test
    void 미인증은_401_비멤버는_403() throws Exception {
        mvc.perform(get("/api/v1/projects/" + project.getId() + "/notifications/stream")).andExpect(status().isUnauthorized());
        mvc.perform(as(outsider, get("/api/v1/projects/" + project.getId() + "/notifications/stream"))).andExpect(status().isForbidden());
    }

    @Test
    void 멤버가_열면_이벤트_스트림이_열리고_다시_연결_간격을_먼저_보낸다() throws Exception {
        MvcResult r = open(front, project);
        MockHttpServletResponse res = r.getResponse();
        assertThat(res.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(res.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(body(r)).contains("retry:5000");
        assertThat(body(r)).doesNotContain("event:notification");
    }

    @Test
    void 팀을_지정하면_그_팀_멤버의_이_프로젝트_스트림에만_신호가_1번_간다() throws Exception {
        MvcResult frontHere = open(front, project);
        MvcResult frontOther = open(front, other);
        MvcResult reporterHere = open(reporter, project);
        QAIssue issue = issueWithoutTeam();

        mvc.perform(as(owner, patch("/api/v1/projects/" + project.getId() + "/qa-issues/" + issue.getId()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"assigned_team\":\"frontend\"}"))
            .andExpect(status().isOk());

        Long id = notifications.findAll().stream()
            .filter(n -> n.getIssue() != null && n.getIssue().getId().equals(issue.getId()))
            .findFirst().orElseThrow().getId();
        assertThat(body(frontHere)).containsOnlyOnce("event:notification")
            .contains("data:{\"notification_id\":\"" + id + "\"}");
        // 다른 프로젝트 연결·다른 사람 연결에는 가지 않는다
        assertThat(body(frontOther)).doesNotContain("event:notification");
        assertThat(body(reporterHere)).doesNotContain("event:notification");
    }

    @Test
    void 저장이_롤백된_알림은_보내지_않는다() throws Exception {
        MvcResult frontHere = open(front, project);
        QAIssue issue = issueWithoutTeam();

        tx.executeWithoutResult(status -> {
            notifier.notifyTeam(issues.findById(issue.getId()).orElseThrow(), MemberRole.FRONTEND, NotificationEvent.ISSUE_CREATED, owner);
            status.setRollbackOnly();
        });

        assertThat(body(frontHere)).doesNotContain("event:notification");
        assertThat(notifications.findAll().stream().filter(n -> n.getIssue() != null && n.getIssue().getId().equals(issue.getId()))).isEmpty();
    }

    @Test
    void 한_사람이_한_프로젝트에_연결을_6개_열면_가장_오래된_것이_닫히고_나머지_5개만_신호를_받는다() throws Exception {
        MvcResult first = open(front, project);
        List<MvcResult> rest = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rest.add(open(front, project));
        }
        QAIssue issue = issueWithoutTeam();
        tx.executeWithoutResult(status ->
            notifier.notifyTeam(issues.findById(issue.getId()).orElseThrow(), MemberRole.FRONTEND, NotificationEvent.ISSUE_CREATED, owner));

        assertThat(body(first)).doesNotContain("event:notification");
        for (MvcResult r : rest) {
            assertThat(body(r)).containsOnlyOnce("event:notification");
        }
    }
}
