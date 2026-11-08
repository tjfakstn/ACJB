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
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.StyleProperty;
import com.acjb.server.domain.StyleScan;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StyleMismatchRepository;
import com.acjb.server.repository.StyleScanRepository;

import jakarta.servlet.http.Cookie;

/** GET /style-mismatches(목록·상세), POST /style-mismatches/{mismatchId}/issues */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StyleMismatchApiTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired StyleMismatchRepository mismatches;
    @Autowired StyleScanRepository scans;
    @Autowired QAIssueRepository issues;
    @Autowired NotificationRepository notifications;

    Project project;
    Project otherProject;
    Member me;

    @BeforeEach
    void setUp() {
        project = projects.save(new Project("sm-api", owner()));
        otherProject = projects.save(new Project("sm-api-other", owner()));
        me = members.save(new Member("sm@test.dev", "멤버"));
        projectMembers.save(new ProjectMember(project, me, MemberRole.DESIGNER));
        projectMembers.save(new ProjectMember(otherProject, me, MemberRole.DESIGNER));
    }

    private ResultActions getAs(String path) throws Exception {
        return mvc.perform(get("/api/v1/projects/" + path).with(oauth2Login().attributes(a -> a.put("email", me.getEmail()))));
    }

    private ResultActions createIssue(StyleMismatch m, String body) throws Exception {
        return mvc.perform(post("/api/v1/projects/" + project.getId() + "/style-mismatches/" + m.getId() + "/issues")
            .with(oauth2Login().attributes(a -> a.put("email", me.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static final String ISSUE_BODY = """
        {"description":"버튼 색이 달라요","screen_location":"로그인 화면 버튼","repro_steps":["로그인 화면 진입"],
         "platform_scope":"common","environment":{"platform":"web","browser":"Chrome 130"}}""";

    // --- 목록·상세 ---

    @Test
    void 목록은_그_프로젝트_것만_created_at_desc_id_desc로_사람_입력과_scan을_함께_반환한다() throws Exception {
        StyleMismatch manual = mismatches.save(StyleMismatch.manual(project, "84:759", "설정 화면", StyleProperty.SHADOW, "0 2 4", "없음"));
        StyleScan scan = scans.save(new StyleScan(project, "84:759", "84:800", "https://app.test/home", ".btn", me));
        StyleMismatch scanned = mismatches.save(StyleMismatch.fromScan(scan, StyleProperty.COLOR, "#1A73E8", "#1A73E9"));
        mismatches.save(StyleMismatch.manual(otherProject, null, null, StyleProperty.RADIUS, "8px", "6px"));

        getAs(project.getId() + "/style-mismatches")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(scanned.getId())))
            .andExpect(jsonPath("$.data.items[0].property").value("color"))
            .andExpect(jsonPath("$.data.items[0].frame_id").value("84:759"))
            .andExpect(jsonPath("$.data.items[0].screen").value("https://app.test/home"))
            .andExpect(jsonPath("$.data.items[1].id").value(String.valueOf(manual.getId())));
    }

    @Test
    void frame_id와_property로_거르고_정해지지_않은_property는_400() throws Exception {
        mismatches.save(StyleMismatch.manual(project, "84:759", null, StyleProperty.COLOR, "a", "b"));
        mismatches.save(StyleMismatch.manual(project, "84:900", null, StyleProperty.COLOR, "a", "b"));
        mismatches.save(StyleMismatch.manual(project, "84:759", null, StyleProperty.RADIUS, "8px", "6px"));

        getAs(project.getId() + "/style-mismatches?frame_id=84:759").andExpect(jsonPath("$.data.total").value(2));
        getAs(project.getId() + "/style-mismatches?property=radius").andExpect(jsonPath("$.data.total").value(1));
        getAs(project.getId() + "/style-mismatches?frame_id=84:759&property=color").andExpect(jsonPath("$.data.total").value(1));
        getAs(project.getId() + "/style-mismatches?property=size").andExpect(status().isBadRequest());
    }

    @Test
    void 상세는_그_프로젝트_기록만_다른_프로젝트나_없는_id는_404() throws Exception {
        StyleMismatch mine = mismatches.save(StyleMismatch.manual(project, null, "홈", StyleProperty.RADIUS, "8px", "6px"));
        StyleMismatch foreign = mismatches.save(StyleMismatch.manual(otherProject, null, null, StyleProperty.RADIUS, "8px", "6px"));

        getAs(project.getId() + "/style-mismatches/" + mine.getId())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.expected_value").value("8px"))
            .andExpect(jsonPath("$.data.actual_value").value("6px"));
        getAs(project.getId() + "/style-mismatches/" + foreign.getId())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data").doesNotExist());
        getAs(project.getId() + "/style-mismatches/abc").andExpect(status().isNotFound());
    }

    // --- 차이로 이슈 만들기 ---

    @Test
    void 기록된_차이로_이슈를_만들면_그_차이가_연결되고_needs_check로_시작한다() throws Exception {
        StyleMismatch m = mismatches.save(StyleMismatch.manual(project, null, null, StyleProperty.COLOR, "Gray 80", "#333333"));
        createIssue(m, ISSUE_BODY)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("needs_check"))
            .andExpect(jsonPath("$.data.reported_by").value(String.valueOf(me.getId())))
            .andExpect(jsonPath("$.data.assigned_team").isEmpty())
            .andExpect(jsonPath("$.data.style_mismatch.id").value(String.valueOf(m.getId())))
            .andExpect(jsonPath("$.data.style_mismatch.expected_value").value("Gray 80"))
            .andExpect(jsonPath("$.data.style_mismatch.actual_value").value("#333333"))
            .andExpect(jsonPath("$.data.environment.platform").value("web"));
        assertThat(mismatches.count()).isEqualTo(1);   // 새 차이 행을 만들지 않는다
        assertThat(notifications.count()).isZero();    // 담당자 없이 시작 — AC3 알림 없음
    }

    @Test
    void 재현_정보가_비면_AC1대로_400이고_이슈를_만들지_않는다() throws Exception {
        StyleMismatch m = mismatches.save(StyleMismatch.manual(project, null, null, StyleProperty.COLOR, "a", "b"));
        createIssue(m, "{\"screen_location\":\"홈\",\"repro_steps\":[],\"platform_scope\":\"common\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("description")))
            .andExpect(jsonPath("$.error.message", containsString("repro_steps")));
        assertThat(issues.count()).isZero();
    }

    @Test
    void 다른_프로젝트의_차이로는_이슈를_만들_수_없다_404() throws Exception {
        StyleMismatch foreign = mismatches.save(StyleMismatch.manual(otherProject, null, null, StyleProperty.COLOR, "a", "b"));
        createIssue(foreign, ISSUE_BODY).andExpect(status().isNotFound());
        assertThat(issues.count()).isZero();
    }

    @Test
    void body의_assigned_team과_reported_by는_받지_않는다() throws Exception {
        Member other = members.save(new Member("sm-other@test.dev", "다른"));
        projectMembers.save(new ProjectMember(project, other, MemberRole.BACKEND));
        StyleMismatch m = mismatches.save(StyleMismatch.manual(project, null, null, StyleProperty.COLOR, "a", "b"));
        createIssue(m, """
            {"description":"d","screen_location":"홈","repro_steps":["홈 진입"],"platform_scope":"common",
             "assigned_team":"backend","reported_by":"%d"}""".formatted(other.getId()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.assigned_team").isEmpty())
            .andExpect(jsonPath("$.data.reported_by").value(String.valueOf(me.getId())));
        assertThat(notifications.count()).isZero();
    }

    /** 프로젝트는 소유자가 있어야 만들 수 있다(AC16·N15) — 테스트마다 소유자용 멤버를 따로 만든다 */
    private Member owner() {
        return members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
    }
}
