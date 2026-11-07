package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.acjb.server.domain.NotificationEvent;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;
import com.acjb.server.repository.StyleMismatchRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST·GET /projects/{projectId}/qa-issues. 메서드 이름 앞의 id는 tests/harness/golden_cases.yaml 케이스와 대응한다.
 * 요청자는 oauth2Login()으로 흉내 내고(운영 코드에 우회 없음), POST는 쿠키와 같은 값을 X-XSRF-TOKEN에 실어 CSRF를 통과한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QaIssueApiTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired StyleMismatchRepository mismatches;
    @Autowired StatusHistoryRepository histories;
    @Autowired NotificationRepository notifications;

    Project project;
    Project otherProject;
    Member reporter;   // member-1
    Member assignee;   // member-2
    Member outsider;   // 다른 프로젝트 멤버

    @BeforeEach
    void setUp() {
        project = projects.save(new Project("issue-api", owner()));
        otherProject = projects.save(new Project("issue-api-other", owner()));
        reporter = members.save(new Member("reporter@test.dev", "등록자"));
        assignee = members.save(new Member("assignee@test.dev", "담당자"));
        outsider = members.save(new Member("outsider@test.dev", "외부"));
        projectMembers.save(new ProjectMember(project, reporter, MemberRole.DESIGNER));
        projectMembers.save(new ProjectMember(project, assignee, MemberRole.BACKEND));
        projectMembers.save(new ProjectMember(otherProject, outsider, MemberRole.PLANNER));
        projectMembers.save(new ProjectMember(otherProject, reporter, MemberRole.DESIGNER));
    }

    private ResultActions postIssue(Project p, Member actor, String body) throws Exception {
        return mvc.perform(post("/api/v1/projects/" + p.getId() + "/qa-issues")
            .with(oauth2Login().attributes(a -> a.put("email", actor.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getAs(Member actor, String path) throws Exception {
        return mvc.perform(get("/api/v1/projects/" + path)
            .with(oauth2Login().attributes(a -> a.put("email", actor.getEmail()))));
    }

    private String createIssue(Project p, String body) throws Exception {
        String res = postIssue(p, reporter, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).path("data").path("id").asString();
    }

    private static final String VALID = """
        {"description":"아이콘이 너무 커요","screen_location":"홈 화면 상단 알림 아이콘",
         "repro_steps":["홈 진입","상단 알림 아이콘 확인"],"platform_scope":"aos"}""";

    // --- AC1 ---

    @Test
    void qi001_screen_location이_없으면_400이고_빠진_항목_이름을_담는다() throws Exception {
        postIssue(project, reporter, """
            {"description":"아이콘이 너무 커요","repro_steps":["홈 진입","아이콘 확인"],"platform_scope":"common"}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").exists())
            .andExpect(jsonPath("$.error.message", containsString("screen_location")));
        assertThat(issues.count()).isZero();
    }

    @Test
    void 필수_항목이_여럿_비면_빠진_이름을_모두_담는다() throws Exception {
        postIssue(project, reporter, "{\"repro_steps\":[]}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("description")))
            .andExpect(jsonPath("$.error.message", containsString("screen_location")))
            .andExpect(jsonPath("$.error.message", containsString("repro_steps")))
            .andExpect(jsonPath("$.error.message", containsString("platform_scope")));
    }

    @Test
    void qi002_필수_항목이_모두_있으면_201이고_needs_check로_등록된다() throws Exception {
        postIssue(project, reporter, VALID)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").exists())
            .andExpect(jsonPath("$.data.status").value("needs_check"))
            .andExpect(jsonPath("$.data.reported_by").value(String.valueOf(reporter.getId())))
            .andExpect(jsonPath("$.data.created_at").exists())
            .andExpect(jsonPath("$.data.status_history.length()").value(1))
            .andExpect(jsonPath("$.data.status_history[0].status").value("needs_check"));
    }

    @Test
    void ng001_공백만_있는_description은_빈_값으로_보고_저장하지_않는다() throws Exception {
        postIssue(project, reporter, """
            {"description":"   ","screen_location":"홈 화면","repro_steps":["홈 진입"],"platform_scope":"common"}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("description")));
        assertThat(issues.count()).isZero();
    }

    @Test
    void 공백만_있는_재현_절차_항목도_빈_값이다() throws Exception {
        postIssue(project, reporter, """
            {"description":"d","screen_location":"홈","repro_steps":["홈 진입"," "],"platform_scope":"common"}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("repro_steps")));
    }

    @Test
    void 정해지지_않은_platform_scope_값은_400() throws Exception {
        postIssue(project, reporter, """
            {"description":"d","screen_location":"홈","repro_steps":["홈 진입"],"platform_scope":"web"}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("platform_scope")));
    }

    // --- AC2 ---

    @Test
    void sm001_style_mismatch에_actual_value가_없으면_400() throws Exception {
        postIssue(project, reporter, """
            {"description":"버튼 색이 달라요","screen_location":"로그인 화면 버튼","repro_steps":["로그인 화면 진입"],
             "platform_scope":"common","style_mismatch":{"property":"color","expected_value":"Gray 80"}}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").exists())
            .andExpect(jsonPath("$.error.message", containsString("actual_value")));
    }

    @Test
    void ng002_style_mismatch가_잘못되면_이슈_차이_알림_모두_저장하지_않는다() throws Exception {
        postIssue(project, reporter, """
            {"description":"버튼 색이 달라요","screen_location":"로그인 화면 버튼","repro_steps":["로그인 화면 진입"],
             "platform_scope":"common","assigned_to":"%d","style_mismatch":{"property":"color","actual_value":"#333333"}}"""
            .formatted(assignee.getId()))
            .andExpect(status().isBadRequest());
        assertThat(issues.count()).isZero();
        assertThat(mismatches.count()).isZero();
        assertThat(notifications.count()).isZero();
    }

    @Test
    void 두_값이_모두_있는_style_mismatch는_저장되고_이슈에_연결된다() throws Exception {
        postIssue(project, reporter, """
            {"description":"버튼 색이 달라요","screen_location":"로그인 화면 버튼","repro_steps":["로그인 화면 진입"],
             "platform_scope":"common","style_mismatch":{"property":"color","expected_value":"Gray 80","actual_value":"#333333"}}""")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.style_mismatch.property").value("color"))
            .andExpect(jsonPath("$.data.style_mismatch.expected_value").value("Gray 80"))
            .andExpect(jsonPath("$.data.style_mismatch.actual_value").value("#333333"));
        assertThat(mismatches.count()).isEqualTo(1);
    }

    // --- AC3 ---

    @Test
    void nt001_담당자를_지정해_등록하면_담당자에게_issue_created_1건() throws Exception {
        String id = createIssue(project, """
            {"description":"아이콘이 너무 커요","screen_location":"홈 화면","repro_steps":["홈 진입"],
             "platform_scope":"common","assigned_to":"%d"}""".formatted(assignee.getId()));
        assertThat(notifications.countByIssueIdAndEvent(Long.valueOf(id), NotificationEvent.ISSUE_CREATED)).isEqualTo(1);
        assertThat(notifications.findAll()).singleElement()
            .satisfies(n -> assertThat(n.getRecipient().getId()).isEqualTo(assignee.getId()));
    }

    @Test
    void nt004_담당자_없이_등록하면_알림이_생기지_않는다() throws Exception {
        createIssue(project, """
            {"description":"텍스트가 잘려요","screen_location":"결제 화면 총액","repro_steps":["결제 화면 진입"],"platform_scope":"ios"}""");
        assertThat(notifications.count()).isZero();
    }

    @Test
    void 프로젝트_멤버가_아닌_사람을_담당자로_지정하면_400이고_저장하지_않는다() throws Exception {
        postIssue(project, reporter, """
            {"description":"d","screen_location":"홈","repro_steps":["홈 진입"],"platform_scope":"common","assigned_to":"%d"}"""
            .formatted(outsider.getId()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("assigned_to")));
        assertThat(issues.count()).isZero();
    }

    // --- N8 ---

    @Test
    void ng008_body의_reported_by는_무시하고_로그인_사용자로_기록한다() throws Exception {
        postIssue(project, reporter, """
            {"description":"아이콘이 너무 커요","screen_location":"홈 화면 상단 알림 아이콘","repro_steps":["홈 진입"],
             "platform_scope":"aos","reported_by":"%d"}""".formatted(assignee.getId()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.reported_by").value(String.valueOf(reporter.getId())));
    }

    // --- 조회 (AC5, N10) ---

    @Test
    void 상세_조회는_등록_때의_재현_정보와_환경을_그대로_반환한다() throws Exception {
        String id = createIssue(project, """
            {"description":"아이콘이 너무 커요","screen_location":"홈 화면 상단 알림 아이콘","repro_steps":["홈 진입","아이콘 확인"],
             "platform_scope":"aos","environment":{"platform":"aos","device":"Pixel 8","deploy_stage":"staging"},
             "screenshots":["https://example.com/a.png"]}""");
        getAs(reporter, project.getId() + "/qa-issues/" + id)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.repro_steps[0]").value("홈 진입"))
            .andExpect(jsonPath("$.data.repro_steps[1]").value("아이콘 확인"))
            .andExpect(jsonPath("$.data.screen_location").value("홈 화면 상단 알림 아이콘"))
            .andExpect(jsonPath("$.data.environment.platform").value("aos"))
            .andExpect(jsonPath("$.data.environment.device").value("Pixel 8"))
            .andExpect(jsonPath("$.data.environment.deploy_stage").value("staging"))
            .andExpect(jsonPath("$.data.screenshots[0]").value("https://example.com/a.png"))
            .andExpect(jsonPath("$.data.status_history[0].changed_by").value(String.valueOf(reporter.getId())));
    }

    @Test
    void ng010_다른_프로젝트의_issueId는_내_프로젝트_경로로_조회되지_않는다() throws Exception {
        String otherIssue = createIssue(otherProject, VALID);
        // reporter는 두 프로젝트 모두의 멤버 — project 경로에 otherProject의 이슈 id를 넣는다
        getAs(reporter, project.getId() + "/qa-issues/" + otherIssue)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 없는_issueId와_숫자가_아닌_issueId는_404() throws Exception {
        getAs(reporter, project.getId() + "/qa-issues/999999999").andExpect(status().isNotFound());
        getAs(reporter, project.getId() + "/qa-issues/abc").andExpect(status().isNotFound());
    }

    @Test
    void 목록은_그_프로젝트_이슈만_created_at_desc_id_desc로_반환한다() throws Exception {
        String first = createIssue(project, VALID);
        String second = createIssue(project, VALID);
        createIssue(otherProject, VALID);

        String res = getAs(reporter, project.getId() + "/qa-issues")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andReturn().getResponse().getContentAsString();
        JsonNode items = json.readTree(res).path("data").path("items");
        assertThat(items.get(0).path("id").asString()).isEqualTo(second);
        assertThat(items.get(1).path("id").asString()).isEqualTo(first);
    }

    @Test
    void 목록은_status와_assigned_to로_거른다() throws Exception {
        createIssue(project, VALID);
        String assigned = createIssue(project, """
            {"description":"d","screen_location":"홈","repro_steps":["홈 진입"],"platform_scope":"common","assigned_to":"%d"}"""
            .formatted(assignee.getId()));

        getAs(reporter, project.getId() + "/qa-issues?assigned_to=" + assignee.getId())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(assigned));
        getAs(reporter, project.getId() + "/qa-issues?status=fixed")
            .andExpect(jsonPath("$.data.total").value(0));
        getAs(reporter, project.getId() + "/qa-issues?status=needs_check")
            .andExpect(jsonPath("$.data.total").value(2));
        getAs(reporter, project.getId() + "/qa-issues?status=closed")
            .andExpect(status().isBadRequest());
    }

    /** 프로젝트는 소유자가 있어야 만들 수 있다(AC16·N15) — 테스트마다 소유자용 멤버를 따로 만든다 */
    private Member owner() {
        return members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
    }
}
