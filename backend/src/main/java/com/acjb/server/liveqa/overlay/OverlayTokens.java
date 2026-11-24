package com.acjb.server.liveqa.overlay;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 오버레이 입장권(2026-11-24, docs/design-live-overlay.md 2절) — 배포 사이트(다른 출처)에 얹힌 QAting 툴바가 등록·조회 API를 부를 때 쓴다.
 * 배포 사이트에서는 QAting 로그인 쿠키가 실리지 않으므로(다른 사이트 요청) 프로젝트·멤버·배포 출처·만료를 서버 비밀로 서명해 넘긴다.
 * 용도 표시(ov)를 담아 연결 화면 입장권과 섞어 쓸 수 없게 한다.
 */
@Component
public class OverlayTokens {

    public static final Duration TTL = Duration.ofHours(4);
    private static final String PURPOSE = "ov";

    public record Ticket(long projectId, long memberId, String targetOrigin, long expiresAt) {
    }

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Clock clock;

    public OverlayTokens(@Value("${app.overlay.secret:}") String secret, Clock clock) {
        if (secret == null || secret.isBlank()) {
            // 비밀을 두지 않으면 서버를 띄울 때마다 새로 만든다 — 다시 시작하면 QAting에서 [사이트에서 QA 시작]을 다시 누르면 된다
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.clock = clock;
    }

    public Ticket issue(long projectId, long memberId, String targetOrigin) {
        long exp = clock.instant().plus(TTL).getEpochSecond();
        return new Ticket(projectId, memberId, targetOrigin, exp);
    }

    public String encode(Ticket t) {
        String payload = PURPOSE + "|" + t.projectId() + "|" + t.memberId() + "|" + t.targetOrigin() + "|" + t.expiresAt();
        String body = ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + ENC.encodeToString(sign(body));
    }

    public Optional<Ticket> verify(String token) {
        if (token == null || token.length() > 2000) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        try {
            byte[] given = DEC.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(given, sign(body))) {
                return Optional.empty();
            }
            String[] parts = new String(DEC.decode(body), StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 5 || !PURPOSE.equals(parts[0])) {
                return Optional.empty();
            }
            long exp = Long.parseLong(parts[4]);
            if (clock.instant().getEpochSecond() > exp) {
                return Optional.empty();
            }
            URI origin = URI.create(parts[3]);
            if (origin.getScheme() == null || origin.getHost() == null) {
                return Optional.empty();
            }
            return Optional.of(new Ticket(Long.parseLong(parts[1]), Long.parseLong(parts[2]), parts[3], exp));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
