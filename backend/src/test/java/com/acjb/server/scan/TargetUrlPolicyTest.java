package com.acjb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** scan target_url 제한 — 내부망 주소를 막고, 루프백은 로컬 개발 설정일 때만 연다(2026-11-04 결정) */
class TargetUrlPolicyTest {

    private final TargetUrlPolicy prod = new TargetUrlPolicy(false);
    private final TargetUrlPolicy dev = new TargetUrlPolicy(true);

    @ParameterizedTest
    @ValueSource(strings = {
        "http://10.0.0.5/", "http://172.16.3.4/", "http://192.168.0.10:8080/", "http://169.254.169.254/latest/meta-data/",
        "http://0.0.0.0/", "http://100.64.1.1/", "http://[fc00::1]/", "http://[fe80::1]/", "http://[::ffff:10.0.0.1]/",
        "http://224.0.0.1/", "http://255.255.255.255/"})
    void 내부망_주소는_개발_설정에서도_막는다(String url) {
        assertThatThrownBy(() -> dev.check(url)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("내부망");
        assertThat(prod.allows(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:5173/login", "http://[::1]:5173/"})
    void 루프백은_로컬_개발_설정일_때만_허용한다(String url) {
        assertThatCode(() -> dev.check(url)).doesNotThrowAnyException();
        assertThatThrownBy(() -> prod.check(url)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("localhost");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.com/", "javascript:alert(1)", "//example.com", "http://", "not a url"})
    void http_https가_아니거나_형식이_틀리면_거부한다(String url) {
        assertThat(dev.allows(url)).isFalse();
    }

    @Test
    void 사용자_정보와_너무_긴_주소는_거부하고_공개_주소는_통과한다() {
        assertThatThrownBy(() -> dev.check("http://user:pw@93.184.216.34/")).hasMessageContaining("사용자 정보");
        assertThatThrownBy(() -> dev.check("http://93.184.216.34/" + "a".repeat(TargetUrlPolicy.MAX_LENGTH))).hasMessageContaining("자 이하");
        assertThatCode(() -> prod.check("https://93.184.216.34/app")).doesNotThrowAnyException();
    }
}
