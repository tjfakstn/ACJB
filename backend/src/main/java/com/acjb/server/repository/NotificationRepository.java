package com.acjb.server.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acjb.server.domain.Notification;
import com.acjb.server.domain.NotificationEvent;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    long countByIssueIdAndEvent(Long issueId, NotificationEvent event);

    /**
     * AC14 — 요청자가 받는 알림 중 이 프로젝트 이슈의 것만(AC12: 다른 프로젝트 알림은 담지 않는다).
     * event가 null이면 거르지 않는다. 정렬 created_at desc·id desc 고정
     */
    @Query("""
        select n from Notification n
        where n.recipient.id = :memberId
          and n.issue.project.id = :projectId
          and (:event is null or n.event = :event)
        order by n.createdAt desc, n.id desc
        """)
    List<Notification> findInbox(@Param("projectId") Long projectId, @Param("memberId") Long memberId,
                                 @Param("event") NotificationEvent event);
}
