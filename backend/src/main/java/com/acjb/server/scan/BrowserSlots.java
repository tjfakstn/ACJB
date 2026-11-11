package com.acjb.server.scan;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 서버 헤드리스 크로미움을 동시에 띄우는 수(2026-11-11). 배포 화면 캡처·scan은 요청마다 크로미움을 새로 띄우는데,
 * 크로미움은 JVM 힙 밖에서 수백 MB를 써서 여러 명이 동시에 캡처하면 서버 전체가 메모리 부족으로 멈출 수 있다.
 * 자리가 날 때까지 잠시 기다리고, 그래도 없으면 Busy를 던진다(부른 쪽이 429로 바꾼다)
 */
@Component
public class BrowserSlots {

    /** 기다려도 자리가 나지 않았다 */
    public static class Busy extends RuntimeException {
        public Busy() {
            super("지금 서버 브라우저를 쓰는 요청이 많아요. 잠시 후 다시 시도해 주세요.");
        }
    }

    private final Semaphore slots;

    public BrowserSlots(@Value("${app.browser.max-concurrent:2}") int maxConcurrent) {
        this.slots = new Semaphore(Math.max(1, maxConcurrent), true);
    }

    public <T> T run(Duration wait, Supplier<T> work) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Busy();
        }
        if (!acquired) {
            throw new Busy();
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }
}
