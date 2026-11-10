package com.acjb.server.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.Embedding;
import com.acjb.server.domain.EmbeddingSourceType;
import com.acjb.server.domain.IssueStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.QAIssue;
import com.acjb.server.dto.RecheckSuggestionResponse;
import com.acjb.server.dto.RecheckSuggestionResponse.Evidence;
import com.acjb.server.dto.RecheckSuggestionResponse.Suggestion;
import com.acjb.server.recheck.EmbeddingClient;
import com.acjb.server.recheck.EmbeddingClient.InputType;
import com.acjb.server.recheck.EmbeddingIndexer;
import com.acjb.server.recheck.EmbeddingTexts;
import com.acjb.server.recheck.RecheckFailedException;
import com.acjb.server.recheck.RecheckPrompt;
import com.acjb.server.recheck.RecheckPrompt.Document;
import com.acjb.server.recheck.RecheckSuggester;
import com.acjb.server.recheck.RecheckSuggestionSchema;
import com.acjb.server.repository.EmbeddingRepository;
import com.acjb.server.repository.QAIssueRepository;
import com.acjb.server.repository.StyleMismatchRepository;
import com.acjb.server.repository.VerificationRepository;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 재검증 범위 제안(SPEC AC20, 설계 docs/design-recheck-suggestions.md).
 * 순서: 이슈(404) → 권한(403, AC13과 같은 사람) → 상태 fixed(409) → 임베딩 누락분 보충 → 검색 → LLM → 스키마 검증 → 근거 id 검사.
 * 거부될 때는 외부 호출을 하지 않는다. 결과는 저장하지 않고, 이슈·스타일 차이·재검증·알림을 만들거나 바꾸지 않는다(N16).
 * 외부 호출 동안 DB 트랜잭션을 잡지 않는다.
 */
@Service
public class RecheckSuggestionService {

    /** LLM에 넘기는 검색 문서 수(설계 5절) */
    static final int TOP_K = 8;

    private final QAIssueRepository issues;
    private final StyleMismatchRepository mismatches;
    private final VerificationRepository verifications;
    private final EmbeddingRepository embeddings;
    private final VerifierPolicy verifierPolicy;
    private final EmbeddingIndexer indexer;
    private final EmbeddingClient embeddingClient;
    private final RecheckPrompt prompt;
    private final RecheckSuggester suggester;
    private final RecheckSuggestionSchema schema;
    private final TransactionTemplate tx;

    public RecheckSuggestionService(QAIssueRepository issues, StyleMismatchRepository mismatches,
                                    VerificationRepository verifications, EmbeddingRepository embeddings,
                                    VerifierPolicy verifierPolicy, EmbeddingIndexer indexer, EmbeddingClient embeddingClient,
                                    RecheckPrompt prompt, RecheckSuggester suggester, RecheckSuggestionSchema schema,
                                    PlatformTransactionManager txManager) {
        this.issues = issues;
        this.mismatches = mismatches;
        this.verifications = verifications;
        this.embeddings = embeddings;
        this.verifierPolicy = verifierPolicy;
        this.indexer = indexer;
        this.embeddingClient = embeddingClient;
        this.prompt = prompt;
        this.suggester = suggester;
        this.schema = schema;
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setReadOnly(true);
        this.tx = template;
    }

    /** 검사를 통과한 대상 이슈의 값 — 트랜잭션 밖에서 쓰려고 엔티티 대신 값만 꺼낸다 */
    private record Target(Long id, String screenLocation, String text) {
    }

    private record Scored(Embedding embedding, double score) {
    }

