package com.acjb.server.service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;

/**
 * 실시간 알림 스트림(SPEC AC37, 2026-11-11). 받는 사람·프로젝트별로 열린 SSE 연결을 들고 있다가, 알림이 커밋되면 신호만 보낸다.
 * 알림 내용은 싣지 않는다 — 화면이 목록 API(AC14)를 다시 읽으므로 권한·형식이 한 곳에만 있다.
 * 서버 한 대 기준이다: 여러 대로 늘리면 서버끼리 신호를 넘길 장치가 필요하다
 */
@Component
public class NotificationStream {

    static final String EVENT = "notification";
    private static final long RETRY_MILLIS = 5000;

    private final Map<Key, Deque<SseEmitter>> open = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat;
    private final long timeoutMillis;
    private final int maxPerMember;

    public NotificationStream(@Value("${app.notifications.stream-timeout:30m}") Duration timeout,
                              @Value("${app.notifications.heartbeat:25s}") Duration heartbeatEvery,
                              @Value("${app.notifications.stream-max-per-member:5}") int maxPerMember) {
        this.timeoutMillis = timeout.toMillis();
        this.maxPerMember = maxPerMember;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "notification-heartbeat");
            t.setDaemon(true);
            return t;
        });
        // 프록시·브라우저가 조용한 연결을 끊지 않게, 그리고 이미 닫힌 연결을 찾아 치우려고 주석을 보낸다
        long every = heartbeatEvery.toMillis();
        heartbeat.scheduleAtFixedRate(this::beat, every, every, TimeUnit.MILLISECONDS);
    }

    /** 연결을 연다. 상한을 넘으면 가장 오래된 연결을 닫는다(새로고침·여러 탭이 쌓이지 않게) */
    public SseEmitter subscribe(Long projectId, Long memberId) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        Key key = new Key(projectId, memberId);
        SseEmitter evicted = null;
        Deque<SseEmitter> list = open.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (list) {
            list.addLast(emitter);
            if (list.size() > maxPerMember) {
                evicted = list.pollFirst();
            }
        }
        Runnable remove = () -> remove(key, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(e -> remove.run());
        if (evicted != null) {
            evicted.complete();
        }
        // 첫 바이트를 바로 보내야 프록시·브라우저가 연결이 열렸다고 본다
        send(key, emitter, SseEmitter.event().reconnectTime(RETRY_MILLIS).comment("connected"));
        return emitter;
    }

    /** 커밋된 알림만 보낸다 — 롤백되면 이 메서드가 불리지 않는다. 트랜잭션 밖에서 저장된 알림은 바로 보낸다 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(NotificationCreated created) {
        Key key = new Key(created.projectId(), created.recipientId());
        for (SseEmitter emitter : snapshot(key)) {
            send(key, emitter, SseEmitter.event().name(EVENT)
                .data(Map.of("notification_id", String.valueOf(created.notificationId()))));
        }
    }

    int openCount(Long projectId, Long memberId) {
        return snapshot(new Key(projectId, memberId)).size();
    }

    private void beat() {
        for (Key key : List.copyOf(open.keySet())) {
            for (SseEmitter emitter : snapshot(key)) {
                send(key, emitter, SseEmitter.event().comment(""));
            }
        }
    }

    private void send(Key key, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // 브라우저가 떠난 연결 — 치우고 넘어간다(다른 연결·알림 저장에 영향 없음)
            remove(key, emitter);
            emitter.completeWithError(e);
        }
    }

    private List<SseEmitter> snapshot(Key key) {
        Deque<SseEmitter> list = open.get(key);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    private void remove(Key key, SseEmitter emitter) {
        open.computeIfPresent(key, (k, list) -> {
            synchronized (list) {
                list.remove(emitter);
                return list.isEmpty() ? null : list;
            }
        });
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        open.values().forEach(list -> {
            synchronized (list) {
                list.forEach(SseEmitter::complete);
            }
        });
        open.clear();
    }

    private record Key(Long projectId, Long memberId) {
    }
}
