package com.acjb.server.figma;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 프로젝트 소유자 Figma 토큰 암호화(AES-256-GCM, 2026-11-11). 키는 FIGMA_TOKEN_KEY(Base64 32바이트)이고 없으면 토큰을 받지 않는다.
 * 저장 형식: Base64(IV 12바이트 + 암호문·태그). 키·평문을 로그나 예외 메시지에 넣지 않는다
 */
@Component
public class FigmaTokenCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public FigmaTokenCipher(@Value("${app.figma.token-key:}") String base64Key) {
        SecretKeySpec k = null;
        if (base64Key != null && !base64Key.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != 32) {
                throw new IllegalStateException("FIGMA_TOKEN_KEY는 Base64로 적은 32바이트여야 합니다.");
            }
            k = new SecretKeySpec(raw, "AES");
        }
        this.key = k;
    }

    public boolean enabled() {
        return key != null;
    }

    public String encrypt(String plain) {
        require();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + enc.length).put(iv).put(enc).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Figma 토큰을 암호화하지 못했습니다.");
        }
    }

    /** 키가 바뀌었거나 값이 깨졌으면 null — 서버 토큰으로 돌아가고, 소유자가 다시 넣으면 된다 */
    public String decrypt(String stored) {
        if (stored == null || key == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private void require() {
        if (key == null) {
            throw new IllegalStateException("FIGMA_TOKEN_KEY가 설정되지 않았습니다.");
        }
    }
}
