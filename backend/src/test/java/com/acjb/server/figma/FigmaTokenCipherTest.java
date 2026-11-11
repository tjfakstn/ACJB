package com.acjb.server.figma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

/** 소유자 Figma 토큰 암호화(2026-11-11) — 같은 키로만 풀리고, 키가 없으면 받지 않는다 */
class FigmaTokenCipherTest {

    private static String key(int seed) {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (i + seed);
        }
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    void 암호화한_값은_원문이_아니고_같은_키로만_풀린다() {
        FigmaTokenCipher c = new FigmaTokenCipher(key(1));
        String enc = c.encrypt("figd_secret_token_value");
        assertThat(enc).doesNotContain("figd_secret_token_value");
        assertThat(c.encrypt("figd_secret_token_value")).isNotEqualTo(enc); // IV가 매번 다르다
        assertThat(c.decrypt(enc)).isEqualTo("figd_secret_token_value");
        assertThat(new FigmaTokenCipher(key(2)).decrypt(enc)).isNull();   // 다른 키
        assertThat(c.decrypt("깨진값")).isNull();
    }

    @Test
    void 키가_없으면_꺼져_있고_암호화하지_않는다() {
        FigmaTokenCipher off = new FigmaTokenCipher("");
        assertThat(off.enabled()).isFalse();
        assertThat(off.decrypt("x")).isNull();
        assertThatThrownBy(() -> off.encrypt("t")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 기다릴_시간은_분_시간_일로_적는다() {
        assertThat(FigmaErrors.waitText(397_192)).isEqualTo("약 5일");
        assertThat(FigmaErrors.waitText(7_200)).isEqualTo("약 2시간");
        assertThat(FigmaErrors.waitText(400)).isEqualTo("약 7분");
    }
}
