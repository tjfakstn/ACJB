package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.analysis.AnalysisJob;
import com.acjb.server.analysis.DesignCodeMapper;
import com.acjb.server.analysis.MappingFailedException;
import com.acjb.server.domain.DesignCodeMapping;
import com.acjb.server.domain.FigmaScope;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.ProjectRun;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.figma.FigmaNode;
import com.acjb.server.github.GithubClient;
import com.acjb.server.repository.DesignCodeMappingRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AC22 비교 분석·AC27 매핑 조회(골든 케이스 an-001~an-006). Figma·GitHub·LLM은 가짜로 바꿔 끼운다 — 실제 API를 부르지 않는다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AnalysisApiTest {

    private static final String SHA = "a".repeat(40);

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired JsonMapper json;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired ProjectRunRepository runs;
    @Autowired DesignCodeMappingRepository mappings;
    @Autowired AnalysisJob job;
    @MockitoBean FigmaClient figma;
    @MockitoBean GithubClient github;
    @MockitoBean DesignCodeMapper mapper;

    Project project;
    Member owner;
    Member member;
    Member outsider;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("an-owner@test.dev", "소유자"));
        member = members.save(new Member("an-member@test.dev", "멤버"));
        outsider = members.save(new Member("an-outsider@test.dev", "외부인"));
        project = projects.save(new Project("분석 프로젝트", owner));
        projectMembers.save(new ProjectMember(project, owner, null));
        projectMembers.save(new ProjectMember(project, member, MemberRole.FRONTEND));
    }

    private void connectBoth() {
        project.connectFigma("https://www.figma.com/design/K/x?node-id=1-1", "K", "시안", "1:1", "최종", FigmaScope.PAGE, Instant.now());
        project.connectGithub("https://github.com/o/r", "o/r", "main", "main", Instant.now());
        em.flush();
    }

    private ResultActions start(Member as) throws Exception {
        return mvc.perform(post("/api/v1/projects/" + project.getId() + "/analysis")
            .with(oauth2Login().attributes(a -> a.put("email", as.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t"));
    }

    private ResultActions getAs(String path, Member as) throws Exception {
        return mvc.perform(get("/api/v1/projects/" + project.getId() + path).with(oauth2Login().attributes(a -> a.put("email", as.getEmail()))));
    }

    private Long latestRunId() {
        return runs.findFirstByProjectIdAndKindOrderByCreatedAtDescIdDesc(project.getId(), RunKind.ANALYSIS).orElseThrow().getId();
    }

    private ProjectRun reloadRun(Long id) {
        em.flush();
        em.clear();
        return runs.findById(id).orElseThrow();
    }

    private long count(String entity) {
        return em.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult();
    }

    /** 페이지 1:1 > 섹션 S1 > 프레임 F1(노드 N1 포함)·F2. 저장소에는 src/A.tsx 하나 */
    private void stubSources() {
        when(github.commitSha("o", "r", "main")).thenReturn(SHA);
        when(github.tree("o", "r", SHA)).thenReturn(List.of(new GithubClient.TreeFile("src/A.tsx", 30), new GithubClient.TreeFile("README.md", 10)));
        when(github.fileContent("o", "r", SHA, "src/A.tsx")).thenReturn("export function Card() { return null }");
        when(figma.children("K", "1:1")).thenReturn(Optional.of(List.of(new FigmaNode("S1", "최종 섹션", "SECTION"))));
        when(figma.children("K", "S1")).thenReturn(Optional.of(List.of(new FigmaNode("F1", "홈", "FRAME"), new FigmaNode("F2", "설정", "FRAME"))));
        Map<String, JsonNode> docs = Map.of(
            "F1", json.readTree("{\"id\":\"F1\",\"type\":\"FRAME\",\"name\":\"홈\",\"children\":[{\"id\":\"N1\",\"type\":\"FRAME\",\"name\":\"카드\","
                + "\"children\":[{\"id\":\"T1\",\"type\":\"TEXT\",\"name\":\"제목\",\"characters\":\"안녕\"}]}]}"),
            "F2", json.readTree("{\"id\":\"F2\",\"type\":\"FRAME\",\"name\":\"설정\",\"children\":[]}"));
        when(figma.nodeDocuments(eq("K"), anyList())).thenAnswer(inv -> {
            Map<String, JsonNode> out = new LinkedHashMap<>();
            for (Object id : (List<?>) inv.getArgument(1)) {
                if (docs.containsKey(id)) {
                    out.put((String) id, docs.get(id));
                }
            }
            return out;
        });
    }

    /** an-001 */
    @Test
    void an001_GitHub가_연결되지_않았으면_409_NOT_READY다() throws Exception {
        project.connectFigma("https://www.figma.com/design/K/x?node-id=1-1", "K", "시안", "1:1", "최종", FigmaScope.PAGE, Instant.now());
        em.flush();
        start(owner).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NOT_READY"));
        assertThat(runs.count()).isZero();
    }

    /** an-002 — 접수(202) 후 성공해도 이슈·StyleMismatch는 늘지 않는다(N16) */
    @Test
    void an002_접수되고_성공해도_이슈와_스타일차이는_늘지_않는다() throws Exception {
        connectBoth();
        stubSources();
        when(mapper.mapFrames(anyList(), any())).thenReturn(List.of(new DesignCodeMapper.FrameMatch("F1", "src/A.tsx")));
        when(mapper.mapNodes(any(), any())).thenReturn(List.of());
        long issues = count("QAIssue");
        long mismatches = count("StyleMismatch");
        start(owner).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.kind").value("analysis"))
            .andExpect(jsonPath("$.data.status").value("queued"));
        Long runId = latestRunId();
        job.run(runId);
        assertThat(reloadRun(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(count("QAIssue")).isEqualTo(issues);
        assertThat(count("StyleMismatch")).isEqualTo(mismatches);
    }

    /** an-003 */
    @Test
    void an003_실행_중에_다시_시작하면_409이고_실행이_늘지_않는다() throws Exception {
        connectBoth();
        ProjectRun running = runs.save(new ProjectRun(project, RunKind.ANALYSIS, owner, Instant.now()));
        running.start();
        em.flush();
        start(owner).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NOT_READY"));
        assertThat(runs.count()).isEqualTo(1);
    }

    /** an-004 — 섹션 안 프레임까지 넘기고, 넘기지 않은 경로·입력에 없는 노드는 버리고 센다 */
    @Test
    @SuppressWarnings("unchecked")
    void an004_섹션_안_프레임을_넘기고_근거없는_짝은_버리고_센다() throws Exception {
        connectBoth();
        stubSources();
        when(mapper.mapFrames(anyList(), any())).thenReturn(List.of(
            new DesignCodeMapper.FrameMatch("F1", "src/A.tsx"), new DesignCodeMapper.FrameMatch("F2", "src/Missing.tsx")));
        when(mapper.mapNodes(any(), any())).thenReturn(List.of(
            new DesignCodeMapper.NodeMatch("N1", "src/A.tsx", "Card"), new DesignCodeMapper.NodeMatch("N9", "src/A.tsx", "Ghost")));
        start(owner).andExpect(status().isAccepted());
        Long runId = latestRunId();
        job.run(runId);

        ArgumentCaptor<List<DesignCodeMapper.FrameSummary>> frames = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<DesignCodeMapper.CodeBundle> code = ArgumentCaptor.forClass(DesignCodeMapper.CodeBundle.class);
        verify(mapper).mapFrames(frames.capture(), code.capture());
        assertThat(frames.getValue()).extracting(DesignCodeMapper.FrameSummary::id).containsExactly("F1", "F2");
        assertThat(frames.getValue().get(0).section()).isEqualTo("최종 섹션");
        assertThat(frames.getValue().get(0).texts()).containsExactly("안녕");
        assertThat(code.getValue().files()).containsOnlyKeys("src/A.tsx");   // README는 UI 코드가 아니라 넘기지 않는다
        assertThat(code.getValue().commitSha()).isEqualTo(SHA);

        ProjectRun run = reloadRun(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getCreatedCount()).isEqualTo(2);
        assertThat(run.getDroppedCount()).isEqualTo(2);
        assertThat(run.getCommitSha()).isEqualTo(SHA);
        List<DesignCodeMapping> saved = mappings.findOrdered(runId);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getFrameId()).isEqualTo("F1");
        assertThat(saved.get(0).getNodeId()).isNull();
        assertThat(saved.get(0).getCodePath()).isEqualTo("src/A.tsx");
        assertThat(saved.get(1).getNodeId()).isEqualTo("N1");
        assertThat(saved.get(1).getNodeName()).isEqualTo("카드");
        assertThat(saved.get(1).getCodeSymbol()).isEqualTo("Card");
    }

    /** an-005 — 프레임 이름순, 그 안 노드 이름순 */
    @Test
    void an005_매핑은_프레임_이름순_노드_이름순이다() throws Exception {
        ProjectRun run = runs.save(new ProjectRun(project, RunKind.ANALYSIS, owner, Instant.now()));
        run.succeed(SHA, 4, 0, Instant.now());
        Instant now = Instant.now();
        mappings.save(DesignCodeMapping.frame(project, run, "2:1", "B 화면", "b.tsx", now));
        mappings.save(DesignCodeMapping.node(project, run, "2:1", "B 화면", "2:3", "카드 2", "b.tsx", "Card2", now));
        mappings.save(DesignCodeMapping.node(project, run, "2:1", "B 화면", "2:2", "카드 1", "b.tsx", "Card1", now));
        mappings.save(DesignCodeMapping.frame(project, run, "1:1", "A 화면", "a.tsx", now));
        em.flush();
        getAs("/analysis/mappings", member).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.commit_sha").value(SHA))
            .andExpect(jsonPath("$.data.run.status").value("succeeded"))
            .andExpect(jsonPath("$.data.items[0].frame_name").value("A 화면"))
            .andExpect(jsonPath("$.data.items[0].code_path").value("a.tsx"))
            .andExpect(jsonPath("$.data.items[0].nodes", Matchers.hasSize(0)))
            .andExpect(jsonPath("$.data.items[1].frame_name").value("B 화면"))
            .andExpect(jsonPath("$.data.items[1].nodes[0].node_name").value("카드 1"))
            .andExpect(jsonPath("$.data.items[1].nodes[0].code_symbol").value("Card1"))
            .andExpect(jsonPath("$.data.items[1].nodes[1].node_name").value("카드 2"));
    }

    /** an-006 — 성공한 분석이 없으면 빈 목록, 비멤버는 403이고 data가 없다 */
    @Test
    void an006_성공한_분석이_없으면_빈_목록이고_비멤버는_403이다() throws Exception {
        getAs("/analysis/mappings", member).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.run").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items", Matchers.hasSize(0)));
        ProjectRun failed = runs.save(new ProjectRun(project, RunKind.ANALYSIS, owner, Instant.now()));
        failed.fail("실패", Instant.now());
        em.flush();
        getAs("/analysis/mappings", member).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.run").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items", Matchers.hasSize(0)));
        getAs("/analysis/mappings", outsider).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 소유자가_아니면_403이고_실행을_만들지_않는다() throws Exception {
        connectBoth();
        start(member).andExpect(status().isForbidden());
        assertThat(runs.count()).isZero();
    }

    @Test
    void 이미_성공했으면_409다() throws Exception {
        connectBoth();
        ProjectRun done = runs.save(new ProjectRun(project, RunKind.ANALYSIS, owner, Instant.now()));
        done.succeed(SHA, 0, 0, Instant.now());
        em.flush();
        start(owner).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NOT_READY"));
    }

    @Test
    void LLM이_실패하면_failed이고_매핑을_하나도_남기지_않으며_다시_시작할_수_있다() throws Exception {
        connectBoth();
        stubSources();
        when(mapper.mapFrames(anyList(), any())).thenReturn(List.of(new DesignCodeMapper.FrameMatch("F1", "src/A.tsx")));
        when(mapper.mapNodes(any(), any())).thenThrow(new MappingFailedException("AI 분석 호출에 실패했어요. 잠시 뒤 다시 시도해 주세요."));
        start(owner).andExpect(status().isAccepted());
        Long runId = latestRunId();
        job.run(runId);
        ProjectRun run = reloadRun(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("AI 분석");
        assertThat(mappings.findOrdered(runId)).isEmpty();
        getAs("/analysis", member).andExpect(jsonPath("$.data.status").value("failed"));
        start(owner).andExpect(status().isAccepted());
    }

    @Test
    void 프레임이_없으면_LLM을_부르지_않고_failed다() throws Exception {
        connectBoth();
        stubSources();
        when(figma.children("K", "S1")).thenReturn(Optional.of(List.of()));
        start(owner).andExpect(status().isAccepted());
        Long runId = latestRunId();
        job.run(runId);
        assertThat(reloadRun(runId).getStatus()).isEqualTo(RunStatus.FAILED);
        verify(mapper, never()).mapFrames(anyList(), any());
    }

    @Test
    void UI_코드가_한도를_넘으면_잘라_넣지_않고_failed다() throws Exception {
        connectBoth();
        stubSources();
        when(github.fileContent(anyString(), anyString(), anyString(), anyString())).thenReturn("x".repeat(400_001));
        start(owner).andExpect(status().isAccepted());
        Long runId = latestRunId();
        job.run(runId);
        ProjectRun run = reloadRun(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("너무 커요");
        verify(mapper, never()).mapFrames(anyList(), any());
    }

    @Test
    void Figma_요청_한도에_걸리면_기다렸다가_다시_불러_끝낸다() throws Exception {
        connectBoth();
        stubSources();
        when(figma.children("K", "1:1"))
            .thenThrow(new com.acjb.server.figma.FigmaApiException("Figma 요청 한도를 넘었습니다.", 429, false, 7, null))
            .thenReturn(Optional.of(List.of(new FigmaNode("S1", "최종 섹션", "SECTION"))));
        when(mapper.mapFrames(anyList(), any())).thenReturn(List.of(new DesignCodeMapper.FrameMatch("F1", "src/A.tsx")));
        when(mapper.mapNodes(any(), any())).thenReturn(List.of());
        List<Long> waited = new java.util.ArrayList<>();
        job.setSleeper(waited::add);
        try {
            start(owner).andExpect(status().isAccepted());
            Long runId = latestRunId();
            job.run(runId);
            assertThat(reloadRun(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(waited).containsExactly(7L);   // Retry-After만큼
        } finally {
            job.setSleeper(s -> { });
        }
    }

    @Test
    void 분석한_적이_없으면_상태_data는_null이다() throws Exception {
        getAs("/analysis", member).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(Matchers.nullValue()));
    }

    @Test
    void 매핑이_있는_프로젝트도_삭제된다() throws Exception {
        ProjectRun run = runs.save(new ProjectRun(project, RunKind.ANALYSIS, owner, Instant.now()));
        run.succeed(SHA, 1, 0, Instant.now());
        mappings.save(DesignCodeMapping.frame(project, run, "1:1", "A", "a.tsx", Instant.now()));
        em.flush();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/projects/" + project.getId())
                .with(oauth2Login().attributes(a -> a.put("email", owner.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t"))
            .andExpect(status().isNoContent());
        em.clear();
        assertThat(projects.findById(project.getId())).isEmpty();
        assertThat(mappings.count()).isZero();
    }

    /** 2026-11-10 — 코드 파일을 찾지 못한 화면(LLM이 비워 답했거나 근거 없는 경로를 단 프레임)은 매핑 조회에 "확인해 볼 곳"으로 담긴다. 이슈는 만들지 않는다 */
    @Test
    void 코드를_찾지_못한_화면은_매핑_조회의_unmapped_frames로_돌려준다() throws Exception {
        connectBoth();
        stubSources();
        when(figma.children("K", "S1")).thenReturn(Optional.of(List.of(new FigmaNode("F1", "홈", "FRAME"), new FigmaNode("F2", "설정", "FRAME"))));
        when(mapper.mapFrames(anyList(), any())).thenReturn(List.of(
            new DesignCodeMapper.FrameMatch("F1", "src/A.tsx"), new DesignCodeMapper.FrameMatch("F2", null)));
        when(mapper.mapNodes(any(), any())).thenReturn(List.of());
        long issuesBefore = count("QAIssue");
        start(owner).andExpect(status().isAccepted());
        job.run(latestRunId());

        getAs("/analysis/mappings", member).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", Matchers.hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].frame_id").value("F1"))
            .andExpect(jsonPath("$.data.unmapped_frames", Matchers.hasSize(1)))
            .andExpect(jsonPath("$.data.unmapped_frames[0].frame_id").value("F2"))
            .andExpect(jsonPath("$.data.unmapped_frames[0].frame_name").value("설정"))
            .andExpect(jsonPath("$.data.unmapped_frames[0].section").value("최종 섹션"));
        assertThat(count("QAIssue")).isEqualTo(issuesBefore);
    }

    @Test
    void 며칠짜리_Figma_한도면_기다리지_않고_바로_실패하고_본인_토큰을_안내한다() throws Exception {
        connectBoth();
        stubSources();
        // 우리 서버 계정이 속하지 않은 무료 플랜 파일 — Retry-After가 며칠로 온다(2026-11-11 실제 397192초)
        when(figma.children("K", "1:1"))
            .thenThrow(new com.acjb.server.figma.FigmaApiException("Figma 요청 한도를 넘었습니다.", 429, false, 397_192, null));
        List<Long> waited = new java.util.ArrayList<>();
        job.setSleeper(waited::add);
        try {
            start(owner).andExpect(status().isAccepted());
            Long runId = latestRunId();
            job.run(runId);
            ProjectRun run = reloadRun(runId);
            assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(run.getErrorMessage()).contains("약 5일").contains("본인 Figma 토큰");
            assertThat(waited).isEmpty();
        } finally {
            job.setSleeper(s -> { });
        }
    }

    @Test
    void 소유자_토큰을_넣은_프로젝트는_비교_분석의_Figma_호출을_그_토큰으로_한다() throws Exception {
        connectBoth();
        stubSources();
        project.changeFigmaToken(cipher.encrypt("figd_owner_token_value_1234"), "1234");
        projects.saveAndFlush(project);
        List<Boolean> usedOwnToken = new java.util.ArrayList<>();
        when(figma.children("K", "1:1")).thenAnswer(inv -> {
            usedOwnToken.add(com.acjb.server.figma.FigmaTokenScope.usingUserToken());
            return Optional.of(List.of(new FigmaNode("S1", "최종 섹션", "SECTION")));
        });
        when(mapper.mapFrames(anyList(), any())).thenReturn(List.of(new DesignCodeMapper.FrameMatch("F1", "src/A.tsx")));
        when(mapper.mapNodes(any(), any())).thenReturn(List.of());
        start(owner).andExpect(status().isAccepted());
        Long runId = latestRunId();
        job.run(runId);
        assertThat(reloadRun(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(usedOwnToken).containsExactly(true);
        assertThat(com.acjb.server.figma.FigmaTokenScope.usingUserToken()).isFalse(); // 작업이 끝나면 되돌린다
    }

    @Autowired com.acjb.server.figma.FigmaTokenCipher cipher;

    @Autowired com.acjb.server.repository.NotificationRepository notificationRepo;

    @Test
    void 비교_분석이_실패하면_실행한_소유자에게_run_failed_알림_1건() throws Exception {
        connectBoth();
        stubSources();
        when(github.fileContent(anyString(), anyString(), anyString(), anyString())).thenReturn("x".repeat(400_001));
        start(owner).andExpect(status().isAccepted());
        Long runId = latestRunId();
        job.run(runId);
        assertThat(reloadRun(runId).getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(notificationRepo.findAll()).filteredOn(n -> n.getProject().getId().equals(project.getId())).singleElement().satisfies(n -> {
            assertThat(n.getEvent()).isEqualTo(com.acjb.server.domain.NotificationEvent.RUN_FAILED);
            assertThat(n.getRecipient().getId()).isEqualTo(owner.getId());
            assertThat(n.getRun().getId()).isEqualTo(runId);
            assertThat(n.getIssue()).isNull();
        });
        getAs("/notifications", owner)
            .andExpect(jsonPath("$.data.items[0].event").value("run_failed"))
            .andExpect(jsonPath("$.data.items[0].run.kind").value("analysis"))
            .andExpect(jsonPath("$.data.items[0].run.error_message", Matchers.containsString("너무 커요")));
    }
}
