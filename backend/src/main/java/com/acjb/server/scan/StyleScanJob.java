package com.acjb.server.scan;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.acjb.server.recheck.EmbeddingSourcesChanged;
import com.acjb.server.domain.FigmaFrame;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.StyleScan;
import com.acjb.server.figma.FigmaApiException;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.repository.FigmaFrameRepository;
import com.acjb.server.repository.StyleMismatchRepository;
import com.acjb.server.repository.StyleScanRepository;

import tools.jackson.databind.JsonNode;

/**
 * POST /style-mismatches/scan의 실제 작업(단일 컴포넌트 scan, SPEC 4절). 요청 트랜잭션이 커밋된 뒤 별도 스레드에서 돈다.
 * Figma 노드 값과 배포 화면 요소의 computed style을 color·font_weight·radius만 비교해, 다른 property만 StyleMismatch로 저장한다.
 * 양쪽 다 추출된 property만 비교한다(N3). 브라우저가 도는 동안 DB 연결을 잡지 않도록 시작·저장만 짧은 트랜잭션으로 나눈다.
 */
@Component
public class StyleScanJob {

    private static final Logger log = LoggerFactory.getLogger(StyleScanJob.class);
    private static final int ERROR_MAX = 500;

    private final FigmaClient figma;
    private final ComputedStyleReader reader;
    private final StyleScanRepository scans;
    private final StyleMismatchRepository mismatches;
    private final FigmaFrameRepository frames;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public StyleScanJob(FigmaClient figma, ComputedStyleReader reader, StyleScanRepository scans,
                        StyleMismatchRepository mismatches, FigmaFrameRepository frames, PlatformTransactionManager txManager,
                        ApplicationEventPublisher events) {
        this.events = events;
        this.figma = figma;
        this.reader = reader;
        this.scans = scans;
        this.mismatches = mismatches;
        this.frames = frames;
        this.tx = new TransactionTemplate(txManager);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(StyleScanRequested event) {
        run(event.scanId());
    }

    /** 대상 scan의 입력값 — 트랜잭션 밖에서 쓰려고 엔티티 대신 값만 꺼낸다 */
    private record Input(Long projectId, String fileKey, String frameId, String nodeId, String targetUrl, String selector) {
    }

    /** 테스트에서도 직접 부른다. 결과는 scan 행의 status(done/failed)로 남는다 */
    public void run(Long scanId) {
        Input input;
        try {
            input = tx.execute(s -> start(scanId));
        } catch (ScanFailedException e) {
            fail(scanId, e.getMessage());
            return;
        }
        if (input == null) {
            return;
        }
        try {
            List<StyleComparator.Result> results = compare(input);
            tx.executeWithoutResult(s -> save(scanId, results));
        } catch (ScanFailedException | FigmaApiException e) {
            log.warn("scan 실패 scan_id={}: {}", scanId, e.getMessage());
            fail(scanId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("scan 중 예상치 못한 오류 scan_id={}", scanId, e);
            fail(scanId, "scan 중 오류가 발생했습니다.");
        }
    }

    private Input start(Long scanId) {
        StyleScan scan = scans.findById(scanId).orElse(null);
        if (scan == null) {
            return null;
        }
        scan.start();
        Long projectId = scan.getProject().getId();
        FigmaFrame frame = frames.findByProjectIdAndFrameId(projectId, scan.getFrameId())
            .orElseThrow(() -> new ScanFailedException("frame_id가 이 프로젝트에 동기화돼 있지 않습니다."));
        return new Input(projectId, frame.getFigmaFileKey(), scan.getFrameId(), scan.getNodeId(), scan.getTargetUrl(), scan.getSelector());
    }

    private List<StyleComparator.Result> compare(Input in) {
        JsonNode frameDoc = figma.nodeDocument(in.fileKey(), in.frameId())
            .orElseThrow(() -> new ScanFailedException("Figma 파일에서 frame_id를 찾을 수 없습니다(다시 동기화 필요)."));
        JsonNode node = FigmaStyleExtractor.find(frameDoc, in.nodeId())
            .orElseThrow(() -> new ScanFailedException("node_id가 frame_id 안에 없습니다."));
        StyleValues expected = FigmaStyleExtractor.extract(node);
        StyleValues actual = reader.read(in.targetUrl(), in.selector()).forFigmaNode(FigmaStyleExtractor.isText(node));
        List<StyleComparator.Result> results = StyleComparator.compare(expected, actual);
        if (results.isEmpty()) {
            // 모두 일치한 것과 구분되도록 done(0)이 아니라 failed로 남긴다
            throw new ScanFailedException("비교할 수 있는 값이 없습니다 — color·font_weight·radius 중 Figma와 화면 양쪽에서 모두 추출된 값이 없습니다.");
        }
        return results;
    }

    private void save(Long scanId, List<StyleComparator.Result> results) {
        StyleScan scan = scans.findById(scanId).orElseThrow();
        int count = 0;
        for (StyleComparator.Result r : results) {
            if (!r.matches()) {
                mismatches.save(StyleMismatch.fromScan(scan, r.property(), r.expected(), r.actual()));
                count++;
            }
        }
        scan.complete(count);
        if (count > 0) {
            // 스타일 차이는 재검증 범위 제안(AC20)의 검색 문서가 된다 — 이 트랜잭션 커밋 뒤 임베딩을 만든다
            events.publishEvent(new EmbeddingSourcesChanged(scan.getProject().getId()));
        }
    }

    private void fail(Long scanId, String message) {
        String m = message == null || message.isBlank() ? "scan 중 오류가 발생했습니다." : message;
        tx.executeWithoutResult(s -> scans.findById(scanId).ifPresent(scan -> scan.fail(m.length() <= ERROR_MAX ? m : m.substring(0, ERROR_MAX))));
    }
}
