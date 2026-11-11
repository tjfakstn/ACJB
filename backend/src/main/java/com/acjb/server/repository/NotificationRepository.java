package com.acjb.server.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acjb.server.domain.Notification;
import com.acjb.server.domain.NotificationEvent;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    long countByIssueIdAndEvent(Long issueId, NotificationEvent event);

    /**
     * AC14 — 요청자가 받는 알림 중 이 프로젝트의 것만(AC12: 다른 프로젝트 알림은 담지 않는다).
     * event가 null이면 거르지 않는다. 정렬 created_at desc·id desc 고정. 화면 문구에 쓰는 이슈·실행·일으킨 사람을 함께 읽는다
     */
    @Query("""
        select n from Notification n
          left join fetch n.issue
          left join fetch n.run
          left join fetch n.actor
        where n.recipient.id = :memberId
          and n.project.id = :projectId
          and (:event is null or n.event = :event)
        order by n.createdAt desc, n.id desc
        """)
    List<Notification> findInbox(@Param("projectId") Long projectId, @Param("memberId") Long memberId,
                                 @Param("event") NotificationEvent event);

    /** 내 알림 한 건 — 남의 알림이나 다른 프로젝트 알림은 없는 것으로 본다 */
    Optional<Notification> findByIdAndProjectIdAndRecipientId(Long id, Long projectId, Long recipientId);

    /** 이 프로젝트에서 내가 읽지 않은 알림을 모두 읽음으로. 이미 읽은 알림의 시각은 바꾸지 않는다 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Notification n set n.readAt = :now
        where n.project.id = :projectId and n.recipient.id = :memberId and n.readAt is null
        """)
    int markAllRead(@Param("projectId") Long projectId, @Param("memberId") Long memberId, @Param("now") Instant now);
}
