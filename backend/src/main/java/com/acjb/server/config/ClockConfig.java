package com.acjb.server.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 초대 만료(7일) 판정용 시계. 테스트에서 시각을 바꿔 끼울 수 있게 Bean으로 둔다 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
