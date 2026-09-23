package com.acjb.server.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties(prefix = "app.cors")
@Getter
@Setter
public class CorsProperties {

    /** YAML 리스트(app.cors.allowed-origins)는 @Value로 못 받아서 @ConfigurationProperties로 바인딩 */
    private List<String> allowedOrigins = List.of();
}
