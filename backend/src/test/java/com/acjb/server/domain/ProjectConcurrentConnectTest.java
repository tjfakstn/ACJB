package com.acjb.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectRepository;

/**
 * Figma·GitHub 연결이 동시에 저장돼도 서로 덮어쓰지 않는다(2026-11-11 버그 — 두 요청 모두 200인데 GitHub 연결이 사라졌다).
 * 외부 확인을 기다리는 동안 먼저 읽어 둔 프로젝트로 Figma를 저장하는 상황을 두 트랜잭션으로 그대로 만든다
 */
@SpringBootTest
class ProjectConcurrentConnectTest {

    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired TransactionTemplate tx;

    Long projectId;
    Long ownerId;

    @AfterEach
    void cleanUp() {
        if (projectId != null) {
            projects.deleteById(projectId);
        }
        if (ownerId != null) {
            members.deleteById(ownerId);
        }
    }

    @Test
    void 먼저_읽은_프로젝트로_Figma를_저장해도_그사이_저장된_GitHub_연결이_남는다() throws Exception {
        Member owner = members.save(new Member("race-owner@test.dev", "소유자"));
        ownerId = owner.getId();
        projectId = projects.save(new Project("동시 연결", owner)).getId();

        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch githubSaved = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Figma 요청: 프로젝트를 읽고 → (외부 확인을 기다리는 동안) → 저장
            Future<?> figma = pool.submit(() -> tx.executeWithoutResult(s -> {
                Project p = projects.findById(projectId).orElseThrow();
                loaded.countDown();
                await(githubSaved);
                p.connectFigma("https://www.figma.com/design/K/x?node-id=1-2", "K", "파일", "1:2", "페이지", FigmaScope.PAGE, Instant.now());
            }));
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            // 그사이 GitHub 요청이 저장을 끝낸다
            tx.executeWithoutResult(s -> projects.findById(projectId).orElseThrow()
                .connectGithub("https://github.com/team/app", "team/app", "main", "main", Instant.now()));
            githubSaved.countDown();
            figma.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Project saved = projects.findById(projectId).orElseThrow();
        assertThat(saved.getFigmaFileKey()).isEqualTo("K");
        assertThat(saved.getGithubRepo()).isEqualTo("team/app");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
