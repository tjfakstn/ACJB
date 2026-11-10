package com.acjb.server.liveqa;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.acjb.server.config.CorsProperties;
import com.acjb.server.scan.TargetUrlPolicy;

/**
 * 배포 화면을 QAting 화면에 iframe으로 넣을 수 있는지 응답 헤더로 판단한다(AC29, 시안 M-09B).
 * 브라우저가 iframe 차단을 페이지에 알려 주지 않아서(교차 출처라 onload도 똑같이 온다) 서버가 대신 헤더를 읽는다.
 * 리다이렉트는 한 단계씩 주소 제한을 검사하며 따라간다(N19) — 내부망으로 새는 요청을 막는다.
 */
@Component
public class EmbedChecker {

    private static final int MAX_REDIRECTS = 5;

    public record Result(Boolean embeddable, String reason) {
        static Result unknown(String reason) {
            return new Result(null, reason);
        }
    }

    private final TargetUrlPolicy policy;
    private final List<String> appOrigins;
    private final HttpClient http;
    private final Duration timeout;

    public EmbedChecker(TargetUrlPolicy policy, CorsProperties cors, @Value("${app.capture.embed-check-timeout:8s}") Duration timeout) {
        this.policy = policy;
        // QAting 화면이 뜨는 출처 — 프론트 주소(CORS 허용 출처)와 같다
        this.appOrigins = cors.getAllowedOrigins().stream().map(o -> o.toLowerCase(Locale.ROOT).replaceAll("/+$", "")).toList();
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Result check(String url) {
        String current = url;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (!policy.allows(current)) {
                    return Result.unknown("배포 주소가 허용되지 않은 주소로 이동해 확인하지 못했어요.");
                }
                HttpRequest request = HttpRequest.newBuilder(URI.create(current)).timeout(timeout)
                    .header("User-Agent", "QAting-embed-check").GET().build();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                var location = response.headers().firstValue("location");
                if (status >= 300 && status < 400 && location.isPresent()) {
                    current = URI.create(current).resolve(location.get().trim()).toString();
                    continue;
                }
                return judge(response.headers().allValues("x-frame-options"), response.headers().allValues("content-security-policy"));
            }
            return Result.unknown("리다이렉트가 너무 많아 확인하지 못했어요.");
        } catch (IOException | IllegalArgumentException e) {
            return Result.unknown("배포 주소에 연결하지 못해 확인하지 못했어요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unknown("확인이 중단됐어요.");
        }
    }

    /** CSP frame-ancestors가 있으면 그것이 X-Frame-Options보다 우선이다(CSP Level 2) */
    Result judge(List<String> xFrameOptions, List<String> csps) {
        for (String csp : csps) {
            for (String directive : csp.split(";")) {
                String[] parts = directive.trim().split("\\s+");
                if (parts.length == 0 || !parts[0].equalsIgnoreCase("frame-ancestors")) {
                    continue;
                }
                for (int i = 1; i < parts.length; i++) {
                    if (allowsApp(parts[i])) {
                        return new Result(true, null);
                    }
                }
                return new Result(false, "사이트의 보안 정책(CSP frame-ancestors)이 다른 사이트 안에 표시되는 것을 막고 있어요.");
            }
        }
        for (String xfo : xFrameOptions) {
            String v = xfo.trim().toUpperCase(Locale.ROOT);
            if (v.equals("DENY") || v.equals("SAMEORIGIN") || v.startsWith("ALLOW-FROM")) {
                return new Result(false, "사이트의 보안 정책(X-Frame-Options: " + v + ")이 다른 사이트 안에 표시되는 것을 막고 있어요.");
            }
        }
        return new Result(true, null);
    }

    private boolean allowsApp(String source) {
        String s = source.toLowerCase(Locale.ROOT).replaceAll("/+$", "");
        if (s.equals("*")) {
            return true;
        }
        for (String origin : appOrigins) {
            URI o = URI.create(origin);
            String host = o.getHost() == null ? "" : o.getHost();
            String hostPort = o.getPort() > 0 ? host + ":" + o.getPort() : host;
            if (s.equals(origin) || s.equals(hostPort) || s.equals(o.getScheme() + ":")) {
                return true;
            }
            // *.example.com 형태
            if (s.contains("*.")) {
                String suffix = s.substring(s.indexOf("*.") + 1);
                if (host.endsWith(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
