package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.FigmaFrame;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.StyleScan;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.repository.FigmaFrameRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.StyleScanRepository;
import com.acjb.server.scan.ComputedStyleReader;

import jakarta.servlet.http.Cookie;

/**
 * POST /style-mismatches/scan, GET /style-mismatches/scans/{scanId} — 요청 검증과 상태 조회.
 * 트랜잭션 안에서 돌아 커밋 뒤 작업은 실행되지 않는다. 비교 작업 자체는 StyleScanJobTest가 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StyleScanApiTest {

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired FigmaFrameRepository frames;
    @Autowired StyleScanRepository scans;
    @MockitoBean FigmaClient figma;
    @MockitoBean ComputedStyleReader reader;

    Project project;
    Project otherProject;
    Member me;

    @BeforeEach
    void setUp() {
        project = projects.save(new Project("scan-api", owner()));
        otherProject = projects.save(new Project("scan-api-other", owner()));
        me = members.save(new Member("scan@test.dev", "멤버"));
        projectMembers.save(new ProjectMember(project, me, MemberRole.DESIGNER));
        frames.save(new FigmaFrame(project, "84:759", "GBZhzBWLOVirx9MMd5zddI", "시작하기", "https://www.figma.com/design/x"));
        when(figma.isConfigured()).thenReturn(true);
    }

    private ResultActions scan(Long projectId, String body) throws Exception {
        return mvc.perform(post("/api/v1/projects/" + projectId + "/style-mismatches/scan")
            .with(oauth2Login().attributes(a -> a.put("email", me.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** golden ng-003 — 유효한 요청이면 비동기 작업이 큐에 들어가 202와 scan_id를 돌려준다 */
    @Test
    void ng_003_유효한_요청은_202_queued() throws Exception {
        scan(project.getId(), """
            {"frame_id":"84:759","node_id":"84:759","target_url":"http://localhost:5173","selector":"button.primary"}""")
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.scan_id").isString())
            .andExpect(jsonPath("$.data.status").value("queued"));
        assertThat(scans.findAll()).filteredOn(s -> s.getProject().getId().equals(project.getId())).singleElement()
            .satisfies(s -> {
                assertThat(s.getNodeId()).isEqualTo("84:759");
                assertThat(s.getSelector()).isEqualTo("button.primary");
                assertThat(s.getRequestedBy().getId()).isEqualTo(me.getId());
            });
    }

    @Test
    void 필수_항목이_빠지면_400과_빠진_이름() throws Exception {
        scan(project.getId(), """
            {"frame_id":"84:759","target_url":"http://localhost:5173"}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.message", containsString("node_id")))
            .andExpect(jsonPath("$.error.message", containsString("selector")));
        assertThat(scans.count()).isZero();
    }

    @Test
    void 내부망_주소와_http가_아닌_주소는_400이고_저장하지_않는다() throws Exception {
        for (String url : new String[] {"http://169.254.169.254/latest/meta-data/", "http://10.0.0.5/", "file:///etc/passwd"}) {
            scan(project.getId(), """
                {"frame_id":"84:759","node_id":"84:760","target_url":"%s","selector":"button"}""".formatted(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        }
        assertThat(scans.count()).isZero();
    }

    @Test
    void 동기화되지_않은_프레임과_형식이_틀린_id는_400() throws Exception {
        scan(project.getId(), """
            {"frame_id":"1:2","node_id":"1:3","target_url":"http://localhost:5173","selector":"button"}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("figma/sync")));
        scan(project.getId(), """
            {"frame_id":"84:759","node_id":"84/760","target_url":"http://localhost:5173","selector":"button"}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("node_id")));
        assertThat(scans.count()).isZero();
    }

    @Test
    void Figma_토큰이_없으면_503() throws Exception {
        when(figma.isConfigured()).thenReturn(false);
        scan(project.getId(), """
            {"frame_id":"84:759","node_id":"84:760","target_url":"http://localhost:5173","selector":"button"}""")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("FIGMA_UNAVAILABLE"));
    }

    @Test
    void 멤버가_아닌_프로젝트에는_scan할_수_없다_AC12() throws Exception {
        scan(otherProject.getId(), """
            {"frame_id":"84:759","node_id":"84:760","target_url":"http://localhost:5173","selector":"button"}""")
            .andExpect(status().isForbidden());
    }

    @Test
    void 상태_조회는_프로젝트_안의_scan만_보이고_실패_이유를_준다() throws Exception {
        StyleScan scan = scans.save(new StyleScan(project, "84:759", "84:760", "http://localhost:5173", "button", me));
        scan.fail("selector에 맞는 요소를 화면에서 찾지 못했습니다.");
        mvc.perform(get("/api/v1/projects/" + project.getId() + "/style-mismatches/scans/" + scan.getId())
                .with(oauth2Login().attributes(a -> a.put("email", me.getEmail()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("failed"))
            .andExpect(jsonPath("$.data.mismatch_count").value(0))
            .andExpect(jsonPath("$.data.finished_at").isString())
            .andExpect(jsonPath("$.data.error_message", containsString("selector")));

        StyleScan others = scans.save(new StyleScan(otherProject, "84:759", "84:760", "http://localhost:5173", "button", me));
        projectMembers.save(new ProjectMember(otherProject, members.save(new Member("scan-other@test.dev", "남")), MemberRole.DEVELOPER));
        mvc.perform(get("/api/v1/projects/" + project.getId() + "/style-mismatches/scans/" + others.getId())
                .with(oauth2Login().attributes(a -> a.put("email", me.getEmail()))))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/" + project.getId() + "/style-mismatches/scans/abc")
                .with(oauth2Login().attributes(a -> a.put("email", me.getEmail()))))
            .andExpect(status().isNotFound());
    }

    private Member owner() {
        return members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
    }
}
