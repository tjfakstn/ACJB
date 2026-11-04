package com.acjb.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.acjb.server.domain.Notification;
import com.acjb.server.domain.NotificationEvent;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    long countByIssueIdAndEvent(Long issueId, NotificationEvent event);
}
