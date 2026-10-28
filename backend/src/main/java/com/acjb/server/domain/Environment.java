package com.acjb.server.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 이슈가 실제로 발생·확인된 환경. 사용자가 입력한 값을 기록만 한다 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Environment {

    @Enumerated(EnumType.STRING)
    private EnvPlatform platform;

    private String device;

    private String browser;

    @Enumerated(EnumType.STRING)
    private DeployStage deployStage;

    public Environment(EnvPlatform platform, String device, String browser, DeployStage deployStage) {
        this.platform = platform;
        this.device = device;
        this.browser = browser;
        this.deployStage = deployStage;
    }
}
