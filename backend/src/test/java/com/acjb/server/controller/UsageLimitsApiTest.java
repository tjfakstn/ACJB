package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.config.StaleRunRecovery;
import com.acjb.server.domain.FigmaScope;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.parse.IssueTextParser;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.NotificationRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * 사용량 한도(2026-11-11) — 한도를 넘으면 429 RATE_LIMITED이고 아무것도 만들지 않는다.
 * 재시작으로 끊긴 실행은 시작 시 실패로 정리되고 실행한 사람에게 알린다. 한도는 이 테스트에서만 1로 낮춘다.
 * 설정이 달라 컨텍스트를 따로 띄우므로 끝나면 닫는다 — 열어 두면 DB 연결 수가 모자라 다른 테스트가 뜨지 못했다
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "app.limits.projects-per-owner=1",
    "app.limits.analysis-per-day=1",
    "app.limits.re-qa-per-day=1",
    "app.limits.parse-per-day=1",
    "app.limits.invitations-per-day=1",
})
class UsageLimitsApiTest {

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired ProjectRunRepository runs;
    @Autowired NotificationRepository notifications;
    @Autowired StaleRunRecovery recovery;
    @MockitoBean IssueTextParser parser;

    Member owner;
    Project project;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("lim-owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
        project = projects.save(new Project("한도 프로젝트", owner));
        projectMembers.save(new ProjectMember(project, owner, null));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r) {
        return r.with(oauth2Login().attributes(a -> a.put("email", owner.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t").contentType(MediaType.APPLICATION_JSON);
    }

    private ProjectRun finishedRun(RunKind kind, RunStatus status) {
        ProjectRun run = new ProjectRun(project, kind, owner, Instant.now());
        if (status == RunStatus.FAILED) {
            run.fail("실패", Instant.now());
        } else {
            run.start();
        }
        return runs.save(run);
    }

    @Test
    void 소유한_프로젝트가_한도면_새_프로젝트를_만들지_않는다() throws Exception {
        long before = projects.count();
        mvc.perform(as(post("/api/v1/projects")).content("{\"name\":\"두번째\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertThat(projects.count()).isEqualTo(before);
    }

    @Test
    void 하루_비교_분석_횟수를_넘으면_실패한_실행이어도_다시_시작하지_못한다() throws Exception {
        project.connectFigma("https://www.figma.com/design/K/x?node-id=1-1", "K", "시안", "1:1", "최종", FigmaScope.PAGE, Instant.now());
        project.connectGithub("https://github.com/o/r", "o/r", "main", "main", Instant.now());
        finishedRun(RunKind.ANALYSIS, RunStatus.FAILED);
        em.flush();
        mvc.perform(as(post("/api/v1/projects/" + project.getId() + "/analysis")))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertThat(runs.countByProjectIdAndKindAndCreatedAtAfter(project.getId(), RunKind.ANALYSIS, Instant.EPOCH)).isEqualTo(1);
    }

    @Test
    void 하루_AI_문장_정리_횟수를_넘으면_429() throws Exception {
        String path = "/api/v1/projects/" + project.getId() + "/qa-issues/parse";
        mvc.perform(as(post(path)).content("{\"text\":\"로그인 버튼 색이 달라요\"}"));
        mvc.perform(as(post(path)).content("{\"text\":\"로그인 버튼 색이 달라요\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void 하루_초대_메일_수를_넘으면_초대를_만들지_않는다() throws Exception {
        String path = "/api/v1/projects/" + project.getId() + "/invitations";
        mvc.perform(as(post(path)).content("{\"email\":\"lim-a@test.dev\"}")).andExpect(status().isCreated());
        mvc.perform(as(post(path)).content("{\"email\":\"lim-b@test.dev\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        long invited = em.createQuery("select count(i) from Invitation i where i.project.id = :p", Long.class)
            .setParameter("p", project.getId()).getSingleResult();
        assertThat(invited).isEqualTo(1);
    }

    @Test
    void 서버가_다시_시작되면_끝나지_못한_실행을_실패로_바꾸고_실행한_사람에게_알린다() {
        ProjectRun running = finishedRun(RunKind.FIRST_QA, RunStatus.RUNNING);
        ProjectRun queued = runs.save(new ProjectRun(project, RunKind.RE_QA, owner, Instant.now()));
        ProjectRun failed = finishedRun(RunKind.ANALYSIS, RunStatus.FAILED);
        em.flush();

        recovery.recover();
        em.flush();
        em.clear();

        for (Long id : List.of(running.getId(), queued.getId())) {
            ProjectRun r = runs.findById(id).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(r.getErrorMessage()).contains("다시 시작");
            assertThat(r.getFinishedAt()).isNotNull();
        }
        assertThat(runs.findById(failed.getId()).orElseThrow().getErrorMessage()).isEqualTo("실패");
        long runFailed = em.createQuery("select count(n) from Notification n where n.run.id in :ids and n.recipient.id = :m", Long.class)
            .setParameter("ids", List.of(running.getId(), queued.getId())).setParameter("m", owner.getId()).getSingleResult();
        assertThat(runFailed).isEqualTo(2);
    }
}
