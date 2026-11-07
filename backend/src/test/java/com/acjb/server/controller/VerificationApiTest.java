package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.domain.PlatformScope;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.StatusHistory;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;
import com.acjb.server.repository.VerificationRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * POST·GET /projects/{projectId}/qa-issues/{issueId}/verifications. 메서드 이름 앞의 id는 golden_cases.yaml 케이스와 대응한다.
 * reporter(member-1, 등록자)·assignee(member-2, 담당자)·other(같은 프로젝트의 다른 멤버).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VerificationApiTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired StatusHistoryRepository histories;
    @Autowired VerificationRepository verifications;
    @Autowired NotificationRepository notifications;

    Project project;
    Project otherProject;
    Member reporter;
    Member assignee;
    Member other;

    @BeforeEach
    void setUp() {
        project = projects.save(new Project("verify-api", owner()));
        otherProject = projects.save(new Project("verify-api-other", owner()));
        reporter = members.save(new Member("v-reporter@test.dev", "등록자"));
        assignee = members.save(new Member("v-assignee@test.dev", "담당자"));
        other = members.save(new Member("v-other@test.dev", "다른 멤버"));
        for (Member m : List.of(reporter, assignee, other)) {
            projectMembers.save(new ProjectMember(project, m, MemberRole.BACKEND));
        }
        projectMembers.save(new ProjectMember(otherProject, reporter, MemberRole.DESIGNER));
    }

    private QAIssue issue(Project p, Member assignedTo, IssueStatus status) {
        QAIssue issue = issues.save(new QAIssue(p, "버튼 색이 달라요", "로그인 화면 버튼",
            List.of("로그인 화면 진입", "버튼 색 확인"), PlatformScope.COMMON, null, List.of(), null, null, assignedTo, reporter));
        histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, reporter));
        if (status != IssueStatus.NEEDS_CHECK) {
            issue.changeStatus(status);
            histories.save(new StatusHistory(issue, status, assignedTo == null ? reporter : assignedTo));
        }
        issues.flush();
        return issue;
    }

    private ResultActions verifyAs(Member actor, QAIssue issue, String body) throws Exception {
        return mvc.perform(post("/api/v1/projects/" + project.getId() + "/qa-issues/" + issue.getId() + "/verifications")
            .with(oauth2Login().attributes(a -> a.put("email", actor.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private IssueStatus reloadStatus(QAIssue issue) {
        em.clear();
        return issues.findById(issue.getId()).orElseThrow().getStatus();
    }

    // --- AC7 ---

    @Test
    void vf002_pass면_resolved가_되고_verified_by_verified_at_repro_steps를_돌려준다() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.FIXED);
        verifyAs(reporter, issue, "{\"result\":\"pass\",\"scope\":\"해당 화면만\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.issue_status").value("resolved"))
            .andExpect(jsonPath("$.data.verification.result").value("pass"))
            .andExpect(jsonPath("$.data.verification.scope").value("해당 화면만"))
            .andExpect(jsonPath("$.data.verification.verified_by").value(String.valueOf(reporter.getId())))
            .andExpect(jsonPath("$.data.verification.verified_at").exists())
            .andExpect(jsonPath("$.data.repro_steps[0]").value("로그인 화면 진입"))
            .andExpect(jsonPath("$.data.repro_steps[1]").value("버튼 색 확인"));
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.RESOLVED);
        assertThat(histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId()))
            .last().satisfies(h -> {
                assertThat(h.getStatus()).isEqualTo(IssueStatus.RESOLVED);
                assertThat(h.getChangedBy().getId()).isEqualTo(reporter.getId());
            });
    }

    @Test
    void vf003_fail이면_needs_recheck가_된다() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.FIXED);
        verifyAs(reporter, issue, "{\"result\":\"fail\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.issue_status").value("needs_recheck"));
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.NEEDS_RECHECK);
    }

    // --- AC11 ---

    @Test
    void nt005_fail이면_담당자에게_recheck_failed_1건() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.FIXED);
        verifyAs(reporter, issue, "{\"result\":\"fail\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.issue_status").value("needs_recheck"));
        assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.RECHECK_FAILED)).isEqualTo(1);
        assertThat(notifications.findAll()).singleElement()
            .satisfies(n -> assertThat(n.getRecipient().getId()).isEqualTo(assignee.getId()));
    }

    @Test
    void nt006_pass면_recheck_failed가_생기지_않는다() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.FIXED);
        verifyAs(reporter, issue, "{\"result\":\"pass\"}").andExpect(status().isCreated());
        assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.RECHECK_FAILED)).isZero();
        assertThat(notifications.count()).isZero();
    }

    // --- AC6 ---

    @Test
    void st001_needs_check_이슈는_재검증할_수_없다() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.NEEDS_CHECK);
        verifyAs(reporter, issue, "{\"result\":\"pass\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false));
        assertThat(verifications.count()).isZero();
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.NEEDS_CHECK);
    }

    @Test
    void resolved나_needs_recheck_이슈도_재검증할_수_없다() throws Exception {
        verifyAs(reporter, issue(project, assignee, IssueStatus.RESOLVED), "{\"result\":\"pass\"}").andExpect(status().isConflict());
        verifyAs(reporter, issue(project, assignee, IssueStatus.NEEDS_RECHECK), "{\"result\":\"fail\"}").andExpect(status().isConflict());
        assertThat(verifications.count()).isZero();
        assertThat(notifications.count()).isZero();
    }

    // --- AC13 ---

    @Test
    void au005_등록자가_아니면_403이고_Verification이_생기지_않는다() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.FIXED);
        long historyBefore = histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId()).size();
        verifyAs(assignee, issue, "{\"result\":\"pass\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false));
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.FIXED);
        assertThat(verifications.count()).isZero();
        assertThat(histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId())).hasSize((int) historyBefore);
    }

    @Test
    void 권한_검사가_전이_검사보다_먼저다() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.NEEDS_CHECK);
        verifyAs(other, issue, "{\"result\":\"pass\"}").andExpect(status().isForbidden());
    }

    // --- AC13 (2026-11-05 수정): 등록자가 제거되면 소유자가 대신 ---

    /** 소유자를 프로젝트 멤버로 넣는다(AC12 필터를 통과해야 권한 검사까지 온다) */
    private Member ownerAsMember() {
        Member owner = project.getOwner();
        projectMembers.save(new ProjectMember(project, owner, MemberRole.PLANNER));
        return owner;
    }

    private void removeReporter() {
        projectMembers.delete(projectMembers.findByProjectIdAndMemberId(project.getId(), reporter.getId()).orElseThrow());
        projectMembers.flush();
    }

    @Test
    void au008_등록자가_제거됐으면_소유자가_재검증할_수_있다() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.FIXED);
        Member owner = ownerAsMember();
        removeReporter();
        verifyAs(owner, issue, "{\"result\":\"pass\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.issue_status").value("resolved"))
            .andExpect(jsonPath("$.data.verification.verified_by").value(String.valueOf(owner.getId())));
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.RESOLVED);
    }

    @Test
    void au009_등록자가_아직_멤버면_소유자라도_403이고_상태_이력이_그대로다() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.FIXED);
        Member owner = ownerAsMember();
        long historyBefore = histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId()).size();
        verifyAs(owner, issue, "{\"result\":\"pass\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false));
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.FIXED);
        assertThat(verifications.count()).isZero();
        assertThat(histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId())).hasSize((int) historyBefore);
    }

    @Test
    void 등록자가_제거돼도_소유자가_아닌_멤버는_403() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.FIXED);
        removeReporter();
        verifyAs(other, issue, "{\"result\":\"pass\"}").andExpect(status().isForbidden());
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.FIXED);
        assertThat(verifications.count()).isZero();
    }

    // --- N6 · N8 ---

    @Test
    void ng006_요청에_repro_steps가_있어도_원본을_덮어쓰지_않고_원본을_돌려준다() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.FIXED);
        verifyAs(reporter, issue, "{\"result\":\"pass\",\"repro_steps\":[\"바꾼 절차\"]}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.repro_steps.length()").value(2))
            .andExpect(jsonPath("$.data.repro_steps[0]").value("로그인 화면 진입"));
        em.clear();
        assertThat(issues.findById(issue.getId()).orElseThrow().getReproSteps()).containsExactly("로그인 화면 진입", "버튼 색 확인");
    }

    @Test
    void body의_verified_by는_무시하고_로그인_사용자로_기록한다() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.FIXED);
        verifyAs(reporter, issue, "{\"result\":\"pass\",\"verified_by\":\"%d\",\"verified_at\":\"2000-01-01T00:00:00Z\"}"
                .formatted(assignee.getId()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.verification.verified_by").value(String.valueOf(reporter.getId())))
            .andExpect(jsonPath("$.data.verification.verified_at", org.hamcrest.Matchers.not(containsString("2000-01-01"))));
    }

    // --- 요청 형태 ---

    @Test
    void 담당자가_없는_이슈의_fail은_알림을_만들지_않는다() throws Exception {
        QAIssue issue = issue(project, null, IssueStatus.FIXED);
        verifyAs(reporter, issue, "{\"result\":\"fail\"}").andExpect(status().isCreated());
        assertThat(notifications.count()).isZero();
    }

    @Test
    void result가_없거나_정해지지_않은_값이면_400() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.FIXED);
        verifyAs(reporter, issue, "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("result")));
        verifyAs(reporter, issue, "{\"result\":\"maybe\"}").andExpect(status().isBadRequest());
        assertThat(verifications.count()).isZero();
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.FIXED);
    }

    @Test
    void 다른_프로젝트_이슈는_404() throws Exception {
        QAIssue foreign = issue(otherProject, null, IssueStatus.FIXED);
        verifyAs(reporter, foreign, "{\"result\":\"pass\"}")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/v1/projects/" + project.getId() + "/qa-issues/" + foreign.getId() + "/verifications")
                .with(oauth2Login().attributes(a -> a.put("email", reporter.getEmail()))))
            .andExpect(status().isNotFound());
    }

    // --- 전체 흐름: 등록 → fixed → fail → fixed → pass ---

    @Test
    void 재검증_왕복_전체_흐름과_목록_최신순() throws Exception {
        QAIssue issue = issue(project, assignee, IssueStatus.NEEDS_CHECK);
        String issuePath = "/api/v1/projects/" + project.getId() + "/qa-issues/" + issue.getId();

        mvc.perform(patch(issuePath).with(oauth2Login().attributes(a -> a.put("email", assignee.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"fixed\"}"))
            .andExpect(status().isOk());
        verifyAs(reporter, issue, "{\"result\":\"fail\"}").andExpect(jsonPath("$.data.issue_status").value("needs_recheck"));
        mvc.perform(patch(issuePath).with(oauth2Login().attributes(a -> a.put("email", assignee.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"fixed\"}"))
            .andExpect(status().isOk());
        verifyAs(reporter, issue, "{\"result\":\"pass\"}").andExpect(jsonPath("$.data.issue_status").value("resolved"));

        mvc.perform(get(issuePath + "/verifications").with(oauth2Login().attributes(a -> a.put("email", other.getEmail()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].result").value("pass"))
            .andExpect(jsonPath("$.data.items[1].result").value("fail"));
        mvc.perform(get(issuePath).with(oauth2Login().attributes(a -> a.put("email", reporter.getEmail()))))
            .andExpect(jsonPath("$.data.status").value("resolved"))
            .andExpect(jsonPath("$.data.status_history.length()").value(5))
            .andExpect(jsonPath("$.data.status_history[1].status").value("fixed"))
            .andExpect(jsonPath("$.data.status_history[2].status").value("needs_recheck"))
            .andExpect(jsonPath("$.data.status_history[3].status").value("fixed"))
            .andExpect(jsonPath("$.data.status_history[4].status").value("resolved"));
        // fixed 2번 → 등록자에게 fixed 2건, fail 1번 → 담당자에게 recheck_failed 1건
        assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.FIXED)).isEqualTo(2);
        assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.RECHECK_FAILED)).isEqualTo(1);
    }

    /** 프로젝트는 소유자가 있어야 만들 수 있다(AC16·N15) — 테스트마다 소유자용 멤버를 따로 만든다 */
    private Member owner() {
        return members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
    }
}
