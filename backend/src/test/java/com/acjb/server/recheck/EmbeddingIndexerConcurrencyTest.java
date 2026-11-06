package com.acjb.server.recheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.PlatformScope;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.repository.EmbeddingRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.QAIssueRepository;

/**
 * 같은 프로젝트 보충이 동시에 떠도 Voyage에는 한 번만 보낸다(2026-11-06 실호출에서 중복 호출로 429). 트랜잭션 없이 돌고 끝에 지운다.
 */
@SpringBootTest
class EmbeddingIndexerConcurrencyTest {

    @Autowired EmbeddingIndexer indexer;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired QAIssueRepository issues;
    @Autowired EmbeddingRepository embeddings;
    @MockitoBean EmbeddingClient embeddingClient;

    Project project;
    Member owner;
    List<QAIssue> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        embeddings.deleteAll(embeddings.findByProjectIdAndModel(project.getId(), "test-model"));
        issues.deleteAll(created);
        projects.delete(project);
        members.delete(owner);
    }

    @Test
    void 동시에_보충해도_같은_문서를_한_번만_임베딩한다() throws Exception {
        owner = members.save(new Member("lock-" + System.nanoTime() + "@test.dev", "소유자"));
        project = projects.save(new Project("embedding-lock", owner));
        for (int i = 0; i < 3; i++) {
            created.add(issues.save(new QAIssue(project, "증상 " + i, "화면 " + i, List.of("진입"), PlatformScope.COMMON,
                null, List.of(), null, null, null, owner)));
        }
        CountDownLatch inCall = new CountDownLatch(1);
        when(embeddingClient.model()).thenReturn("test-model");
        when(embeddingClient.embed(anyList(), any())).thenAnswer(inv -> {
            inCall.countDown();
            Thread.sleep(300); // 첫 호출이 도는 동안 두 번째 보충이 들어오게 한다
            List<float[]> out = new ArrayList<>();
            for (Object ignored : (List<?>) inv.getArgument(0)) {
                out.add(new float[] {1f, 2f, 3f});
            }
            return out;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> indexer.backfill(project.getId()));
            inCall.await();
            Future<?> second = pool.submit(() -> indexer.backfill(project.getId()));
            first.get();
            second.get();
        } finally {
            pool.shutdown();
        }

        verify(embeddingClient, times(1)).embed(anyList(), any());
        assertThat(embeddings.findByProjectIdAndModel(project.getId(), "test-model")).hasSize(3);
    }
}
