package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
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
import tools.jackson.databind.json.JsonMapper;

/** AC16(프로젝트 만들기), AC19(멤버 제거), 첫 로그인 시 Member 생성, 목록의 owner_id·프로젝트별 role */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectManagementApiTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired StatusHistoryRepository histories;
    @Autowired NotificationRepository notifications;

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String email) {
        return r.with(oauth2Login().attributes(a -> { a.put("email", email); a.put("name", "이름-" + email.substring(0, 3)); }))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t");
    }

    private String createProject(String email, String body) throws Exception {
        String res = mvc.perform(as(post("/api/v1/projects"), email).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).path("data").path("id").asString();
    }

    // --- AC16 ---

    @Test
    void ac16_처음_로그인한_사람도_프로젝트를_만들면_소유자이자_고른_역할의_멤버가_된다() throws Exception {
        assertThat(members.findByEmail("new-user@test.dev")).isEmpty();

        String projectId = createProject("New-User@test.dev", "{\"name\":\"  우리 서비스 QA  \",\"role\":\"planner\"}");

        Member me = members.findByEmail("new-user@test.dev").orElseThrow();   // 첫 요청 때 소문자 이메일로 생성
        assertThat(me.getName()).isEqualTo("이름-New");
        Project project = projects.findById(Long.valueOf(projectId)).orElseThrow();
        assertThat(project.getName()).isEqualTo("우리 서비스 QA");
        assertThat(project.getOwner().getId()).isEqualTo(me.getId());
        assertThat(projectMembers.findByProjectIdAndMemberId(project.getId(), me.getId()))
            .hasValueSatisfying(pm -> assertThat(pm.getRole()).isEqualTo(MemberRole.PLANNER));

        mvc.perform(as(get("/api/v1/projects"), "new-user@test.dev"))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(projectId))
            .andExpect(jsonPath("$.data.items[0].owner_id").value(String.valueOf(me.getId())));
        mvc.perform(as(get("/api/v1/projects/" + projectId + "/members"), "new-user@test.dev"))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].role").value("planner"));
    }

    @Test
    void ac16_이름이_비었거나_역할이_정해진_값이_아니면_400이고_아무것도_만들지_않는다() throws Exception {
        long before = projects.count();
        for (String body : List.of("{\"name\":\"  \",\"role\":\"backend\"}", "{\"name\":\"QA\"}",
                "{\"name\":\"QA\",\"role\":\"owner\"}", "{\"name\":\"" + "가".repeat(101) + "\",\"role\":\"backend\"}")) {
            mvc.perform(as(post("/api/v1/projects"), "ac16-bad@test.dev").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        }
        assertThat(projects.count()).isEqualTo(before);
        assertThat(projectMembers.count()).isZero();
    }

    @Test
    void 역할은_frontend_backend로_나뉘고_예전_값_developer는_400이다() throws Exception {
        String fe = createProject("split@test.dev", "{\"name\":\"FE\",\"role\":\"frontend\"}");
        mvc.perform(as(get("/api/v1/projects/" + fe + "/members"), "split@test.dev")).andExpect(jsonPath("$.data.items[0].role").value("frontend"));

        long before = projects.count();
        mvc.perform(as(post("/api/v1/projects"), "split@test.dev").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"OLD\",\"role\":\"developer\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
        assertThat(projects.count()).isEqualTo(before);
    }

    @Test
    void 같은_사람도_프로젝트마다_역할이_다를_수_있다() throws Exception {
        String a = createProject("multi@test.dev", "{\"name\":\"A\",\"role\":\"backend\"}");
        String b = createProject("multi@test.dev", "{\"name\":\"B\",\"role\":\"designer\"}");
        mvc.perform(as(get("/api/v1/projects/" + a + "/members"), "multi@test.dev")).andExpect(jsonPath("$.data.items[0].role").value("backend"));
        mvc.perform(as(get("/api/v1/projects/" + b + "/members"), "multi@test.dev")).andExpect(jsonPath("$.data.items[0].role").value("designer"));
        assertThat(members.findAll().stream().filter(m -> m.getEmail().equals("multi@test.dev")).count()).isEqualTo(1);
    }

    @Test
    void 미인증이면_프로젝트를_만들_수_없다() throws Exception {
        mvc.perform(post("/api/v1/projects").cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"QA\",\"role\":\"backend\"}"))
            .andExpect(status().isUnauthorized());
    }

    // --- AC19 ---

    private record Team(Project project, Member owner, Member dev, Member designer) {
    }

    private Team team() {
        Member owner = members.save(new Member("rm-owner@test.dev", "소유자"));
        Member dev = members.save(new Member("rm-dev@test.dev", "개발자"));
        Member designer = members.save(new Member("rm-des@test.dev", "디자이너"));
        Project project = projects.save(new Project("rm-project", owner));
        projectMembers.save(new ProjectMember(project, owner, MemberRole.PLANNER));
        projectMembers.save(new ProjectMember(project, dev, MemberRole.BACKEND));
        projectMembers.save(new ProjectMember(project, designer, MemberRole.DESIGNER));
        return new Team(project, owner, dev, designer);
    }

    private QAIssue issue(Team t, Member assignee, IssueStatus status) {
        QAIssue issue = issues.save(new QAIssue(t.project(), "d", "홈", List.of("홈 진입"), PlatformScope.COMMON, null, List.of(),
            null, null, assignee, t.designer()));
        histories.save(new StatusHistory(issue, IssueStatus.NEEDS_CHECK, t.designer()));
        issue.changeStatus(status);
        return issues.saveAndFlush(issue);
    }

    @Test
    void ac19_소유자가_멤버를_제거하면_미해결_담당_이슈만_담당자를_비우고_상태_이력은_그대로다() throws Exception {
        Team t = team();
        QAIssue open = issue(t, t.dev(), IssueStatus.NEEDS_CHECK);
        QAIssue fixedIssue = issue(t, t.dev(), IssueStatus.FIXED);
        QAIssue resolved = issue(t, t.dev(), IssueStatus.RESOLVED);
        long historyBefore = histories.count();
        long notificationsBefore = notifications.count();

        mvc.perform(as(delete("/api/v1/projects/" + t.project().getId() + "/members/" + t.dev().getId()), t.owner().getEmail()))
            .andExpect(status().isNoContent());

        em.flush();
        em.clear();
        assertThat(projectMembers.existsByProjectIdAndMemberId(t.project().getId(), t.dev().getId())).isFalse();
        assertThat(issues.findById(open.getId()).orElseThrow().getAssignedTo()).isNull();
        assertThat(issues.findById(fixedIssue.getId()).orElseThrow().getAssignedTo()).isNull();
        assertThat(issues.findById(fixedIssue.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.FIXED);
        assertThat(issues.findById(resolved.getId()).orElseThrow().getAssignedTo().getId()).isEqualTo(t.dev().getId());
        assertThat(histories.count()).isEqualTo(historyBefore);
        assertThat(notifications.count()).isEqualTo(notificationsBefore);
        // 제거된 사람은 이후 AC12대로 403
        mvc.perform(as(get("/api/v1/projects/" + t.project().getId() + "/qa-issues"), t.dev().getEmail()))
            .andExpect(status().isForbidden());
    }

    @Test
    void ac19_소유자가_아니면_403이고_아무도_빠지지_않는다() throws Exception {
        Team t = team();
        mvc.perform(as(delete("/api/v1/projects/" + t.project().getId() + "/members/" + t.designer().getId()), t.dev().getEmail()))
            .andExpect(status().isForbidden());
        assertThat(projectMembers.existsByProjectIdAndMemberId(t.project().getId(), t.designer().getId())).isTrue();
    }

    @Test
    void ac19_소유자_자신은_제거할_수_없다_409_그리고_멤버가_아닌_id는_404() throws Exception {
        Team t = team();
        mvc.perform(as(delete("/api/v1/projects/" + t.project().getId() + "/members/" + t.owner().getId()), t.owner().getEmail()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("OWNER_NOT_REMOVABLE"));
        Member stranger = members.save(new Member("rm-stranger@test.dev", "외부"));
        mvc.perform(as(delete("/api/v1/projects/" + t.project().getId() + "/members/" + stranger.getId()), t.owner().getEmail()))
            .andExpect(status().isNotFound());
        assertThat(projectMembers.existsByProjectIdAndMemberId(t.project().getId(), t.owner().getId())).isTrue();
    }
}
