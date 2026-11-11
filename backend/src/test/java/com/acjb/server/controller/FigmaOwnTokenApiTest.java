package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.FigmaScope;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.figma.FigmaApiException;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.figma.FigmaTokenCipher;
import com.acjb.server.figma.FigmaTokenScope;
import com.acjb.server.github.GithubClient;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * 소유자 Figma 토큰을 연결된 뒤에 넣고·바꾸고·빼기(SPEC AC21, 2026-11-12).
 * 연결 확인은 서버 계정으로 통과했는데 비교 분석에서 Figma 한도에 걸린 프로젝트가 토큰을 넣을 곳이 없던 문제(오단비 발견)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FigmaOwnTokenApiTest {

    private static final String FIGMA_URL = "https://www.figma.com/design/AbC123/membership?node-id=424-2&t=abc-1";
    private static final FigmaClient.FileOutline OUTLINE = new FigmaClient.FileOutline("멤버십 앱 리뉴얼",
        List.of(new FigmaClient.Page("424:2", "UI Design - Refined")));
    private static final String TOKEN = "figd_abcdefghijklmnopqrstuvwxyz_9f3c";

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired ProjectRunRepository runs;
    @Autowired FigmaTokenCipher cipher;
    @MockitoBean FigmaClient figma;
    @MockitoBean GithubClient github;

    Project project;
    Member owner;
    Member member;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("ft-owner@test.dev", "소유자"));
        member = members.save(new Member("ft-member@test.dev", "멤버"));
        project = projects.save(new Project("토큰 프로젝트", owner));
        projectMembers.save(new ProjectMember(project, owner, null));
        projectMembers.save(new ProjectMember(project, member, MemberRole.FRONTEND));
        when(figma.isConfigured()).thenReturn(true);
    }

    /** 서버 계정으로 연결까지 끝난 상태(오단비가 본 상태) */
    private void connectedWithServerAccount() {
        project.connectFigma(FIGMA_URL, "AbC123", "멤버십 앱 리뉴얼", "424:2", "UI Design - Refined", FigmaScope.PAGE, Instant.now());
        projects.saveAndFlush(project);
    }

    private ResultActions as(Member m, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.with(oauth2Login().attributes(a -> a.put("email", m.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t"));
    }

    private ResultActions putToken(Member m, String token) throws Exception {
        return as(m, put("/api/v1/projects/" + project.getId() + "/connections/figma/token")
            .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"));
    }

    private Project reload() {
        em.flush();
        em.clear();
        return projects.findById(project.getId()).orElseThrow();
    }

    @Test
    void 연결된_뒤_토큰만_넣으면_연결된_파일을_그_토큰으로_확인하고_암호화해_저장한다() throws Exception {
        connectedWithServerAccount();
        List<Boolean> usedOwnToken = new ArrayList<>();
        when(figma.outline("AbC123")).thenAnswer(inv -> {
            usedOwnToken.add(FigmaTokenScope.usingUserToken());
            return OUTLINE;
        });

        putToken(owner, TOKEN)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.url").value(FIGMA_URL))
            .andExpect(jsonPath("$.data.page_id").value("424:2"))
            .andExpect(jsonPath("$.data.uses_own_token").value(true))
            .andExpect(jsonPath("$.data.token_hint").value("9f3c"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(Matchers.not(Matchers.containsString(TOKEN))));

        assertThat(usedOwnToken).containsExactly(true);
        Project saved = reload();
        assertThat(saved.getFigmaUrl()).isEqualTo(FIGMA_URL);
        assertThat(cipher.decrypt(saved.getFigmaTokenEnc())).isEqualTo(TOKEN);
    }

    @Test
    void 비교_분석이_성공한_뒤에도_링크가_그대로라_토큰은_넣을_수_있다() throws Exception {
        connectedWithServerAccount();
        ProjectRun run = runs.save(new ProjectRun(project, RunKind.ANALYSIS, owner, Instant.now()));
        run.finish(RunStatus.SUCCEEDED, Instant.now());
        runs.saveAndFlush(run);
        when(figma.outline("AbC123")).thenReturn(OUTLINE);

        putToken(owner, TOKEN).andExpect(status().isOk()).andExpect(jsonPath("$.data.uses_own_token").value(true));
    }

    @Test
    void Figma가_연결되지_않았으면_409이고_Figma를_부르지_않는다() throws Exception {
        putToken(owner, TOKEN).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NOT_READY"));
        verify(figma, never()).outline(any());
    }

    @Test
    void 소유자가_아니면_403이고_형식이_아니면_400이며_둘_다_Figma를_부르지_않는다() throws Exception {
        connectedWithServerAccount();
        putToken(member, TOKEN).andExpect(status().isForbidden());
        putToken(owner, "not a token!").andExpect(status().isBadRequest());
        verify(figma, never()).outline(any());
        assertThat(reload().getFigmaTokenEnc()).isNull();
    }

    @Test
    void 새_토큰으로_못_읽으면_422이고_전에_넣은_토큰을_그대로_둔다() throws Exception {
        connectedWithServerAccount();
        project.changeFigmaToken(cipher.encrypt(TOKEN), "9f3c");
        projects.saveAndFlush(project);
        when(figma.outline("AbC123")).thenThrow(new FigmaApiException("forbidden", 403, null).markUserToken());

        putToken(owner, "figd_zzzzzzzzzzzzzzzzzzzzzzzzzz_0000")
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.error.message").value(Matchers.containsString("입력한 Figma 토큰")));

        Project saved = reload();
        assertThat(cipher.decrypt(saved.getFigmaTokenEnc())).isEqualTo(TOKEN);
        assertThat(saved.getFigmaTokenHint()).isEqualTo("9f3c");
    }

    @Test
    void 토큰을_빼면_서버_계정으로_돌아가고_소유자만_뺄_수_있다() throws Exception {
        connectedWithServerAccount();
        project.changeFigmaToken(cipher.encrypt(TOKEN), "9f3c");
        projects.saveAndFlush(project);

        as(member, delete("/api/v1/projects/" + project.getId() + "/connections/figma/token")).andExpect(status().isForbidden());
        as(owner, delete("/api/v1/projects/" + project.getId() + "/connections/figma/token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.uses_own_token").value(false))
            .andExpect(jsonPath("$.data.token_hint").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.url").value(FIGMA_URL));
        Project saved = reload();
        assertThat(saved.getFigmaTokenEnc()).isNull();
        assertThat(saved.getFigmaFileKey()).isEqualTo("AbC123");
    }

    @Test
    void 처음_연결할_때_서버_계정이_짧은_Figma_한도에_걸려도_422로_본인_토큰을_안내한다() throws Exception {
        when(figma.outline("AbC123")).thenThrow(new FigmaApiException("rate", 429, false, 30, null));
        as(owner, put("/api/v1/projects/" + project.getId() + "/connections/figma")
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + FIGMA_URL + "\"}"))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.error.code").value("CONNECTION_FORBIDDEN"))
            .andExpect(jsonPath("$.error.message").value(Matchers.containsString("본인 Figma 토큰")));
        assertThat(reload().getFigmaFileKey()).isNull();
    }
}
