package com.acjb.server.scan;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * scan이 여는 주소 제한(2026-11-04 설만수 결정: 개발에서만 localhost 허용). 서버가 사용자가 준 URL을 직접 열기 때문에,
 * 서버 내부망(루프백·사설 IP·링크 로컬/클라우드 메타데이터 169.254.169.254 등)으로 가는 요청을 막는다.
 * 요청 단계에서 target_url을 한 번 검사하고, 브라우저가 화면을 여는 동안에도 모든 하위 요청(리다이렉트·리소스)을 다시 검사한다.
 * 루프백은 app.scan.allow-localhost=true(로컬 개발)일 때만 허용한다. 사설 IP는 그때도 막는다.
 */
@Component
public class TargetUrlPolicy {

    public static final int MAX_LENGTH = 1000;

    private final boolean allowLocalhost;

    public TargetUrlPolicy(@Value("${app.scan.allow-localhost:false}") boolean allowLocalhost) {
        this.allowLocalhost = allowLocalhost;
    }

    /** 허용되지 않으면 이유를 담은 IllegalArgumentException(사용자에게 그대로 보여도 되는 메시지) */
    public void check(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("target_url이 비어 있습니다.");
        }
        if (raw.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("target_url은 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("target_url 형식이 올바르지 않습니다.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("target_url은 http 또는 https 주소여야 합니다.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("target_url에 사용자 정보(user@)를 넣을 수 없습니다.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("target_url에 호스트가 없습니다.");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("target_url의 호스트를 찾을 수 없습니다.");
        }
        for (InetAddress address : addresses) {
            if (blocked(address)) {
                throw new IllegalArgumentException(address.isLoopbackAddress()
                    ? "target_url이 이 서버 자신(localhost)을 가리킵니다. 로컬 개발에서만 app.scan.allow-localhost로 허용할 수 있습니다."
                    : "target_url이 내부망 주소를 가리켜 열 수 없습니다.");
            }
        }
    }

    /** 브라우저 하위 요청용 — 예외 대신 true/false */
    public boolean allows(String url) {
        try {
            check(url);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    boolean blocked(InetAddress a) {
        if (a.isLoopbackAddress()) {
            return !allowLocalhost;
        }
        if (a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return blockedV4(b);
        }
        if (a instanceof Inet6Address) {
            // fc00::/7 고유 로컬, fec0::/10 사이트 로컬(구), 64:ff9b::/96 NAT64·::ffff:0:0/96 매핑 주소는 안의 IPv4로 판단
            if ((b[0] & 0xFE) == 0xFC || ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0xC0)) {
                return true;
            }
            boolean mapped = isZero(b, 0, 10) && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
            boolean nat64 = (b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
                && isZero(b, 4, 12);
            if (mapped || nat64) {
                byte[] v4 = {b[12], b[13], b[14], b[15]};
                try {
                    return blocked(InetAddress.getByAddress(v4));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean blockedV4(byte[] b) {
        int o0 = b[0] & 0xFF;
        int o1 = b[1] & 0xFF;
        return o0 == 0                                  // 0.0.0.0/8
            || (o0 == 100 && o1 >= 64 && o1 <= 127)     // 100.64.0.0/10 (CGNAT)
            || (o0 == 192 && o1 == 0 && (b[2] & 0xFF) == 0) // 192.0.0.0/24
            || (o0 == 198 && (o1 == 18 || o1 == 19))    // 198.18.0.0/15
            || o0 >= 240;                               // 240.0.0.0/4, 255.255.255.255
    }

    private static boolean isZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
