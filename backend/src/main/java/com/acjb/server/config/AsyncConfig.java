package com.acjb.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Figma 동기화처럼 202를 먼저 돌려주고 뒤에서 도는 작업용. 실행기는 Spring Boot 기본(applicationTaskExecutor) */
@Configuration
@EnableAsync
public class AsyncConfig {
}
