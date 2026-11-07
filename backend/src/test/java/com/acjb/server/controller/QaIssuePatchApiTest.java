package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.acjb.server.domain.Environment;
import com.acjb.server.domain.EnvPlatform;
import com.acjb.server.domain.DeployStage;
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

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * PATCH /projects/{projectId}/qa-issues/{issueId}. 메서드 이름 앞의 id는 tests/harness/golden_cases.yaml 케이스와 대응한다.
 * 등장인물: reporter(member-1, 등록자), assignee(member-2, 담당자), other(member-3, 같은 프로젝트의 다른 멤버),
 * outsider(member-9, 다른 프로젝트 멤버).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QaIssuePatchApiTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired StatusHistoryRepository histories;
    @Autowired NotificationRepository notifications;

    Project project;
    Member reporter;
    Member assignee;
    Member other;
    Member outsider;

    @BeforeEach
    void setUp() {
        project = projects.save(new Project("patch-api", owner()));
        Project otherProject = projects.save(new Project("patch-api-other", owner()));
        reporter = members.save(new Member("p-reporter@test.dev", "등록자"));
        assignee = members.save(new Member("p-assignee@test.dev", "담당자"));
        other = members.save(new Member("p-other@test.dev", "다른 멤버"));
        outsider = members.save(new Member("p-outsider@test.dev", "외부"));
        projectMembers.save(new ProjectMember(project, reporter, MemberRole.DESIGNER));
        projectMembers.save(new ProjectMember(project, assignee, MemberRole.BACKEND));
        projectMembers.save(new ProjectMember(project, other, MemberRole.BACKEND));
        projectMembers.save(new ProjectMember(otherProject, outsider, MemberRole.PLANNER));
    }

    /** 등록 API를 거친 것과 같은 상태(needs_check + 이력 1건)를 만든다. status가 다르면 그 상태로 맞춘다 */
    private QAIssue issue(Member assignedTo, IssueStatus status) {
        QAIssue issue = issues.save(new QAIssue(project, "아이콘이 너무 커요", "홈 화면 상단 알림 아이콘",
            List.of("홈 진입", "상단 알림 아이콘 확인"), PlatformScope.AOS,
            new Environment(EnvPlatform.AOS, "Pixel 8", null, DeployStage.STAGING), List.of(), null, null, assignedTo, reporter));
        histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, reporter));
        if (status != IssueStatus.NEEDS_CHECK) {
            issue.changeStatus(status);
            histories.save(new StatusHistory(issue, status, assignedTo == null ? reporter : assignedTo));
        }
        issues.flush();
        return issue;
    }

    private ResultActions patchAs(Member actor, QAIssue issue, String body) throws Exception {
        return mvc.perform(patch("/api/v1/projects/" + project.getId() + "/qa-issues/" + issue.getId())
            .with(oauth2Login().attributes(a -> a.put("email", actor.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long historyCount(QAIssue issue) {
        return histories.findByIssueIdOrderByChangedAtAscIdAsc(issue.getId()).size();
    }

    private IssueStatus reloadStatus(QAIssue issue) {
        em.clear();
        return issues.findById(issue.getId()).orElseThrow().getStatus();
    }

    // --- AC4 · AC6: fixed ---

    @Test
    void nt002_담당자가_fixed로_바꾸면_등록자에게_fixed_알림_1건() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(assignee, issue, "{\"status\":\"fixed\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("fixed"));
        assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.FIXED)).isEqualTo(1);
        assertThat(notifications.findAll()).singleElement()
            .satisfies(n -> assertThat(n.getRecipient().getId()).isEqualTo(reporter.getId()));
    }

    @Test
    void st002_needs_check에서_fixed는_성공하고_status_history_마지막에_남는다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(assignee, issue, "{\"status\":\"fixed\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status_history.length()").value(2))
            .andExpect(jsonPath("$.data.status_history[1].status").value("fixed"))
            .andExpect(jsonPath("$.data.status_history[1].changed_by").value(String.valueOf(assignee.getId())))
            .andExpect(jsonPath("$.data.status_history[1].changed_at").exists());
    }

    @Test
    void needs_recheck에서_fixed도_PATCH로_가능하다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_RECHECK);
        patchAs(assignee, issue, "{\"status\":\"fixed\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("fixed"));
    }

    @Test
    void st003_PATCH로_resolved를_직접_요청하면_409() throws Exception {
        // 권한 있는 요청자(담당자)여야 전이 검사까지 간다 — 권한이 없으면 au-006대로 403
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(assignee, issue, "{\"status\":\"resolved\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false));
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.NEEDS_CHECK);
    }

    @Test
    void ng007_fixed에서_fixed는_409이고_이력과_알림이_늘지_않는다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.FIXED);
        long before = historyCount(issue);
        patchAs(assignee, issue, "{\"status\":\"fixed\"}").andExpect(status().isConflict());
        assertThat(historyCount(issue)).isEqualTo(before);
        assertThat(notifications.count()).isZero();
    }

    @Test
    void resolved된_이슈는_PATCH로_다시_fixed가_될_수_없다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.RESOLVED);
        patchAs(assignee, issue, "{\"status\":\"fixed\"}").andExpect(status().isConflict());
    }

    // --- AC13: 권한 ---

    @Test
    void au003_담당자가_아닌_멤버의_fixed는_403이고_상태_이력_알림이_그대로다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        long before = historyCount(issue);
        patchAs(other, issue, "{\"status\":\"fixed\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false));
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.NEEDS_CHECK);
        assertThat(historyCount(issue)).isEqualTo(before);
        assertThat(notifications.count()).isZero();
    }

    @Test
    void au003_등록자도_담당자가_아니면_fixed로_바꿀_수_없다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(reporter, issue, "{\"status\":\"fixed\"}").andExpect(status().isForbidden());
    }

    @Test
    void au004_담당자가_없는_이슈는_누구도_fixed로_바꿀_수_없다() throws Exception {
        QAIssue issue = issue(null, IssueStatus.NEEDS_CHECK);
        patchAs(reporter, issue, "{\"status\":\"fixed\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void au006_권한_검사가_전이_검사보다_먼저다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(other, issue, "{\"status\":\"resolved\"}").andExpect(status().isForbidden());
    }

    @Test
    void au007_프로젝트_멤버가_아닌_사람을_담당자로_지정하면_400이고_알림이_없다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(reporter, issue, "{\"assigned_to\":\"%d\"}".formatted(outsider.getId()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
        assertThat(notifications.count()).isZero();
        em.clear();
        assertThat(issues.findById(issue.getId()).orElseThrow().getAssignedTo().getId()).isEqualTo(assignee.getId());
    }

    // --- AC3 · N5: 담당자 지정 ---

    @Test
    void nt003_담당자가_없던_이슈에_지정하면_그_담당자에게_issue_created_1건() throws Exception {
        QAIssue issue = issue(null, IssueStatus.NEEDS_CHECK);
        patchAs(reporter, issue, "{\"assigned_to\":\"%d\"}".formatted(other.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assigned_to").value(String.valueOf(other.getId())));
        assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.ISSUE_CREATED)).isEqualTo(1);
        assertThat(notifications.findAll()).singleElement()
            .satisfies(n -> assertThat(n.getRecipient().getId()).isEqualTo(other.getId()));
    }

    @Test
    void 담당자를_다른_사람으로_바꾸면_새_담당자에게_1건() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(other, issue, "{\"assigned_to\":\"%d\"}".formatted(other.getId())).andExpect(status().isOk());
        assertThat(notifications.findAll()).singleElement()
            .satisfies(n -> assertThat(n.getRecipient().getId()).isEqualTo(other.getId()));
    }

    @Test
    void ng004_같은_담당자를_다시_지정하면_200이고_알림이_없다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(reporter, issue, "{\"assigned_to\":\"%d\"}".formatted(assignee.getId())).andExpect(status().isOk());
        assertThat(notifications.count()).isZero();
    }

    @Test
    void assigned_to를_null로_비우면_400() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(reporter, issue, "{\"assigned_to\":null}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("assigned_to")));
    }

    // --- 함께 보낸 경우 (변경 전 담당자 기준 — 2026-11-04 설만수 결정) ---

    @Test
    void 담당자가_fixed와_재지정을_함께_보내면_둘_다_반영되고_알림이_각각_1건() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(assignee, issue, "{\"status\":\"fixed\",\"assigned_to\":\"%d\"}".formatted(other.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("fixed"))
            .andExpect(jsonPath("$.data.assigned_to").value(String.valueOf(other.getId())));
        assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.FIXED)).isEqualTo(1);
        assertThat(notifications.countByIssueIdAndEvent(issue.getId(), NotificationEvent.ISSUE_CREATED)).isEqualTo(1);
    }

    @Test
    void 자기를_담당자로_지정하면서_fixed를_요청해도_변경_전_담당자가_아니면_403이고_담당자도_안_바뀐다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(other, issue, "{\"status\":\"fixed\",\"assigned_to\":\"%d\"}".formatted(other.getId()))
            .andExpect(status().isForbidden());
        assertThat(notifications.count()).isZero();
        em.clear();
        QAIssue reloaded = issues.findById(issue.getId()).orElseThrow();
        assertThat(reloaded.getAssignedTo().getId()).isEqualTo(assignee.getId());
        assertThat(reloaded.getStatus()).isEqualTo(IssueStatus.NEEDS_CHECK);
    }

    // --- N6: 재현 정보는 바꿀 수 없다 ---

    @Test
    void ng005_repro_steps를_바꾸려_하면_400이고_원본이_그대로다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(assignee, issue, "{\"repro_steps\":[\"다른 절차\"]}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("repro_steps")));
        em.clear();
        assertThat(issues.findById(issue.getId()).orElseThrow().getReproSteps()).containsExactly("홈 진입", "상단 알림 아이콘 확인");
    }

    @Test
    void 허용된_필드와_함께_와도_다른_필드가_있으면_아무것도_바꾸지_않고_400() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(assignee, issue, "{\"status\":\"fixed\",\"screen_location\":\"다른 화면\"}").andExpect(status().isBadRequest());
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.NEEDS_CHECK);
        assertThat(notifications.count()).isZero();
    }

    @Test
    void 빈_body와_정해지지_않은_status_값은_400() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(assignee, issue, "{}").andExpect(status().isBadRequest());
        patchAs(assignee, issue, "{\"status\":\"closed\"}").andExpect(status().isBadRequest());
    }

    @Test
    void 다른_프로젝트_이슈나_없는_이슈는_404() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        mvc.perform(patch("/api/v1/projects/" + project.getId() + "/qa-issues/999999999")
                .with(oauth2Login().attributes(a -> a.put("email", assignee.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"fixed\"}"))
            .andExpect(status().isNotFound());
        assertThat(reloadStatus(issue)).isEqualTo(IssueStatus.NEEDS_CHECK);
    }

    // --- AC5 ---

    @Test
    void vf001_fixed_이슈를_조회하면_등록_때의_재현_정보와_환경이_그대로다() throws Exception {
        QAIssue issue = issue(assignee, IssueStatus.NEEDS_CHECK);
        patchAs(assignee, issue, "{\"status\":\"fixed\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects/" + project.getId() + "/qa-issues/" + issue.getId())
                .with(oauth2Login().attributes(a -> a.put("email", reporter.getEmail()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("fixed"))
            .andExpect(jsonPath("$.data.repro_steps[0]").value("홈 진입"))
            .andExpect(jsonPath("$.data.repro_steps[1]").value("상단 알림 아이콘 확인"))
            .andExpect(jsonPath("$.data.screen_location").value("홈 화면 상단 알림 아이콘"))
            .andExpect(jsonPath("$.data.environment.device").value("Pixel 8"));
    }

    /** 프로젝트는 소유자가 있어야 만들 수 있다(AC16·N15) — 테스트마다 소유자용 멤버를 따로 만든다 */
    private Member owner() {
        return members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
    }
}
