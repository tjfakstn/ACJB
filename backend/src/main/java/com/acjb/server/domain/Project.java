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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Member owner;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public Project(String name, Member owner) {
        this.name = name;
        this.owner = owner;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
