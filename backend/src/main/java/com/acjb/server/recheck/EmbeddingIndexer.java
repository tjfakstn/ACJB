package com.acjb.server.recheck;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.acjb.server.domain.Embedding;
import com.acjb.server.domain.EmbeddingSourceType;
import com.acjb.server.domain.Project;
import com.acjb.server.recheck.EmbeddingClient.InputType;
import com.acjb.server.repository.EmbeddingRepository;
import com.acjb.server.repository.ProjectRepository;

/**
 * 프로젝트의 임베딩 누락분을 채운다(설계 4절). 원본이 저장되면 커밋 뒤 비동기로 한 번 돌고, 실패하거나 키가 없던 기간의
 * 누락분은 제안 요청 때 같은 메서드로 다시 채운다 — 그래서 이슈 등록 등 본 요청은 임베딩 실패에 영향을 받지 않는다.
 * 외부 호출 동안 DB 트랜잭션을 잡지 않는다: 읽기 → 호출 → 저장을 나눈다.
 */
@Component
public class EmbeddingIndexer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexer.class);

    private final EmbeddingClient client;
    private final EmbeddingRepository embeddings;
    private final ProjectRepository projects;
    private final TransactionTemplate tx;
    /**
     * 같은 프로젝트의 보충은 한 번에 하나만 돈다. 이슈를 연달아 등록하면 보충이 동시에 여러 개 떠서 같은 문서를 중복으로
     * Voyage에 보내고, 무료 등급의 분당 호출 한도(429)에 바로 걸렸다(2026-11-06 실호출 확인). 뒤에 온 보충은 앞의 것이 끝난 뒤
     * 남은 누락분만 보낸다. 서버 한 대 기준의 잠금이다 — 여러 대가 되면 uk_embeddings_source가 중복 저장을 막는다.
     */
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public EmbeddingIndexer(EmbeddingClient client, EmbeddingRepository embeddings, ProjectRepository projects,
                            PlatformTransactionManager txManager) {
        this.client = client;
        this.embeddings = embeddings;
        this.projects = projects;
        this.tx = new TransactionTemplate(txManager);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSourcesChanged(EmbeddingSourcesChanged event) {
        if (!client.isConfigured()) {
            return;
        }
        try {
            backfill(event.projectId());
        } catch (RecheckFailedException e) {
            // 제안 요청 때 다시 채우므로 여기서는 남기기만 한다
            log.warn("임베딩 생성 실패 project_id={}: {}", event.projectId(), e.getMessage());
        }
    }

    private record Pending(EmbeddingSourceType type, Long sourceId, String text) {
    }

    /** 누락분을 모두 채운다. 외부 호출이 실패하면 RecheckFailedException(이미 저장된 행은 그대로 둔다) */
    public void backfill(Long projectId) {
        ReentrantLock lock = locks.computeIfAbsent(projectId, id -> new ReentrantLock());
        lock.lock();
        try {
            fill(projectId);
        } finally {
            lock.unlock();
        }
    }

    private void fill(Long projectId) {
        String model = client.model();
        List<Pending> pending = tx.execute(s -> findPending(projectId, model));
        if (pending == null || pending.isEmpty()) {
            return;
        }
        List<float[]> vectors = client.embed(pending.stream().map(Pending::text).toList(), InputType.DOCUMENT);
        for (int i = 0; i < pending.size(); i++) {
            Pending p = pending.get(i);
            float[] vector = vectors.get(i);
            try {
                tx.executeWithoutResult(s -> {
                    Project project = projects.getReferenceById(projectId);
                    embeddings.save(new Embedding(project, p.type(), p.sourceId(), model, vector));
                });
            } catch (DataIntegrityViolationException e) {
                // 동시에 돈 다른 보충이 먼저 저장했다(uk_embeddings_source) — 같은 원본이라 그대로 둔다
            }
        }
    }

    private List<Pending> findPending(Long projectId, String model) {
        List<Pending> out = new ArrayList<>();
        embeddings.issuesWithoutEmbedding(projectId, model)
            .forEach(i -> out.add(new Pending(EmbeddingSourceType.QA_ISSUE, i.getId(), EmbeddingTexts.issue(i))));
        embeddings.mismatchesWithoutEmbedding(projectId, model)
            .forEach(m -> out.add(new Pending(EmbeddingSourceType.STYLE_MISMATCH, m.getId(), EmbeddingTexts.mismatch(m))));
        embeddings.failedVerificationsWithoutEmbedding(projectId, model)
            .forEach(v -> out.add(new Pending(EmbeddingSourceType.VERIFICATION, v.getId(), EmbeddingTexts.failedVerification(v))));
        return out;
    }
}
