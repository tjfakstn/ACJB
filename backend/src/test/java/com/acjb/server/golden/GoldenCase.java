package com.acjb.server.golden;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 테스트 메서드가 검증하는 골든 케이스 id(tests/harness/golden_cases.yaml).
 * 메서드 이름 앞에 id를 붙이지 못한 테스트(케이스를 나중에 만들었거나 교체한 경우)를 케이스에 연결한다.
 * GoldenCatalogTest가 모든 케이스가 하니스나 테스트 메서드에 연결됐는지 이 표시와 메서드 이름으로 확인한다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GoldenCase {
    String[] value();
}
