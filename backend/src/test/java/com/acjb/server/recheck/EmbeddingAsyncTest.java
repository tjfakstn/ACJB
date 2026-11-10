package com.acjb.server.recheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.acjb.server.domain.EmbeddingSourceType;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.repository.EmbeddingRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StatusHistoryRepository;

import jakarta.servlet.http.Cookie;

/** 이슈 등록이 커밋되면 별도 스레드에서 그 이슈의 임베딩이 1건 생긴다(설계 4절). 트랜잭션 없이 돌고 만든 행은 끝에 지운다 */
@SpringBootTest
@AutoConfigureMockMvc
class EmbeddingAsyncTest {

    @Autowired MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired QAIssueRepository issues;
    @Autowired StatusHistoryRepository histories;
    @Autowired EmbeddingRepository embeddings;
    @MockitoBean EmbeddingClient embeddingClient;

    Project project;
    Member owner;
    ProjectMember membership;

    @AfterEach
    void cleanUp() {
        embeddings.deleteAll(embeddings.findByProjectIdAndModel(project.getId(), "test-model"));
        issues.findAll().stream().filter(i -> i.getProject().getId().equals(project.getId())).forEach(i -> {
            histories.deleteAll(histories.findByIssueIdOrderByChangedAtAscIdAsc(i.getId()));
            issues.delete(i);
        });
        projectMembers.delete(membership);
        projects.delete(project);
        members.delete(owner);
    }

    @Test
    void 이슈_등록이_커밋되면_임베딩이_비동기로_1건_생긴다() throws Exception {
        when(embeddingClient.isConfigured()).thenReturn(true);
        when(embeddingClient.model()).thenReturn("test-model");
        when(embeddingClient.embed(anyList(), any())).thenAnswer(inv -> {
            List<float[]> out = new ArrayList<>();
            for (Object ignored : (List<?>) inv.getArgument(0)) {
                out.add(new float[] {1f, 2f, 3f});
            }
            return out;
        });
        owner = members.save(new Member("emb-owner-" + System.nanoTime() + "@test.dev", "소유자"));
        project = projects.save(new Project("embedding-async", owner));
        membership = projectMembers.save(new ProjectMember(project, owner, MemberRole.PLANNER));

        mvc.perform(post("/api/v1/projects/" + project.getId() + "/qa-issues")
                .with(oauth2Login().attributes(a -> a.put("email", owner.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"description":"결제 버튼 색이 달라요","screen_location":"결제 화면 버튼",
                     "repro_steps":["결제 화면 진입","버튼 확인"],"platform_scope":"common"}
                    """))
            .andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(embeddings.findByProjectIdAndModel(project.getId(), "test-model")).hasSize(1));
        assertThat(embeddings.findByProjectIdAndModel(project.getId(), "test-model").get(0).getSourceType())
            .isEqualTo(EmbeddingSourceType.QA_ISSUE);
    }
}
