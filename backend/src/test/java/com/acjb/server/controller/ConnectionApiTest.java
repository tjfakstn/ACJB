package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

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

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.figma.FigmaApiException;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.github.GithubApiException;
import com.acjb.server.github.GithubClient;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/** AC21 — Figma·GitHub 연결(골든 케이스 cn-001~cn-005). 외부 호출은 가짜로 바꿔 끼운다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConnectionApiTest {

    private static final String FIGMA_URL = "https://www.figma.com/design/AbC123/membership";

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired ProjectRunRepository runs;
    @MockitoBean FigmaClient figma;
    @MockitoBean GithubClient github;

    Project project;
    Member owner;
    Member member;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("cn-owner@test.dev", "소유자"));
        member = members.save(new Member("cn-member@test.dev", "멤버"));
        project = projects.save(new Project("연결 프로젝트", owner));
        projectMembers.save(new ProjectMember(project, owner, null));
        projectMembers.save(new ProjectMember(project, member, MemberRole.FRONTEND));
        when(figma.isConfigured()).thenReturn(true);
    }

    private ResultActions connect(String kind, Member as, String url) throws Exception {
        return mvc.perform(put("/api/v1/projects/" + project.getId() + "/connections/" + kind)
            .with(oauth2Login().attributes(a -> a.put("email", as.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
            .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}"));
    }

    private Project reload() {
        em.flush();
        em.clear();
        return projects.findById(project.getId()).orElseThrow();
    }

    @Test
    void cn001_접근_가능한_Figma_링크를_연결하면_파일_이름과_함께_저장된다() throws Exception {
        when(figma.fileName("AbC123")).thenReturn("멤버십 앱 리뉴얼");
        connect("figma", owner, FIGMA_URL)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.file_key").value("AbC123"))
            .andExpect(jsonPath("$.data.file_name").value("멤버십 앱 리뉴얼"))
            .andExpect(jsonPath("$.data.url").value(FIGMA_URL));
        assertThat(reload().getFigmaFileName()).isEqualTo("멤버십 앱 리뉴얼");
        mvc.perform(get("/api/v1/projects/" + project.getId()).with(oauth2Login().attributes(a -> a.put("email", member.getEmail()))))
            .andExpect(jsonPath("$.data.figma.file_name").value("멤버십 앱 리뉴얼"));
    }

    @Test
    void 공개_GitHub_저장소를_연결하면_저장소와_기본_브랜치가_저장된다() throws Exception {
        when(github.repository("team", "membership-app")).thenReturn(new GithubClient.RepoInfo("team/membership-app", "main", false));
        connect("github", owner, "https://github.com/team/membership-app")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.repo").value("team/membership-app"))
            .andExpect(jsonPath("$.data.default_branch").value("main"));
        assertThat(reload().getGithubRepo()).isEqualTo("team/membership-app");
    }

    @Test
    void cn002_형식이_틀린_링크는_400이고_외부_호출을_하지_않는다() throws Exception {
        connect("github", owner, "https://gitlab.com/team/app").andExpect(status().isBadRequest());
        connect("github", owner, "github.com/studio").andExpect(status().isBadRequest());
        connect("figma", owner, "https://example.com/design/AbC123").andExpect(status().isBadRequest());
        verify(github, never()).repository(any(), any());
        verify(figma, never()).fileName(any());
    }

    @Test
    void cn003_비공개이거나_없는_저장소는_422이고_기존_연결을_바꾸지_않는다() throws Exception {
        project.connectGithub("https://github.com/team/old", "team/old", "main", Instant.now());
        projects.saveAndFlush(project);
        when(github.repository("team", "private-app")).thenThrow(new GithubApiException("404", 404, null));
        connect("github", owner, "https://github.com/team/private-app")
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.error.code").value("CONNECTION_FORBIDDEN"));
        assertThat(reload().getGithubRepo()).isEqualTo("team/old");
    }

    @Test
    void 토큰이_읽을_수_있어도_비공개_저장소는_422다() throws Exception {
        when(github.repository("team", "secret")).thenReturn(new GithubClient.RepoInfo("team/secret", "main", true));
        connect("github", owner, "https://github.com/team/secret").andExpect(status().is(422));
        assertThat(reload().getGithubRepo()).isNull();
    }

    @Test
    void GitHub_응답이_없으면_502() throws Exception {
        when(github.repository("team", "app")).thenThrow(new GithubApiException("timeout", 0, null));
        connect("github", owner, "https://github.com/team/app")
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error.code").value("CONNECTION_FAILED"));
    }

    @Test
    void Figma_보기_권한이_없으면_422_없는_파일이면_502() throws Exception {
        when(figma.fileName("AbC123")).thenThrow(new FigmaApiException("forbidden", 403, null));
        connect("figma", owner, FIGMA_URL).andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("CONNECTION_FORBIDDEN"));
        when(figma.fileName("Nope99")).thenThrow(new FigmaApiException("not found", 404, null));
        connect("figma", owner, "https://www.figma.com/file/Nope99/x").andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error.code").value("CONNECTION_FAILED"));
        assertThat(reload().getFigmaFileKey()).isNull();
    }

    @Test
    void cn004_소유자가_아니면_403이고_외부_호출을_하지_않는다() throws Exception {
        connect("figma", member, FIGMA_URL).andExpect(status().isForbidden());
        verify(figma, never()).fileName(any());
    }

    @Test
    void cn005_비교_분석이_성공한_뒤의_링크_변경은_409() throws Exception {
        ProjectRun run = runs.save(new ProjectRun(project, RunKind.ANALYSIS, owner, Instant.now()));
        run.finish(RunStatus.SUCCEEDED, Instant.now());
        runs.saveAndFlush(run);
        connect("figma", owner, "https://www.figma.com/design/Other99/next")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("NOT_READY"));
        verify(figma, never()).fileName(any());
    }

    /** 브라우저는 Origin을 붙여 보낸다 — CORS 허용 메서드에 PUT이 없으면 연결 요청이 403 "Invalid CORS request"로 막힌다 */
    @Test
    void 프론트_주소에서_보낸_PUT_연결_요청은_CORS를_통과한다() throws Exception {
        when(github.repository("team", "app")).thenReturn(new GithubClient.RepoInfo("team/app", "main", false));
        mvc.perform(put("/api/v1/projects/" + project.getId() + "/connections/github")
                .header("Origin", "http://localhost:5173")
                .with(oauth2Login().attributes(a -> a.put("email", owner.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://github.com/team/app\"}"))
            .andExpect(status().isOk());
    }
}
