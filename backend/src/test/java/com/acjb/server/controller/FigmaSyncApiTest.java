package com.acjb.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.acjb.server.domain.FigmaSync;
import com.acjb.server.domain.JobStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.figma.FigmaApiException;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.figma.FigmaNode;
import com.acjb.server.figma.FigmaSyncJob;
import com.acjb.server.repository.FigmaFrameRepository;
import com.acjb.server.repository.FigmaSyncRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * POST /figma/sync, GET /figma/sync/{syncId}, GET /figma/frames. Figma는 가짜(FigmaClient mock).
 * 이 클래스는 트랜잭션 안에서 돌아 커밋 뒤 작업이 실행되지 않으므로, 작업 로직은 FigmaSyncJob.run을 직접 불러 검증한다.
 * 커밋 뒤 비동기 실행 자체는 FigmaSyncAsyncTest가 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FigmaSyncApiTest {

    private static final String CSRF = "test-csrf-token";
    private static final String KEY = "GBZhzBWLOVirx9MMd5zddI";

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired FigmaSyncRepository syncs;
    @Autowired FigmaFrameRepository frames;
    @Autowired FigmaSyncJob job;
    @MockitoBean FigmaClient figma;

    Project project;
    Project otherProject;
    Member me;

    @BeforeEach
    void setUp() {
        project = projects.save(new Project("figma-api", owner()));
        otherProject = projects.save(new Project("figma-api-other", owner()));
        me = members.save(new Member("figma@test.dev", "멤버"));
        projectMembers.save(new ProjectMember(project, me, MemberRole.DESIGNER));
        projectMembers.save(new ProjectMember(otherProject, me, MemberRole.DESIGNER));
        when(figma.isConfigured()).thenReturn(true);
    }

    private ResultActions requestSync(String body) throws Exception {
        return mvc.perform(post("/api/v1/projects/" + project.getId() + "/figma/sync")
            .with(oauth2Login().attributes(a -> a.put("email", me.getEmail())))
            .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getAs(String path) throws Exception {
        return mvc.perform(get("/api/v1/projects/" + path).with(oauth2Login().attributes(a -> a.put("email", me.getEmail()))));
    }

    private FigmaSync queued(List<String> frameIds) {
        return syncs.saveAndFlush(new FigmaSync(project, KEY, frameIds, me));
    }

    private FigmaSync reload(FigmaSync s) {
        em.flush();
        em.clear();
        return syncs.findById(s.getId()).orElseThrow();
    }

    // --- 요청 ---

    @Test
    void 요청하면_202와_queued_sync_id를_돌려주고_Figma는_아직_부르지_않는다() throws Exception {
        String res = requestSync("{\"figma_file_key\":\"" + KEY + "\",\"frame_ids\":[\"84:759\",\"84:759\"]}")
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("queued"))
            .andExpect(jsonPath("$.data.sync_id").exists())
            .andReturn().getResponse().getContentAsString();
        assertThat(res).contains("sync_id");
        FigmaSync saved = syncs.findAll().getFirst();
        assertThat(saved.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(saved.getFrameIds()).containsExactly("84:759");   // 중복 id는 한 번만
        assertThat(saved.getRequestedBy().getId()).isEqualTo(me.getId());
        verify(figma, never()).nodes(anyString(), any());
    }

    @Test
    void 파일_키가_없거나_형식이_틀리면_400() throws Exception {
        requestSync("{}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message", containsString("figma_file_key")));
        requestSync("{\"figma_file_key\":\"../etc\"}").andExpect(status().isBadRequest());
        assertThat(syncs.count()).isZero();
    }

    @Test
    void frame_ids가_빈_배열이거나_형식이_틀리면_400() throws Exception {
        requestSync("{\"figma_file_key\":\"" + KEY + "\",\"frame_ids\":[]}").andExpect(status().isBadRequest());
        requestSync("{\"figma_file_key\":\"" + KEY + "\",\"frame_ids\":[\"84:759\",\"a b\"]}").andExpect(status().isBadRequest());
        assertThat(syncs.count()).isZero();
    }

    @Test
    void Figma_토큰이_설정되지_않았으면_503이고_작업을_만들지_않는다() throws Exception {
        when(figma.isConfigured()).thenReturn(false);
        requestSync("{\"figma_file_key\":\"" + KEY + "\"}")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("FIGMA_UNAVAILABLE"));
        assertThat(syncs.count()).isZero();
    }

    // --- 작업 ---

    @Test
    void 전체_동기화는_페이지_바로_아래_FRAME만_저장하고_done이_된다() throws Exception {
        when(figma.topLevelNodes(KEY)).thenReturn(List.of(
            new FigmaNode("1:2", "홈", "FRAME"), new FigmaNode("1:3", "메모", "TEXT"), new FigmaNode("1:4", "로그인", "FRAME")));
        FigmaSync sync = queued(null);

        job.run(sync.getId());

        FigmaSync done = reload(sync);
        assertThat(done.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(done.getFrameCount()).isEqualTo(2);
        assertThat(done.getFinishedAt()).isNotNull();
        getAs(project.getId() + "/figma/frames")
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].name").value("로그인"))     // name asc
            .andExpect(jsonPath("$.data.items[0].frame_id").value("1:4"))
            .andExpect(jsonPath("$.data.items[0].frame_url").value("https://www.figma.com/design/" + KEY + "/?node-id=1-4"))
            .andExpect(jsonPath("$.data.items[0].last_synced_at").exists())
            .andExpect(jsonPath("$.data.items[1].name").value("홈"));
        getAs(project.getId() + "/figma/sync/" + sync.getId())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("done"))
            .andExpect(jsonPath("$.data.frame_count").value(2))
            .andExpect(jsonPath("$.data.finished_at").exists());
    }

    @Test
    void frame_ids_중_하나라도_없거나_프레임이_아니면_failed이고_아무_프레임도_저장하지_않는다() {
        Map<String, Optional<FigmaNode>> found = new LinkedHashMap<>();
        found.put("84:759", Optional.of(new FigmaNode("84:759", "시작하기 버튼", "FRAME")));
        found.put("84:800", Optional.empty());
        found.put("84:900", Optional.of(new FigmaNode("84:900", "라벨", "TEXT")));
        when(figma.nodes(eq(KEY), any())).thenReturn(found);
        FigmaSync sync = queued(List.of("84:759", "84:800", "84:900"));

        job.run(sync.getId());

        FigmaSync failed = reload(sync);
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("84:800(없음)").contains("84:900(TEXT, 프레임 아님)");
        assertThat(frames.count()).isZero();
    }

    @Test
    void 다시_동기화하면_같은_프레임을_갱신하고_중복으로_만들지_않는다() {
        frames.save(new FigmaFrame(project, "84:759", KEY, "옛 이름", "old-url"));
        when(figma.nodes(eq(KEY), any())).thenReturn(Map.of("84:759", Optional.of(new FigmaNode("84:759", "새 이름", "COMPONENT"))));
        FigmaSync sync = queued(List.of("84:759"));

        job.run(sync.getId());

        assertThat(reload(sync).getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(frames.findByProjectIdOrderByNameAscIdAsc(project.getId())).singleElement()
            .satisfies(f -> assertThat(f.getName()).isEqualTo("새 이름"));
    }

    @Test
    void Figma_호출이_실패하면_failed로_끝나고_이유를_남긴다() throws Exception {
        when(figma.topLevelNodes(KEY)).thenThrow(new FigmaApiException("Figma 접근이 거부됐습니다(토큰 권한 또는 파일 공유 설정 확인)."));
        FigmaSync sync = queued(null);

        job.run(sync.getId());

        FigmaSync failed = reload(sync);
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("접근이 거부");
        assertThat(failed.getFinishedAt()).isNotNull();
        getAs(project.getId() + "/figma/sync/" + sync.getId()).andExpect(jsonPath("$.data.status").value("failed"));
    }

    // --- 조회 ---

    @Test
    void 다른_프로젝트의_작업과_프레임은_보이지_않는다() throws Exception {
        FigmaSync foreign = syncs.save(new FigmaSync(otherProject, KEY, null, me));
        frames.save(new FigmaFrame(otherProject, "9:9", KEY, "남의 화면", "url"));

        getAs(project.getId() + "/figma/sync/" + foreign.getId())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data").doesNotExist());
        getAs(project.getId() + "/figma/sync/abc").andExpect(status().isNotFound());
        getAs(project.getId() + "/figma/frames").andExpect(jsonPath("$.data.items.length()").value(0));
    }

    /** 프로젝트는 소유자가 있어야 만들 수 있다(AC16·N15) — 테스트마다 소유자용 멤버를 따로 만든다 */
    private Member owner() {
        return members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
    }
}
