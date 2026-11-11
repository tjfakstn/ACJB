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
import com.acjb.server.figma.FigmaNode;
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

    /** Figma 앱 페이지 [공유하기]에서 복사한 모양 — node-id는 "424-2"로 온다 */
    private static final String FIGMA_URL = "https://www.figma.com/design/AbC123/membership?node-id=424-2&t=abc-1";
    private static final FigmaClient.FileOutline OUTLINE = new FigmaClient.FileOutline("멤버십 앱 리뉴얼",
        java.util.List.of(new FigmaClient.Page("0:1", "Archive"), new FigmaClient.Page("424:2", "UI Design - Refined")));

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
    void cn001_접근_가능한_Figma_페이지_링크를_연결하면_파일과_페이지가_함께_저장된다() throws Exception {
        when(figma.outline("AbC123")).thenReturn(OUTLINE);
        connect("figma", owner, FIGMA_URL)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.file_key").value("AbC123"))
            .andExpect(jsonPath("$.data.file_name").value("멤버십 앱 리뉴얼"))
            .andExpect(jsonPath("$.data.page_id").value("424:2"))
            .andExpect(jsonPath("$.data.page_name").value("UI Design - Refined"))
            .andExpect(jsonPath("$.data.scope").value("page"))
            .andExpect(jsonPath("$.data.url").value(FIGMA_URL));
        Project saved = reload();
        assertThat(saved.getFigmaFileName()).isEqualTo("멤버십 앱 리뉴얼");
        assertThat(saved.getFigmaPageId()).isEqualTo("424:2");
        mvc.perform(get("/api/v1/projects/" + project.getId()).with(oauth2Login().attributes(a -> a.put("email", member.getEmail()))))
            .andExpect(jsonPath("$.data.figma.page_name").value("UI Design - Refined"));
    }

    /** cn-006 — 페이지·섹션 링크만 받는다(2026-11-07 설만수 결정으로 섹션 허용 — 거부 예는 프레임 링크로 바꿈) */
    @Test
    void cn006_페이지_섹션_링크가_아니면_400이고_연결하지_않는다() throws Exception {
        when(figma.outline("AbC123")).thenReturn(OUTLINE);
        when(figma.nodes("AbC123", java.util.List.of("607:36215")))
            .thenReturn(java.util.Map.of("607:36215", java.util.Optional.of(new FigmaNode("607:36215", "WS-01", "FRAME"))));
        connect("figma", owner, "https://www.figma.com/design/AbC123/membership").andExpect(status().isBadRequest());
        connect("figma", owner, "https://www.figma.com/proto/AbC123/membership?node-id=424-2").andExpect(status().isBadRequest());
        connect("figma", owner, "https://www.figma.com/file/AbC123/membership?node-id=424-2").andExpect(status().isBadRequest());
        verify(figma, never()).outline(any());   // 형식이 틀리면 외부 호출 없음
        connect("figma", owner, "https://www.figma.com/design/AbC123/membership?node-id=607-36215")   // 프레임
            .andExpect(status().isBadRequest());
        assertThat(reload().getFigmaFileKey()).isNull();
    }

    /** cn-008 — 섹션 링크는 섹션 id·이름과 scope section으로 저장된다 */
    @Test
    void cn008_섹션_링크를_연결하면_섹션과_scope가_저장된다() throws Exception {
        when(figma.outline("AbC123")).thenReturn(OUTLINE);
        when(figma.nodes("AbC123", java.util.List.of("605:30464")))
            .thenReturn(java.util.Map.of("605:30464", java.util.Optional.of(new FigmaNode("605:30464", "전체 서비스 최종 통합 UI", "SECTION"))));
        connect("figma", owner, "https://www.figma.com/design/AbC123/membership?node-id=605-30464&t=x-1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page_id").value("605:30464"))
            .andExpect(jsonPath("$.data.page_name").value("전체 서비스 최종 통합 UI"))
            .andExpect(jsonPath("$.data.scope").value("section"));
        Project saved = reload();
        assertThat(saved.getFigmaScope()).isEqualTo(com.acjb.server.domain.FigmaScope.SECTION);
        mvc.perform(get("/api/v1/projects/" + project.getId()).with(oauth2Login().attributes(a -> a.put("email", member.getEmail()))))
            .andExpect(jsonPath("$.data.figma.scope").value("section"));
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
        verify(figma, never()).outline(any());
    }

    @Test
    void cn003_비공개이거나_없는_저장소는_422이고_기존_연결을_바꾸지_않는다() throws Exception {
        project.connectGithub("https://github.com/team/old", "team/old", "main", "main", Instant.now());
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
        when(figma.outline("AbC123")).thenThrow(new FigmaApiException("forbidden", 403, null));
        connect("figma", owner, FIGMA_URL).andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("CONNECTION_FORBIDDEN"));
        when(figma.outline("Nope99")).thenThrow(new FigmaApiException("not found", 404, null));
        connect("figma", owner, "https://www.figma.com/design/Nope99/x?node-id=1-2").andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error.code").value("CONNECTION_FAILED"));
        assertThat(reload().getFigmaFileKey()).isNull();
    }

    @Test
    void cn004_소유자가_아니면_403이고_외부_호출을_하지_않는다() throws Exception {
        connect("figma", member, FIGMA_URL).andExpect(status().isForbidden());
        verify(figma, never()).outline(any());
    }

    @Test
    void cn005_비교_분석이_성공한_뒤의_링크_변경은_409() throws Exception {
        ProjectRun run = runs.save(new ProjectRun(project, RunKind.ANALYSIS, owner, Instant.now()));
        run.finish(RunStatus.SUCCEEDED, Instant.now());
        runs.saveAndFlush(run);
        connect("figma", owner, "https://www.figma.com/design/Other99/next?node-id=0-1")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("NOT_READY"));
        verify(figma, never()).outline(any());
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

    /** Figma는 만료·잘못된 토큰도 403으로 준다 — 사용자 권한 문제(422)가 아니라 서버 설정 문제(503)로 알린다 */
    @Test
    void 서버_Figma_토큰이_만료됐으면_503이고_권한_없음으로_안내하지_않는다() throws Exception {
        when(figma.outline("AbC123")).thenThrow(new FigmaApiException("token expired", 403, true, null));
        connect("figma", owner, FIGMA_URL)
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("FIGMA_UNAVAILABLE"));
        assertThat(reload().getFigmaFileKey()).isNull();
    }

    /** cn-007 — 브랜치를 고른다: 빼면 기본 브랜치, 있는 브랜치는 저장, 없는 브랜치는 400이고 기존 연결 유지 */
    @Test
    void cn007_GitHub_연결_때_브랜치를_고른다() throws Exception {
        when(github.repository("team", "app")).thenReturn(new GithubClient.RepoInfo("team/app", "main", false));
        when(github.branches("team", "app")).thenReturn(java.util.List.of("main", "develop"));
        String path = "/api/v1/projects/" + project.getId() + "/connections/github";

        connect("github", owner, "https://github.com/team/app")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.branch").value("main"))
            .andExpect(jsonPath("$.data.default_branch").value("main"))
            .andExpect(jsonPath("$.data.branches[0]").value("develop"))
            .andExpect(jsonPath("$.data.branches[1]").value("main"));

        mvc.perform(put(path).with(oauth2Login().attributes(a -> a.put("email", owner.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://github.com/team/app\",\"branch\":\"develop\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.branch").value("develop"));

        when(github.branchExists("team", "app", "no-such-branch")).thenReturn(false);
        mvc.perform(put(path).with(oauth2Login().attributes(a -> a.put("email", owner.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://github.com/team/app\",\"branch\":\"no-such-branch\"}"))
            .andExpect(status().isBadRequest());

        assertThat(reload().getGithubBranch()).isEqualTo("develop");
        mvc.perform(get("/api/v1/projects/" + project.getId()).with(oauth2Login().attributes(a -> a.put("email", member.getEmail()))))
            .andExpect(jsonPath("$.data.github.branch").value("develop"));
    }

    @Test
    void 브랜치_주소를_넣으면_400이고_저장소_주소만_넣으라고_안내한다() throws Exception {
        connect("github", owner, "https://github.com/TEAMFICIAL/teamficial-fe/tree/main")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("저장소 주소만")));
        connect("github", owner, "https://github.com/TEAMFICIAL/teamficial-fe/blob/main/README.md").andExpect(status().isBadRequest());
        verify(github, never()).repository(any(), any());
        assertThat(reload().getGithubRepo()).isNull();
    }

    @Test
    void 내보내기가_막힌_Figma_파일은_422이고_보기_권한이_아니라_내보내기_설정을_안내한다() throws Exception {
        when(figma.outline("AbC123")).thenThrow(new FigmaApiException("forbidden", 403, null).markNotExportable());
        connect("figma", owner, FIGMA_URL)
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.error.code").value("CONNECTION_FORBIDDEN"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("내보내기")));
        assertThat(reload().getFigmaFileKey()).isNull();
    }

    @Autowired com.acjb.server.figma.FigmaTokenCipher cipher;

    private ResultActions connectWithToken(String url, String token) throws Exception {
        return mvc.perform(put("/api/v1/projects/" + project.getId() + "/connections/figma")
            .with(oauth2Login().attributes(a -> a.put("email", owner.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
            .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\",\"token\":\"" + token + "\"}"));
    }

    @Test
    void 서버_계정이_며칠짜리_한도에_막힌_파일은_422이고_본인_토큰을_안내한다() throws Exception {
        when(figma.outline("AbC123")).thenThrow(new FigmaApiException("rate", 429, false, 397_192, null));
        connect("figma", owner, FIGMA_URL)
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.error.code").value("CONNECTION_FORBIDDEN"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("본인 Figma 토큰")));
        assertThat(reload().getFigmaFileKey()).isNull();
    }

    @Test
    void 본인_토큰을_넣으면_그_토큰으로_확인하고_암호화해_저장하며_값은_돌려주지_않는다() throws Exception {
        String token = "figd_abcdefghijklmnopqrstuvwxyz_9f3c";
        java.util.List<Boolean> usedOwnToken = new java.util.ArrayList<>();
        when(figma.outline("AbC123")).thenAnswer(inv -> {
            usedOwnToken.add(com.acjb.server.figma.FigmaTokenScope.usingUserToken());
            return OUTLINE;
        });
        connectWithToken(FIGMA_URL, token)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.uses_own_token").value(true))
            .andExpect(jsonPath("$.data.token_hint").value("9f3c"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
        assertThat(usedOwnToken).containsExactly(true);
        Project saved = reload();
        assertThat(saved.getFigmaTokenEnc()).isNotNull().doesNotContain(token);
        assertThat(cipher.decrypt(saved.getFigmaTokenEnc())).isEqualTo(token);
        // 프로젝트 정보에도 끝 4자리만
        mvc.perform(get("/api/v1/projects/" + project.getId()).with(oauth2Login().attributes(a -> a.put("email", member.getEmail()))))
            .andExpect(jsonPath("$.data.figma.uses_own_token").value(true))
            .andExpect(jsonPath("$.data.figma.token_hint").value("9f3c"));
    }

    @Test
    void 본인_토큰으로도_못_읽으면_422이고_토큰을_저장하지_않는다() throws Exception {
        when(figma.outline("AbC123")).thenThrow(new FigmaApiException("forbidden", 403, null).markUserToken());
        connectWithToken(FIGMA_URL, "figd_abcdefghijklmnopqrstuvwxyz_0000")
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("입력한 Figma 토큰")));
        assertThat(reload().getFigmaTokenEnc()).isNull();
    }

    @Test
    void 토큰_형식이_아니면_400이고_Figma를_부르지_않는다() throws Exception {
        connectWithToken(FIGMA_URL, "not a token!").andExpect(status().isBadRequest());
        verify(figma, never()).outline(any());
    }
}
