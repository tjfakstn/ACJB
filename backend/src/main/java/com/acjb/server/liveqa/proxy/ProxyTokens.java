package com.acjb.server.liveqa.proxy;

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
 * 연결 화면(프록시) 입장권 — 프로젝트 id·배포 사이트 출처·만료 시각을 서버 비밀로 서명한다(2026-11-20).
 * 입장권이 있어야 프록시가 움직인다 — 로그인한 그 프로젝트 멤버가 API로 받은 것만(AC12), 아무나 쓰는 열린 프록시가 되지 않게.
 * 배포 출처를 입장권에 담아 화면의 하위 요청마다 DB·GitHub를 다시 읽지 않는다.
 */
@Component
public class ProxyTokens {

    public static final Duration TTL = Duration.ofHours(4);

    public record Ticket(long projectId, String targetOrigin, long expiresAt) {
    }

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Clock clock;

    public ProxyTokens(@Value("${app.proxy.secret:}") String secret, Clock clock) {
        if (secret == null || secret.isBlank()) {
            // 비밀을 두지 않으면 서버를 띄울 때마다 새로 만든다 — 다시 시작하면 입장권을 다시 받으면 된다
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.clock = clock;
    }

    public String issue(long projectId, String targetOrigin) {
        long exp = clock.instant().plus(TTL).getEpochSecond();
        String payload = projectId + "|" + targetOrigin + "|" + exp;
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
            if (parts.length != 3) {
                return Optional.empty();
            }
            long exp = Long.parseLong(parts[2]);
            if (clock.instant().getEpochSecond() > exp) {
                return Optional.empty();
            }
            URI origin = URI.create(parts[1]);
            if (origin.getScheme() == null || origin.getHost() == null) {
                return Optional.empty();
            }
            return Optional.of(new Ticket(Long.parseLong(parts[0]), parts[1], exp));
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
