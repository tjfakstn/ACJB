package com.acjb.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Figma 기준값과 구현 값이 다른 차이 한 건. 이슈에 붙는 경우만 다루므로 이슈 테이블에 포함한다.
 * 컬럼은 이슈에 차이가 없을 때 null이다 — expected/actual 둘 다 있어야 저장한다는 규칙(AC2)은 서비스에서 지킨다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StyleMismatch {

    @Enumerated(EnumType.STRING)
    @Column(name = "mismatch_property")
    private StyleProperty property;

    @Column(name = "mismatch_expected_value")
    private String expectedValue;

    @Column(name = "mismatch_actual_value")
    private String actualValue;

    public StyleMismatch(StyleProperty property, String expectedValue, String actualValue) {
        this.property = property;
        this.expectedValue = expectedValue;
        this.actualValue = actualValue;
    }
}
