package com.acjb.server.liveqa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.acjb.server.config.CorsProperties;
import com.acjb.server.scan.TargetUrlPolicy;

/** 헤더로 화면 삽입 가능 여부 판단(AC29), 이미지 형식 판단(AC30), 경로 붙이기 */
class LiveQaUnitTest {

    private final EmbedChecker checker;

    LiveQaUnitTest() {
        CorsProperties cors = new CorsProperties();
        cors.setAllowedOrigins(List.of("http://localhost:5173"));
        checker = new EmbedChecker(new TargetUrlPolicy(true), cors, java.time.Duration.ofSeconds(1));
    }

    @Test
    void X_Frame_Options_DENY와_SAMEORIGIN은_막힌_것으로_본다() {
        assertThat(checker.judge(List.of("DENY"), List.of()).embeddable()).isFalse();
        assertThat(checker.judge(List.of("sameorigin"), List.of()).embeddable()).isFalse();
        assertThat(checker.judge(List.of(), List.of()).embeddable()).isTrue();
    }

    @Test
    void CSP_frame_ancestors가_있으면_그것이_우선이다() {
        assertThat(checker.judge(List.of("DENY"), List.of("default-src 'self'; frame-ancestors http://localhost:5173")).embeddable()).isTrue();
        assertThat(checker.judge(List.of(), List.of("frame-ancestors 'self'")).embeddable()).isFalse();
        assertThat(checker.judge(List.of(), List.of("frame-ancestors 'none'")).embeddable()).isFalse();
        assertThat(checker.judge(List.of(), List.of("frame-ancestors *")).embeddable()).isTrue();
        assertThat(checker.judge(List.of(), List.of("script-src 'self'")).embeddable()).isTrue();
    }

    @Test
    void 이미지는_내용_시그니처로만_판단한다() {
        assertThat(ImageFormats.detect("<svg></svg>xxxxxxxxxxxxx".getBytes())).isNull();
        byte[] webp = new byte[30];
        System.arraycopy("RIFF".getBytes(), 0, webp, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(), 0, webp, 8, 8);
        webp[24] = (byte) 0xFF; webp[25] = 0x04; // 너비-1 = 1279
        webp[27] = (byte) 0xCF; webp[28] = 0x02; // 높이-1 = 719
        ImageFormats.Info info = ImageFormats.detect(webp);
        assertThat(info).isNotNull();
        assertThat(info.contentType()).isEqualTo("image/webp");
        assertThat(info.width()).isEqualTo(1280);
        assertThat(info.height()).isEqualTo(720);
    }

    @Test
    void 배포_주소_뒤에_경로를_붙이고_쿼리는_버린다() {
        assertThat(LiveQaValidation.join("https://a.dev", "/login")).isEqualTo("https://a.dev/login");
        assertThat(LiveQaValidation.join("https://a.dev/", "/")).isEqualTo("https://a.dev/");
        assertThat(LiveQaValidation.join("https://u.github.io/repo/?x=1", "/pricing")).isEqualTo("https://u.github.io/repo/pricing");
        assertThat(LiveQaValidation.join("http://localhost:4173", "/")).isEqualTo("http://localhost:4173/");
    }
}
