package com.acjb.server.liveqa.proxy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.acjb.server.scan.TargetUrlPolicy;

/** 프록시 주소(app.proxy.origin)가 있을 때만 프록시 필터를 건다 — 보안 필터보다 먼저, 프록시 호스트 요청만 가로챈다 */
@Configuration
@ConditionalOnExpression("'${app.proxy.origin:}' != ''")
public class LiveProxyConfig {

    @Bean
    public FilterRegistrationBean<LiveProxyFilter> liveProxyFilter(@Value("${app.proxy.origin}") String origin,
                                                                   @Value("${app.proxy.snippet-url}") String snippetUrl,
                                                                   ProxyTokens tokens, TargetUrlPolicy policy) {
        FilterRegistrationBean<LiveProxyFilter> bean = new FilterRegistrationBean<>(new LiveProxyFilter(origin.trim(), snippetUrl, tokens, policy));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        bean.addUrlPatterns("/*");
        return bean;
    }
}
