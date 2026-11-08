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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * QA 단위. 로그인한 사용자 누구나 만들고 만든 사람이 소유자다(AC16). 소유자 없는 프로젝트는 만들지 않는다(N15).
 * 이름은 사용자가 정하므로 UNIQUE가 아니다.
 */
@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 선택, 200자 이하(2026-11-07, V5). 작업공간·초대 미리보기·초대 메일에 보인다 */
    @Column(length = 200)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Member owner;

    // --- Figma·GitHub 연결(AC21, V4) — 접근 확인에 성공했을 때만 넷씩 함께 채운다. 연결 API는 다음 단계에서 구현 ---
    @Column(name = "figma_url", length = 500)
    private String figmaUrl;

    @Column(name = "figma_file_key", length = 100)
    private String figmaFileKey;

    @Column(name = "figma_file_name", length = 255)
    private String figmaFileName;

    /** 분석할 페이지(2026-11-07, V6) — 연결은 페이지 공유 링크만 받는다 */
    @Column(name = "figma_page_id", length = 50)
    private String figmaPageId;

    @Column(name = "figma_page_name", length = 255)
    private String figmaPageName;

    /** figma_page_id가 페이지인지 섹션인지(V8) */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "figma_scope", length = 10)
    private FigmaScope figmaScope;

    @Column(name = "figma_connected_at")
    private Instant figmaConnectedAt;

    @Column(name = "github_url", length = 500)
    private String githubUrl;

    /** owner/repo */
    @Column(name = "github_repo", length = 200)
    private String githubRepo;

    @Column(name = "github_default_branch", length = 100)
    private String githubDefaultBranch;

    /** 분석할 브랜치 — 사용자가 고름, 기본값은 기본 브랜치(2026-11-07, V7) */
    @Column(name = "github_branch", length = 100)
    private String githubBranch;

    @Column(name = "github_connected_at")
    private Instant githubConnectedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public Project(String name, Member owner) {
        this(name, null, owner);
    }

    public Project(String name, String description, Member owner) {
        this.name = name;
        this.description = description;
        this.owner = owner;
    }

    /** AC21 — 접근 확인에 성공한 값만 넘긴다(서비스에서 확인) */
    public void connectFigma(String url, String fileKey, String fileName, String pageId, String pageName, FigmaScope scope, Instant now) {
        this.figmaScope = scope;
        this.figmaUrl = url;
        this.figmaFileKey = fileKey;
        this.figmaFileName = fileName;
        this.figmaPageId = pageId;
        this.figmaPageName = pageName;
        this.figmaConnectedAt = now;
    }

    public void connectGithub(String url, String repo, String defaultBranch, String branch, Instant now) {
        this.githubUrl = url;
        this.githubRepo = repo;
        this.githubDefaultBranch = defaultBranch;
        this.githubBranch = branch;
        this.githubConnectedAt = now;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
