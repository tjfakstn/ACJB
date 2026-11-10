package com.acjb.server.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acjb.server.domain.Embedding;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.Verification;

public interface EmbeddingRepository extends JpaRepository<Embedding, Long> {

    /** 검색은 같은 프로젝트·같은 모델 행만 읽는다 — 다른 프로젝트 문서를 쓰지 않는 근거(N16) */
    List<Embedding> findByProjectIdAndModel(Long projectId, String model);

    // --- 아직 임베딩이 없는 원본(누락분 보충용). 정렬은 id 순으로 고정 ---

    @Query("select i from QAIssue i where i.project.id = :projectId and not exists ("
        + "select e.id from Embedding e where e.sourceType = com.acjb.server.domain.EmbeddingSourceType.QA_ISSUE"
        + " and e.sourceId = i.id and e.model = :model) order by i.id")
    List<QAIssue> issuesWithoutEmbedding(@Param("projectId") Long projectId, @Param("model") String model);

    @Query("select s from StyleMismatch s where s.project.id = :projectId and not exists ("
        + "select e.id from Embedding e where e.sourceType = com.acjb.server.domain.EmbeddingSourceType.STYLE_MISMATCH"
        + " and e.sourceId = s.id and e.model = :model) order by s.id")
    List<StyleMismatch> mismatchesWithoutEmbedding(@Param("projectId") Long projectId, @Param("model") String model);

    /** pass 재검증은 회귀 근거가 되지 않아 fail만 대상이다 */
    @Query("select v from Verification v where v.issue.project.id = :projectId"
        + " and v.result = com.acjb.server.domain.VerificationResult.FAIL and not exists ("
        + "select e.id from Embedding e where e.sourceType = com.acjb.server.domain.EmbeddingSourceType.VERIFICATION"
        + " and e.sourceId = v.id and e.model = :model) order by v.id")
    List<Verification> failedVerificationsWithoutEmbedding(@Param("projectId") Long projectId, @Param("model") String model);
}
