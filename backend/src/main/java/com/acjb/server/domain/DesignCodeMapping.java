package com.acjb.server.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 비교 분석(AC22)이 만든 Figma ↔ 코드 짝(V8). nodeId가 null이면 프레임 자체의 짝(프레임 → 파일),
 * 아니면 프레임 안 블록의 짝(노드 → 파일·컴포넌트). 저장소에 실제로 있는 경로만 저장한다
 */
@Entity
@Table(name = "design_code_mappings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DesignCodeMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, updatable = false)
    private ProjectRun run;

    @Column(name = "frame_id", nullable = false, length = 50, updatable = false)
    private String frameId;

    @Column(name = "frame_name", nullable = false, length = 255, updatable = false)
    private String frameName;

    @Column(name = "node_id", length = 50, updatable = false)
    private String nodeId;

    @Column(name = "node_name", length = 255, updatable = false)
    private String nodeName;

    @Column(name = "code_path", nullable = false, length = 500, updatable = false)
    private String codePath;

    @Column(name = "code_symbol", length = 255, updatable = false)
    private String codeSymbol;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private DesignCodeMapping(Project project, ProjectRun run, String frameId, String frameName, String nodeId, String nodeName,
                              String codePath, String codeSymbol, Instant now) {
        this.project = project;
        this.run = run;
        this.frameId = frameId;
        this.frameName = cut(frameName);
        this.nodeId = nodeId;
        this.nodeName = nodeName == null ? null : cut(nodeName);
        this.codePath = codePath;
        this.codeSymbol = codeSymbol == null ? null : cut(codeSymbol);
        this.createdAt = now;
    }

    public static DesignCodeMapping frame(Project project, ProjectRun run, String frameId, String frameName, String codePath, Instant now) {
        return new DesignCodeMapping(project, run, frameId, frameName, null, null, codePath, null, now);
    }

    public static DesignCodeMapping node(Project project, ProjectRun run, String frameId, String frameName, String nodeId, String nodeName,
                                         String codePath, String codeSymbol, Instant now) {
        return new DesignCodeMapping(project, run, frameId, frameName, nodeId, nodeName, codePath, codeSymbol, now);
    }

    /** Figma 이름은 길이 제한이 없어 컬럼(255)에 맞춰 자른다 */
    private static String cut(String s) {
        return s.length() > 255 ? s.substring(0, 255) : s;
    }
}
