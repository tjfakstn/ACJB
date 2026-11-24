package com.acjb.server.domain;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배포 화면 캡처 또는 첨부 이미지 한 장(AC30). v1은 파일 저장소 없이 DB에 둔다 — 이미지 바이트는 목록 조회 때 읽지 않도록 지연 로딩한다.
 */
@Entity
@Table(name = "qa_captures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QaCapture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", updatable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10, updatable = false)
    private CaptureKind kind;

    @Column(name = "content_type", nullable = false, length = 20, updatable = false)
    private String contentType;

    /** kind=auto일 때 서버 브라우저가 연 주소 */
    @Column(name = "page_url", length = 1500, updatable = false)
    private String pageUrl;

    @Column(nullable = false, updatable = false)
    private int width;

    @Column(nullable = false, updatable = false)
    private int height;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "image", nullable = false, updatable = false, columnDefinition = "LONGBLOB")
    private byte[] image;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", updatable = false)
    private Member createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 전체 페이지 캡처(2026-11-20) — width·height는 이미지 크기(페이지 전체), viewportHeight는 브라우저 화면 높이 */
    @Column(name = "full_page", nullable = false, updatable = false)
    private boolean fullPage;

    @Column(name = "viewport_height", updatable = false)
    private Integer viewportHeight;

    /** 찍는 순간 화면 요소의 위치 목록(JSON, 페이지 맨 위 기준) — 핀을 찍은 점의 요소를 고를 때 읽는다 */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "elements", updatable = false, columnDefinition = "MEDIUMTEXT")
    private String elements;

    public QaCapture(Project project, CaptureKind kind, String contentType, String pageUrl, int width, int height, byte[] image,
                     Member createdBy, Instant createdAt) {
        this(project, kind, contentType, pageUrl, width, height, image, createdBy, createdAt, false, null, null);
    }

    public QaCapture(Project project, CaptureKind kind, String contentType, String pageUrl, int width, int height, byte[] image,
                     Member createdBy, Instant createdAt, boolean fullPage, Integer viewportHeight, String elements) {
        this.fullPage = fullPage;
        this.viewportHeight = viewportHeight;
        this.elements = elements;
        this.project = project;
        this.kind = kind;
        this.contentType = contentType;
        this.pageUrl = pageUrl;
        this.width = width;
        this.height = height;
        this.image = image;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }
}
