package com.acjb.server.analysis;

import java.util.List;
import java.util.Map;

/**
 * 비교 분석(AC22)의 LLM 단계 — Figma 프레임·노드와 코드 파일·컴포넌트의 짝을 제안한다.
 * 제안일 뿐이라 AnalysisJob이 입력에 있는 프레임·노드와 넘긴 파일 경로만 남기고 나머지는 버린다.
 * 테스트에서는 가짜로 바꿔 끼운다(실제 호출은 ClaudeDesignCodeMapper)
 */
public interface DesignCodeMapper {

    /** ① 프레임 → 그 화면을 그리는 파일 하나(없으면 file null). 입력 프레임마다 하나씩 */
    List<FrameMatch> mapFrames(List<FrameSummary> frames, CodeBundle code);

    /** ② 프레임 하나 안의 주요 블록 노드 → 파일·컴포넌트(없으면 file·symbol null) */
    List<NodeMatch> mapNodes(FrameDetail frame, CodeBundle code);

    /** 프레임 요약 — 이름, 자식(손자까지) 이름, 보이는 텍스트 일부 */
    record FrameSummary(String id, String section, String name, List<String> children, List<String> texts) {
    }

    /** 프레임 하나의 노드 트리를 줄 단위로 적은 것("[id] TYPE 'name'") */
    record FrameDetail(String id, String name, String tree) {
    }

    record FrameMatch(String frameId, String file) {
    }

    record NodeMatch(String nodeId, String file, String symbol) {
    }

    /** 분석에 넘기는 코드 — 경로 → 원문(경로순) */
    record CodeBundle(String repo, String commitSha, Map<String, String> files) {
    }
}
