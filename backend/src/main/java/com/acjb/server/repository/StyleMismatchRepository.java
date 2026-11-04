package com.acjb.server.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.StyleMismatch;

public interface StyleMismatchRepository extends JpaRepository<StyleMismatch, Long> {
    // 정렬은 db-schema.md 인덱스 설계(created_at desc, id desc)를 따른다. frame_id·property 필터는 서비스 구현 때 추가
    List<StyleMismatch> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);
}
