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
 * 배포 화면 경로 ↔ Figma 프레임 연결(AC33) — "이 경로의 화면은 이 프레임을 보고 구현했다". 소유자가 지정한 값만 둔다(N19).
 * 프레임 하나에 경로 하나, 한 경로에는 여러 프레임(상태별 시안)이 붙을 수 있다. 경로의 :이름 조각은 아무 한 조각과 맞는다
 */
@Entity
@Table(name = "screen_frame_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScreenFrameLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", updatable = false)
    private Project project;

    @Column(name = "frame_id", nullable = false, length = 50, updatable = false)
    private String frameId;

    @Column(name = "frame_name", nullable = false, length = 255)
    private String frameName;

    @Column(name = "page_path", nullable = false, length = 500)
    private String pagePath;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "updated_by_id")
    private Member updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ScreenFrameLink(Project project, String frameId, String frameName, String pagePath, Member updatedBy, Instant updatedAt) {
        this.project = project;
        this.frameId = frameId;
        this.frameName = frameName;
        this.pagePath = pagePath;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public void change(String frameName, String pagePath, Member updatedBy, Instant updatedAt) {
        this.frameName = frameName;
        this.pagePath = pagePath;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }
}
