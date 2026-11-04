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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Figma에서 동기화한 디자인 기준 화면 하나. frame_id는 Figma 노드 ID(예 "84:759")이고 API에도 이 값으로 노출한다.
 * 재동기화하면 같은 (project, frame_id) 행을 갱신한다(upsert) — 그래서 UNIQUE 제약을 둔다.
 */
@Entity
@Table(name = "figma_frames",
    uniqueConstraints = @UniqueConstraint(name = "uk_figma_frames_project_frame", columnNames = {"project_id", "frame_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FigmaFrame {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", updatable = false)
    private Project project;

    @Column(name = "frame_id", nullable = false, length = 50, updatable = false)
    private String frameId;

    @Column(nullable = false, length = 100)
    private String figmaFileKey;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 500)
    private String frameUrl;

    @Column(nullable = false)
    private Instant lastSyncedAt;

    public FigmaFrame(Project project, String frameId, String figmaFileKey, String name, String frameUrl) {
        this.project = project;
        this.frameId = frameId;
        this.figmaFileKey = figmaFileKey;
        this.name = name;
        this.frameUrl = frameUrl;
        this.lastSyncedAt = Instant.now();
    }

    /** 재동기화로 같은 프레임을 다시 받았을 때 */
    public void refresh(String figmaFileKey, String name, String frameUrl) {
        this.figmaFileKey = figmaFileKey;
        this.name = name;
        this.frameUrl = frameUrl;
        this.lastSyncedAt = Instant.now();
    }
}