    public RecheckSuggestionResponse suggest(Long projectId, String issueId, Member actor) {
        Target target = tx.execute(s -> check(projectId, issueId, actor));
        try {
            return run(projectId, target);
        } catch (RecheckFailedException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.SUGGESTION_FAILED,
                "같이 볼 곳을 제안하지 못했습니다. 잠시 뒤 다시 시도해 주세요. (" + e.getMessage() + ")");
        }
    }

    private Target check(Long projectId, String issueId, Member actor) {
        Long id;
        try {
            id = issueId == null ? null : Long.valueOf(issueId.trim());
        } catch (NumberFormatException e) {
            id = null;
        }
        QAIssue issue = id == null ? null : issues.findByIdAndProjectId(id, projectId).orElse(null);
        if (issue == null) {
            throw ApiException.notFound("이슈를 찾을 수 없습니다.");
        }
        if (!verifierPolicy.canVerify(issue, actor)) {
            throw ApiException.forbidden("재검증 범위 제안은 이 이슈를 재검증할 수 있는 사람만 요청할 수 있습니다.");
        }
        if (issue.getStatus() != IssueStatus.FIXED) {
            throw ApiException.conflict("fixed 상태인 이슈만 재검증 범위를 제안받을 수 있습니다. 현재 상태: "
                + issue.getStatus().name().toLowerCase(Locale.ROOT));
        }
        return new Target(issue.getId(), issue.getScreenLocation(), EmbeddingTexts.issue(issue));
    }

    private RecheckSuggestionResponse run(Long projectId, Target target) {
        indexer.backfill(projectId);
        String model = embeddingClient.model();
        // 같은 프로젝트·같은 모델만(N16). 대상 이슈 자신은 빼고, 대상 이슈의 fail 재검증 기록은 둔다
        List<Embedding> candidates = tx.execute(s -> embeddings.findByProjectIdAndModel(projectId, model)).stream()
            .filter(e -> !(e.getSourceType() == EmbeddingSourceType.QA_ISSUE && e.getSourceId().equals(target.id())))
            .toList();
        if (candidates.isEmpty()) {
            return new RecheckSuggestionResponse(String.valueOf(target.id()), List.of(), 0, 0);
        }
        float[] query = embeddingClient.embed(List.of(target.text()), InputType.QUERY).get(0);
        List<Embedding> top = candidates.stream()
            .map(e -> new Scored(e, cosine(query, e.vectorValues())))
            // 점수가 같으면 id 순 — 같은 입력에 순서가 흔들리지 않게
            .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.embedding().getId()))
            .limit(TOP_K)
            .map(Scored::embedding)
            .toList();
        List<Document> documents = tx.execute(s -> documents(top));
        if (documents == null || documents.isEmpty()) {
            return new RecheckSuggestionResponse(String.valueOf(target.id()), List.of(), 0, 0);
        }
        JsonNode output = schema.validate(suggester.suggest(prompt.render(target.text(), documents)));
        return filter(target, documents, output);
    }

    /** 검색된 임베딩의 원본 텍스트. 원본을 못 찾은 문서는 넘기지 않는다 */
    private List<Document> documents(List<Embedding> top) {
        List<Document> out = new ArrayList<>();
        for (Embedding e : top) {
            String text = switch (e.getSourceType()) {
                case QA_ISSUE -> issues.findById(e.getSourceId()).map(EmbeddingTexts::issue).orElse(null);
                case STYLE_MISMATCH -> mismatches.findById(e.getSourceId()).map(EmbeddingTexts::mismatch).orElse(null);
                case VERIFICATION -> verifications.findById(e.getSourceId()).map(EmbeddingTexts::failedVerification).orElse(null);
            };
            if (text != null) {
                out.add(new Document(e.getSourceType().apiName() + ":" + e.getSourceId(), text));
            }
        }
        return out;
    }

    /**
     * 근거 id 검사(N16, 설계 7절): evidence_ids가 비었거나 하나라도 이번 검색 결과에 없던 id면 그 제안 전체를 버린다.
     * 대상 이슈와 같은 화면, 앞에서 이미 낸 화면도 버린다.
     */
    private RecheckSuggestionResponse filter(Target target, List<Document> documents, JsonNode output) {
        Map<String, Evidence> searched = new HashMap<>();
        for (Document d : documents) {
            int colon = d.id().indexOf(':');
            searched.put(d.id(), new Evidence(d.id().substring(0, colon), d.id().substring(colon + 1)));
        }
        List<Suggestion> kept = new ArrayList<>();
        Set<String> screens = new HashSet<>();
        int dropped = 0;
        for (JsonNode item : output.get("suggestions")) {
            String screen = item.get("screen").asText().strip();
            List<Evidence> evidence = new ArrayList<>();
            boolean valid = item.get("evidence_ids").size() > 0;
            for (JsonNode idNode : item.get("evidence_ids")) {
                Evidence ev = searched.get(idNode.asText().strip());
                if (ev == null) {
                    valid = false;
                    break;
                }
                if (!evidence.contains(ev)) {
                    evidence.add(ev);
                }
            }
            if (!valid || screen.equals(target.screenLocation().strip()) || !screens.add(screen)) {
                dropped++;
                continue;
            }
            kept.add(new Suggestion(screen, item.get("reason").asText().strip(), List.copyOf(evidence)));
        }
        return new RecheckSuggestionResponse(String.valueOf(target.id()), List.copyOf(kept), documents.size(), dropped);
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new RecheckFailedException("임베딩 차원이 맞지 않습니다(모델 설정을 확인하세요).");
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
