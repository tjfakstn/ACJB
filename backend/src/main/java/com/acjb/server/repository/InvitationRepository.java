package com.acjb.server.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acjb.server.domain.Invitation;
import com.acjb.server.domain.InvitationStatus;

import jakarta.persistence.LockModeType;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    /** 수락·취소용 — 같은 초대에 요청이 겹쳐도 한 번만 처리되게 행을 잠근다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.tokenHash = :tokenHash")
    Optional<Invitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.id = :id")
    Optional<Invitation> findByIdForUpdate(@Param("id") Long id);

    /** 프로젝트의 대기 초대 — 만료 여부는 서비스가 expires_at으로 거른다. 정렬 created_at desc·id desc */
    List<Invitation> findByProjectIdAndStatusOrderByCreatedAtDescIdDesc(Long projectId, InvitationStatus status);

    /** 내게 온 대기 초대(GET /invitations/mine). 정렬 created_at desc·id desc */
    List<Invitation> findByEmailAndStatusOrderByCreatedAtDescIdDesc(String email, InvitationStatus status);

    /** 같은 이메일 재초대 시 이전 대기 초대를 취소하려고 찾는다(AC17) */
    List<Invitation> findByProjectIdAndEmailAndStatus(Long projectId, String email, InvitationStatus status);
}
