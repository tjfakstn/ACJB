package com.acjb.server.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.StyleProperty;

public interface StyleMismatchRepository extends JpaRepository<StyleMismatch, Long> {
    // 정렬은 db-schema.md 인덱스 설계(created_at desc, id desc)를 따른다. frame_id·property 필터는 서비스 구현 때 추가
    List<StyleMismatch> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);

    /** GET /style-mismatches — frame_id·property는 없으면 거르지 않는다. 정렬 created_at desc·id desc 고정 */
    @Query("""
        select m from StyleMismatch m
        where m.project.id = :projectId
          and (:frameId is null or m.frameId = :frameId)
          and (:property is null or m.property = :property)
        order by m.createdAt desc, m.id desc
        """)
    List<StyleMismatch> search(@Param("projectId") Long projectId, @Param("frameId") String frameId,
                               @Param("property") StyleProperty property);

    /** 경로의 projectId와 함께 찾아 다른 프로젝트 기록이 섞이지 않게 한다(N10) */
    Optional<StyleMismatch> findByIdAndProjectId(Long id, Long projectId);
}
