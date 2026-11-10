package com.acjb.server.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acjb.server.domain.ManualQaPin;

public interface ManualQaPinRepository extends JpaRepository<ManualQaPin, Long> {

    Optional<ManualQaPin> findByProjectIdAndClientRequestId(Long projectId, String clientRequestId);

    Optional<ManualQaPin> findByIssueId(Long issueId);

    List<ManualQaPin> findByIssueIdIn(Collection<Long> issueIds);

    @Query("select coalesce(max(p.pinNumber), 0) from ManualQaPin p where p.project.id = :projectId")
    int maxPinNumber(@Param("projectId") Long projectId);
}
