package com.acjb.server.figma;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.acjb.server.domain.FigmaFrame;
import com.acjb.server.domain.FigmaSync;
import com.acjb.server.repository.FigmaFrameRepository;
import com.acjb.server.repository.FigmaSyncRepository;

/**
 * POST /figma/sync의 실제 작업. 요청 트랜잭션이 커밋된 뒤 별도 스레드에서 돈다(그래서 202를 먼저 돌려준다).
 * 받은 프레임을 전부 확인한 뒤에만 저장한다 — 하나라도 문제가 있으면 아무 프레임도 바꾸지 않고 failed로 끝낸다.
 */
@Component
public class FigmaSyncJob {

    private static final Logger log = LoggerFactory.getLogger(FigmaSyncJob.class);
    /** 화면 단위로 보는 노드 종류. 전체 동기화는 이 중 FRAME만, frame_ids 지정은 셋 다 받는다 */
    static final Set<String> FRAME_TYPES = Set.of("FRAME", "COMPONENT", "COMPONENT_SET");
    private static final int ERROR_MAX = 500;

    private final FigmaClient figma;
    private final FigmaSyncRepository syncs;
    private final FigmaFrameRepository frames;

    public FigmaSyncJob(FigmaClient figma, FigmaSyncRepository syncs, FigmaFrameRepository frames) {
        this.figma = figma;
        this.syncs = syncs;
        this.frames = frames;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRequested(FigmaSyncRequested event) {
        run(event.syncId());
    }

    /** 테스트에서도 직접 부른다. 결과는 sync 행의 status(done/failed)로 남는다 */
    @Transactional
    public void run(Long syncId) {
        FigmaSync sync = syncs.findById(syncId).orElse(null);
        if (sync == null) {
            return;
        }
        sync.start();
        try {
            List<FigmaNode> picked = fetch(sync);
            for (FigmaNode node : picked) {
                String url = frameUrl(sync.getFigmaFileKey(), node.id());
                frames.findByProjectIdAndFrameId(sync.getProject().getId(), node.id()).ifPresentOrElse(
                    f -> f.refresh(sync.getFigmaFileKey(), node.name(), url),
                    () -> frames.save(new FigmaFrame(sync.getProject(), node.id(), sync.getFigmaFileKey(), node.name(), url)));
            }
            sync.complete(picked.size());
        } catch (FigmaApiException e) {
            log.warn("Figma 동기화 실패 sync_id={}: {}", syncId, e.getMessage());
            sync.fail(truncate(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Figma 동기화 중 예상치 못한 오류 sync_id={}", syncId, e);
            sync.fail("동기화 중 오류가 발생했습니다.");
        }
    }

    private List<FigmaNode> fetch(FigmaSync sync) {
        if (sync.getFrameIds() == null) {
            return figma.topLevelNodes(sync.getFigmaFileKey()).stream().filter(n -> "FRAME".equals(n.type())).toList();
        }
        Map<String, Optional<FigmaNode>> found = figma.nodes(sync.getFigmaFileKey(), sync.getFrameIds());
        List<String> problems = new ArrayList<>();
        List<FigmaNode> picked = new ArrayList<>();
        for (String id : sync.getFrameIds()) {
            Optional<FigmaNode> node = found.getOrDefault(id, Optional.empty());
            if (node.isEmpty()) {
                problems.add(id + "(없음)");
            } else if (!FRAME_TYPES.contains(node.get().type())) {
                problems.add(id + "(" + node.get().type() + ", 프레임 아님)");
            } else {
                picked.add(node.get());
            }
        }
        if (!problems.isEmpty()) {
            throw new FigmaApiException("동기화할 수 없는 frame_ids: " + String.join(", ", problems));
        }
        return picked;
    }

    /** Figma 편집 화면에서 그 노드로 바로 가는 링크(node-id는 ':' 대신 '-') */
    static String frameUrl(String fileKey, String nodeId) {
        return "https://www.figma.com/design/" + fileKey + "/?node-id=" + nodeId.replace(':', '-');
    }

    private static String truncate(String s) {
        return s.length() <= ERROR_MAX ? s : s.substring(0, ERROR_MAX);
    }
}
