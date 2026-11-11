package com.acjb.server.config;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 202를 먼저 돌려주고 뒤에서 도는 작업용 실행기 두 개(2026-11-11).
 * 비교 분석·첫 QA·Re-QA는 몇 분씩 걸리고 안에서 AI를 여러 번 동시에 부른다. 여러 팀이 동시에 돌리면 메모리·AI·Figma 호출 한도를
 * 넘기고, 같은 실행기를 쓰던 초대 메일·Figma 동기화·임베딩까지 밀렸다. 그래서 긴 실행은 전용 실행기(heavyJobExecutor)에서
 * 정해진 수만 동시에 돌리고 나머지는 queued로 기다리게 한다. 그 밖의 짧은 작업은 기본 실행기(applicationTaskExecutor)
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    public static final String HEAVY = "heavyJobExecutor";

    private final int heavyConcurrency;

    public AsyncConfig(@Value("${app.jobs.heavy-concurrency:2}") int heavyConcurrency) {
        this.heavyConcurrency = Math.max(1, heavyConcurrency);
    }

    /** Spring Boot 기본 실행기와 같은 이름 — 이 빈이 있으면 기본 실행기는 만들어지지 않는다 */
    @Bean(name = "applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setThreadNamePrefix("task-");
        executor.initialize();
        return executor;
    }

    @Bean(name = HEAVY)
    public ThreadPoolTaskExecutor heavyJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(heavyConcurrency);
        executor.setMaxPoolSize(heavyConcurrency);
        executor.setThreadNamePrefix("heavy-job-");
        executor.initialize();
        return executor;
    }

    /** @Async에 이름이 없으면 기본 실행기 */
    @Override
    public Executor getAsyncExecutor() {
        return applicationTaskExecutor();
    }
}
