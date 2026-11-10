package com.acjb.server.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 테스트 DB(qating_test)는 컨텍스트가 뜰 때마다 비우고 V*.sql을 처음부터 적용한다.
 * 지난 실행이 남긴 행이나 중간에 끊긴 테스트의 찌꺼기가 다음 실행에 섞이지 않게 한다(예전 ddl-auto: create-drop과 같은 효과).
 * 테스트 소스에만 있으므로 운영·개발 DB에서는 절대 clean하지 않는다.
 */
@Configuration
public class TestFlywayConfig {

    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
