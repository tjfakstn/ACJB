package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.acjb.server.domain.Invitation;
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

    /** pj-003(2026-11-07 교체) — 만들 때 역할을 받지 않고 소유자는 역할 미지정으로 시작, 설명 저장 */
    @Test
    void ac16_처음_로그인한_사람도_프로젝트를_만들면_소유자이자_역할_미지정_멤버가_되고_설명이_저장된다() throws Exception {
        assertThat(members.findByEmail("new-user@test.dev")).isEmpty();

        String projectId = createProject("New-User@test.dev", "{\"name\":\"  우리 서비스 QA  \",\"description\":\"  멤버십 화면 QA  \"}");

        Member me = members.findByEmail("new-user@test.dev").orElseThrow();   // 첫 요청 때 소문자 이메일로 생성
        assertThat(me.getName()).isEqualTo("이름-New");
        Project project = projects.findById(Long.valueOf(projectId)).orElseThrow();
        assertThat(project.getName()).isEqualTo("우리 서비스 QA");
        assertThat(project.getDescription()).isEqualTo("멤버십 화면 QA");
        assertThat(project.getOwner().getId()).isEqualTo(me.getId());
        assertThat(projectMembers.findByProjectIdAndMemberId(project.getId(), me.getId()))
            .hasValueSatisfying(pm -> assertThat(pm.getRole()).isNull());

        mvc.perform(as(get("/api/v1/projects"), "new-user@test.dev"))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(projectId))
            .andExpect(jsonPath("$.data.items[0].description").value("멤버십 화면 QA"))
            .andExpect(jsonPath("$.data.items[0].owner_id").value(String.valueOf(me.getId())));
        mvc.perform(as(get("/api/v1/projects/" + projectId + "/members"), "new-user@test.dev"))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].role").value(org.hamcrest.Matchers.nullValue()));
    }

    /** pj-004(2026-11-07 교체) — 이름·설명 규칙 */
    @Test
    void ac16_이름이_비었거나_규칙을_어기거나_설명이_200자를_넘으면_400이고_아무것도_만들지_않는다() throws Exception {
        long before = projects.count();
        for (String body : List.of("{\"name\":\"  \"}", "{}", "{\"name\":\"가\"}", "{\"name\":\"프로젝트!\"}",
                "{\"name\":\"" + "가".repeat(41) + "\"}",
                "{\"name\":\"새 프로젝트\",\"description\":\"" + "가".repeat(201) + "\"}")) {
            mvc.perform(as(post("/api/v1/projects"), "ac16-bad@test.dev").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        }
        assertThat(projects.count()).isEqualTo(before);
        assertThat(projectMembers.count()).isZero();
    }

    /** pj-008 — 설명은 선택 */
    @Test
    void ac16_설명_없이_만들면_description은_null이다() throws Exception {
        mvc.perform(as(post("/api/v1/projects"), "nodesc@test.dev").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"설명 없는 프로젝트\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.description").value(org.hamcrest.Matchers.nullValue()));
    }

    /** 2026-11-07부터 만들 때 role은 받지 않는다 — 보내도 무시되고 역할 미지정으로 시작한다 */
    @Test
    void 만들_때_보낸_role은_무시되고_역할_미지정으로_시작한다() throws Exception {
        String id = createProject("ignore-role@test.dev", "{\"name\":\"FE\",\"role\":\"frontend\"}");
        mvc.perform(as(get("/api/v1/projects/" + id + "/members"), "ignore-role@test.dev"))
            .andExpect(jsonPath("$.data.items[0].role").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 같은_사람도_프로젝트마다_역할이_다를_수_있다() throws Exception {
        String a = createProject("multi@test.dev", "{\"name\":\"A 프로젝트\"}");
        String b = createProject("multi@test.dev", "{\"name\":\"B 프로젝트\"}");
        Member me = members.findByEmail("multi@test.dev").orElseThrow();
        for (String[] pr : new String[][] {{a, "backend"}, {b, "designer"}}) {
            mvc.perform(as(patch("/api/v1/projects/" + pr[0] + "/members/" + me.getId()), "multi@test.dev")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + pr[1] + "\"}"))
                .andExpect(status().isOk());
        }
        mvc.perform(as(get("/api/v1/projects/" + a + "/members"), "multi@test.dev")).andExpect(jsonPath("$.data.items[0].role").value("backend"));
        mvc.perform(as(get("/api/v1/projects/" + b + "/members"), "multi@test.dev")).andExpect(jsonPath("$.data.items[0].role").value("designer"));
        assertThat(members.findAll().stream().filter(m -> m.getEmail().equals("multi@test.dev")).count()).isEqualTo(1);
    }

    @Test
    void 미인증이면_프로젝트를_만들_수_없다() throws Exception {
        mvc.perform(post("/api/v1/projects").cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"QA 프로젝트\"}"))
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

    // --- AC24: 역할 변경 (2026-11-07) ---

    private String memberPath(Team t, Member m) {
        return "/api/v1/projects/" + t.project().getId() + "/members/" + m.getId();
    }

    private MemberRole roleOf(Team t, Member m) {
        em.flush();
        em.clear();
        return projectMembers.findByProjectIdAndMemberId(t.project().getId(), m.getId()).orElseThrow().getRole();
    }

    @Test
    void rl001_소유자가_멤버_역할을_바꾸면_그_역할만_바뀌고_알림은_없다() throws Exception {
        Team t = team();
        mvc.perform(as(patch(memberPath(t, t.dev())), t.owner().getEmail()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"frontend\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(String.valueOf(t.dev().getId())))
            .andExpect(jsonPath("$.data.role").value("frontend"));
        assertThat(roleOf(t, t.dev())).isEqualTo(MemberRole.FRONTEND);
        assertThat(notifications.count()).isZero();
    }

    @Test
    void rl001_소유자는_자기_역할도_바꿀_수_있다() throws Exception {
        Team t = team();
        mvc.perform(as(patch(memberPath(t, t.owner())), t.owner().getEmail()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"designer\"}"))
            .andExpect(status().isOk());
        assertThat(roleOf(t, t.owner())).isEqualTo(MemberRole.DESIGNER);
    }

    @Test
    void rl002_소유자가_아니면_403이고_역할이_그대로다() throws Exception {
        Team t = team();
        mvc.perform(as(patch(memberPath(t, t.designer())), t.dev().getEmail()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"planner\"}"))
            .andExpect(status().isForbidden());
        assertThat(roleOf(t, t.designer())).isEqualTo(MemberRole.DESIGNER);
    }

    @Test
    void rl003_정해진_값이_아닌_역할은_400() throws Exception {
        Team t = team();
        mvc.perform(as(patch(memberPath(t, t.dev())), t.owner().getEmail()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"developer\"}"))
            .andExpect(status().isBadRequest());
        assertThat(roleOf(t, t.dev())).isEqualTo(MemberRole.BACKEND);
    }

    @Test
    void 프로젝트_멤버가_아닌_사람의_역할_변경은_404() throws Exception {
        Team t = team();
        Member stranger = members.save(new Member("rm-stranger@test.dev", "외부"));
        mvc.perform(as(patch(memberPath(t, stranger)), t.owner().getEmail()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"planner\"}"))
            .andExpect(status().isNotFound());
    }

    // --- AC25: 작업공간 홈 (2026-11-07) ---

    @Test
    void ws001_상세는_연결_실행상태_멤버수_대기초대수와_5개_상태의_이슈수를_준다() throws Exception {
        Team t = team();
        issue(t, t.dev(), IssueStatus.NEEDS_CHECK);
        issue(t, t.dev(), IssueStatus.NEEDS_CHECK);
        issue(t, t.dev(), IssueStatus.FIXED);
        em.persist(new Invitation(t.project(), "pending@test.dev", "h-pending", t.owner(), java.time.Instant.now()));
        em.persist(new Invitation(t.project(), "expired@test.dev", "h-expired", t.owner(),
            java.time.Instant.now().minus(java.time.Duration.ofDays(8))));   // 만료 — 세지 않는다
        em.flush();

        mvc.perform(as(get("/api/v1/projects/" + t.project().getId()), t.dev().getEmail()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("rm-project"))
            .andExpect(jsonPath("$.data.owner_id").value(String.valueOf(t.owner().getId())))
            .andExpect(jsonPath("$.data.my_role").value("backend"))
            .andExpect(jsonPath("$.data.figma").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.github").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.analysis_status").value("none"))
            .andExpect(jsonPath("$.data.first_qa_status").value("none"))
            .andExpect(jsonPath("$.data.member_count").value(3))
            .andExpect(jsonPath("$.data.pending_invitation_count").value(1))
            .andExpect(jsonPath("$.data.issue_counts.needs_check").value(2))
            .andExpect(jsonPath("$.data.issue_counts.in_progress").value(0))
            .andExpect(jsonPath("$.data.issue_counts.fixed").value(1))
            .andExpect(jsonPath("$.data.issue_counts.needs_recheck").value(0))
            .andExpect(jsonPath("$.data.issue_counts.resolved").value(0));
    }

    @Test
    void ws001b_상세는_프로젝트를_만든_시각을_준다() throws Exception {
        Team t = team();
        em.flush();

        mvc.perform(as(get("/api/v1/projects/" + t.project().getId()), t.dev().getEmail()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.created_at").value(t.project().getCreatedAt().toString()));
    }

    @Test
    void ws002_비멤버의_상세_요청은_403이고_데이터가_없다() throws Exception {
        Team t = team();
        members.save(new Member("rm-out@test.dev", "외부"));
        mvc.perform(as(get("/api/v1/projects/" + t.project().getId()), "rm-out@test.dev"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").doesNotExist());   // au-002와 같은 형태 — 거부 응답에는 data 키가 없다
    }

    // --- AC15: 목록 카드 값 (pj-007, 2026-11-07) ---

    @Test
    void pj007_목록_카드에는_연결_분석상태_전체_미해결_이슈수_멤버수와_앞_3명이_담긴다() throws Exception {
        Member a = members.save(new Member("card-a@test.dev", "a"));
        Member b = members.save(new Member("card-b@test.dev", "b"));
        Member c = members.save(new Member("card-c@test.dev", "c"));
        Member d = members.save(new Member("card-d@test.dev", "d"));
        Project project = projects.save(new Project("가", "카드 설명", a));
        for (Member m : List.of(d, c, b, a)) {
            projectMembers.save(new ProjectMember(project, m, MemberRole.BACKEND));
        }
        Team t = new Team(project, a, b, c);
        issue(t, b, IssueStatus.NEEDS_CHECK);
        issue(t, b, IssueStatus.FIXED);
        issue(t, b, IssueStatus.RESOLVED);

        mvc.perform(as(get("/api/v1/projects"), a.getEmail()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].name").value("가"))
            .andExpect(jsonPath("$.data.items[0].description").value("카드 설명"))
            .andExpect(jsonPath("$.data.items[0].figma_connected").value(false))
            .andExpect(jsonPath("$.data.items[0].github_connected").value(false))
            .andExpect(jsonPath("$.data.items[0].analysis_status").value("none"))
            .andExpect(jsonPath("$.data.items[0].last_analyzed_at").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[0].issue_count").value(3))
            .andExpect(jsonPath("$.data.items[0].open_issue_count").value(2))
            .andExpect(jsonPath("$.data.items[0].member_count").value(4))
            .andExpect(jsonPath("$.data.items[0].members_preview.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].members_preview[0].name").value("a"))
            .andExpect(jsonPath("$.data.items[0].members_preview[1].name").value("b"))
            .andExpect(jsonPath("$.data.items[0].members_preview[2].name").value("c"));
    }
}
