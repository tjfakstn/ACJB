package com.acjb.server.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Figma 기준값과 구현 값이 다른 차이 한 건. 사람 입력(manual)과 scan 결과를 같은 테이블에 둔다.
 * 이슈와 독립된 행이라 이슈 없이도 조회(GET /style-mismatches)되고, 이슈는 style_mismatch_id로 참조한다.
 * expected/actual은 NOT NULL — DB 차원에서도 AC2(둘 다 있을 때만 저장)를 막는다. 빈 문자열 거부(400)는 서비스 몫.
 * 기록된 값은 바꾸지 않으므로 setter가 없다.
 */
@Entity
@Table(name = "style_mismatches", indexes = {
    @Index(name = "idx_style_mismatches_project_created", columnList = "project_id, created_at DESC, id DESC"),
    @Index(name = "idx_style_mismatches_project_frame", columnList = "project_id, frame_id"),
    @Index(name = "idx_style_mismatches_project_property", columnList = "project_id, property")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StyleMismatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", updatable = false)
    private Project project;

    @Enumerated(EnumType.STRING)

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10, updatable = false)
    private MismatchSource source;

    /** source=scan일 때만 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", updatable = false)
    private StyleScan scan;

    /** figma_frames와 FK 없이 논리 참조 — 수동 입력은 동기화하지 않은 프레임을 가리킬 수 있다 */
    @Column(name = "frame_id", length = 50, updatable = false)
    private String frameId;

    /** 첫 QA는 매핑 노드 안 하위 요소 id(인스턴스 하위 "I1:2;3:4" 포함, V12) */
    @Column(length = 200, updatable = false)
    private String nodeId;

    /** 비교한 화면. scan이면 target_url */
    @Column(length = 1000, updatable = false)
    private String screen;

    @Column(length = 500, updatable = false)
    private String selector;

    @Enumerated(EnumType.STRING)

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "property", nullable = false, length = 20, updatable = false)
    private StyleProperty property;

    @Column(nullable = false, updatable = false)
    private String expectedValue;

    @Column(nullable = false, updatable = false)
    private String actualValue;

    /** first_qa일 때 코드 값을 확인한 파일·줄(N17). Re-QA는 같은 파일을 다시 읽는다(AC28) */
    @Column(name = "code_path", length = 500, updatable = false)
    private String codePath;

    @Column(name = "code_line", updatable = false)
    private Integer codeLine;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private StyleMismatch(Project project, MismatchSource source, StyleScan scan, String frameId, String nodeId,
                          String screen, String selector, StyleProperty property, String expectedValue, String actualValue) {
        this.project = project;
        this.source = source;
        this.scan = scan;
        this.frameId = frameId;
        this.nodeId = nodeId;
        this.screen = screen;
        this.selector = selector;
        this.property = property;
        this.expectedValue = expectedValue;
        this.actualValue = actualValue;
    }

    /** 사람이 입력한 차이(이슈 등록 시 style_mismatch). frameId·screen은 없으면 null */
    public static StyleMismatch manual(Project project, String frameId, String screen,
                                       StyleProperty property, String expectedValue, String actualValue) {
        return new StyleMismatch(project, MismatchSource.MANUAL, null, frameId, null, screen, null,
            property, expectedValue, actualValue);
    }

    /** scan이 Figma와 구현 양쪽 값을 실제로 추출했을 때만 만든다(N3) */
    public static StyleMismatch fromScan(StyleScan scan, StyleProperty property, String expectedValue, String actualValue) {
        return new StyleMismatch(scan.getProject(), MismatchSource.SCAN, scan, scan.getFrameId(), scan.getNodeId(),
            scan.getTargetUrl(), scan.getSelector(), property, expectedValue, actualValue);
    }

    /** 첫 QA가 Figma 노드 값과 코드 값을 둘 다 확인한 차이(AC23·N17). screen에는 Figma 프레임 이름을 둔다 */
    public static StyleMismatch firstQa(Project project, String frameId, String frameName, String nodeId, StyleProperty property,
                                        String expectedValue, String actualValue, String codePath, Integer codeLine) {
        StyleMismatch m = new StyleMismatch(project, MismatchSource.FIRST_QA, null, frameId, nodeId, frameName, null,
            property, expectedValue, actualValue);
        m.codePath = codePath;
        m.codeLine = codeLine;
        return m;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
