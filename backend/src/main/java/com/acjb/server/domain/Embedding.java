package com.acjb.server.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재검증 범위 제안(AC20) 검색용 벡터 한 건. 원본(이슈·스타일 차이·fail 재검증)은 만들어진 뒤 텍스트가 바뀌지 않아
 * 원본 하나·모델 하나당 한 번만 만든다. 검색은 같은 project_id 행만 읽는다(N16).
 */
@Entity
@Table(name = "embeddings",
    uniqueConstraints = @UniqueConstraint(name = "uk_embeddings_source", columnNames = {"source_type", "source_id", "model"}),
    indexes = @Index(name = "idx_embeddings_project_model", columnList = "project_id, model"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Embedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", updatable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_type", nullable = false, length = 20, updatable = false)
    private EmbeddingSourceType sourceType;

    @Column(name = "source_id", nullable = false, updatable = false)
    private Long sourceId;

    @Column(nullable = false, length = 40, updatable = false)
    private String model;

    @Column(nullable = false, columnDefinition = "blob", updatable = false)
    private byte[] vector;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Embedding(Project project, EmbeddingSourceType sourceType, Long sourceId, String model, float[] vector) {
        this.project = project;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.model = model;
        this.vector = toBytes(vector);
        this.createdAt = Instant.now();
    }

    public float[] vectorValues() {
        ByteBuffer buf = ByteBuffer.wrap(vector).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[vector.length / Float.BYTES];
        buf.asFloatBuffer().get(out);
        return out;
    }

    private static byte[] toBytes(float[] values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buf.asFloatBuffer().put(values);
        return buf.array();
    }
}
