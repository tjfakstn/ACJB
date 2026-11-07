package com.acjb.server.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.acjb.server.domain.DesignCodeMapping;

public interface DesignCodeMappingRepository extends JpaRepository<DesignCodeMapping, Long> {

    /** AC27 고정 정렬 — 프레임 이름·id, 그 안에서 프레임 짝(node_id null)을 먼저, 노드 이름·id */
    @Query("select m from DesignCodeMapping m where m.run.id = :runId "
        + "order by m.frameName asc, m.frameId asc, case when m.nodeId is null then 0 else 1 end asc, m.nodeName asc, m.nodeId asc")
    List<DesignCodeMapping> findOrdered(Long runId);
}
