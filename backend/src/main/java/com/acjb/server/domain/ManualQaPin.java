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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 실시간 QA 워크스페이스에서 찍은 수동 QA 위치(AC31). 이슈 하나에 0..1개이고 등록 뒤에는 바꾸지 않는다(N6과 같은 이유 — 위치가 재현 정보다).
 * 좌표는 등록 때 보던 화면(viewport) 기준 픽셀이다 — 교차 출처 화면은 스크롤을 읽을 수 없어 스크롤 위치는 없다.
 */
@Entity
@Table(name = "manual_qa_pins")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ManualQaPin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", updatable = false)
    private Project project;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qa_issue_id", updatable = false)
    private QAIssue issue;

    @Column(name = "pin_number", nullable = false, updatable = false)
    private int pinNumber;

    @Column(name = "client_request_id", nullable = false, length = 64, updatable = false)
    private String clientRequestId;

    @Column(nullable = false, length = 100, updatable = false)
    private String title;

    @Column(name = "page_path", nullable = false, length = 500, updatable = false)
    private String pagePath;

    /** 등록할 때의 배포 주소 + 경로. 첨부 이미지 기준이고 배포 주소가 없었으면 null */
    @Column(name = "page_url", length = 1500, updatable = false)
    private String pageUrl;

    @Column(name = "x_px", nullable = false, updatable = false)
    private int xPx;

    @Column(name = "y_px", nullable = false, updatable = false)
    private int yPx;

    @Column(name = "viewport_width", nullable = false, updatable = false)
    private int viewportWidth;

    @Column(name = "viewport_height", nullable = false, updatable = false)
    private int viewportHeight;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "location_basis", nullable = false, length = 20, updatable = false)
    private LocationBasis locationBasis;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "capture_status", nullable = false, length = 20, updatable = false)
    private CaptureStatus captureStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "capture_id", updatable = false)
    private QaCapture capture;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 10, updatable = false)
    private Severity severity;

    @Column(name = "expected_result", length = 1000, updatable = false)
    private String expectedResult;

    @Column(name = "actual_result", length = 1000, updatable = false)
    private String actualResult;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 등록할 때 사람이 확인한 기준 Figma 프레임(AC33). 이름은 그때 값 */
    @Column(name = "figma_frame_id", length = 50, updatable = false)
    private String figmaFrameId;

    @Column(name = "figma_frame_name", length = 255, updatable = false)
    private String figmaFrameName;

    @SuppressWarnings("java:S107") // 위치·캡처·선택 입력을 한 번에 받는 등록 전용 생성자
    public ManualQaPin(Project project, QAIssue issue, int pinNumber, String clientRequestId, String title, String pagePath,
                       String pageUrl, int xPx, int yPx, int viewportWidth, int viewportHeight, LocationBasis locationBasis,
                       CaptureStatus captureStatus, QaCapture capture, Severity severity, String expectedResult,
                       String actualResult, String figmaFrameId, String figmaFrameName, Instant createdAt) {
        this.project = project;
        this.issue = issue;
        this.pinNumber = pinNumber;
        this.clientRequestId = clientRequestId;
        this.title = title;
        this.pagePath = pagePath;
        this.pageUrl = pageUrl;
        this.xPx = xPx;
        this.yPx = yPx;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.locationBasis = locationBasis;
        this.captureStatus = captureStatus;
        this.capture = capture;
        this.severity = severity;
        this.expectedResult = expectedResult;
        this.actualResult = actualResult;
        this.figmaFrameId = figmaFrameId;
        this.figmaFrameName = figmaFrameName;
        this.createdAt = createdAt;
    }
}
