package com.acjb.server.liveqa.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import com.acjb.server.scan.TargetUrlPolicy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 연결 화면(프록시) — 프록시 호스트(예: view.acjb-qating.com)로 온 요청만 받아 원래 배포 사이트로 대신 보내고, HTML에 QAting 스니펫을 넣어 돌려준다(2026-11-20).
 * 배포 사이트에 스니펫을 넣지 않아도 실시간 화면의 요소에 핀을 붙일 수 있게 하기 위해서다.
 * <ul>
 *   <li>QAting과 다른 호스트라 QAting 로그인 쿠키(호스트 전용)가 오지 않고, 사이트 스크립트가 QAting 화면·쿠키를 읽을 수 없다</li>
 *   <li>입장권(ProxyTokens)이 있어야 움직인다 — /__qating/enter?t=…로 받은 입장권을 이 호스트의 쿠키로 두고 하위 요청마다 확인한다</li>
 *   <li>보내는 주소는 scan과 같은 제한(내부망·루프백 거부)을 지난 http(s)만. 본문 10MB, 30초까지</li>
 *   <li>로그인·웹소켓·사이트마다 다른 보안 정책 때문에 깨질 수 있다 — 화면은 원래 화면(스니펫·캡처)으로 바꿀 수 있다</li>
 * </ul>
 */
public class LiveProxyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LiveProxyFilter.class);

    public static final String COOKIE = "__qating_proxy";
    private static final String ENTER = "/__qating/enter";
    private static final int MAX_BODY = 10 * 1024 * 1024;
    private static final Duration HOST_CHECK_TTL = Duration.ofMinutes(5);
    private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private final String proxyHost;
    private final boolean secureOrigin;
    private final String snippetUrl;
    private final ProxyTokens tokens;
    private final TargetUrlPolicy policy;
    private final HttpClient http;
    /** 호스트별 주소 제한 결과 — 하위 요청마다 DNS를 다시 보지 않게 잠시 기억한다 */
    private final Map<String, Instant> allowedHosts = new ConcurrentHashMap<>();

    public LiveProxyFilter(String proxyOrigin, String snippetUrl, ProxyTokens tokens, TargetUrlPolicy policy) {
        URI origin = URI.create(proxyOrigin);
        this.proxyHost = origin.getHost().toLowerCase(Locale.ROOT);
        this.secureOrigin = "https".equalsIgnoreCase(origin.getScheme());
        this.snippetUrl = snippetUrl;
        this.tokens = tokens;
        this.policy = policy;
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String host = request.getServerName();
        return host == null || !host.toLowerCase(Locale.ROOT).equals(proxyHost);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        if (ENTER.equals(path)) {
            enter(req, res);
            return;
        }
        Optional<ProxyTokens.Ticket> ticket = Optional.ofNullable(cookie(req)).flatMap(tokens::verify);
        if (ticket.isEmpty()) {
            plain(res, 403, "QAting 연결 화면이 만료됐어요. QAting 실시간 QA 화면에서 다시 열어 주세요.");
            return;
        }
        String target;
        if (path.startsWith(ProxyRewrite.EXT_PREFIX)) {
            try {
                target = ProxyRewrite.decodeUrl(path.substring(ProxyRewrite.EXT_PREFIX.length()));
            } catch (IllegalArgumentException e) {
                plain(res, 400, "잘못된 주소예요.");
                return;
            }
        } else {
            target = ticket.get().targetOrigin() + path + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        }
        forward(req, res, ticket.get(), target);
    }

    /** 입장 — 입장권을 이 호스트의 쿠키로 두고 원래 경로로 보낸다 */
    private void enter(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String token = req.getParameter("t");
        if (tokens.verify(token).isEmpty()) {
            plain(res, 403, "QAting 연결 화면 입장권이 맞지 않거나 만료됐어요. 다시 열어 주세요.");
            return;
        }
        String to = req.getParameter("path");
        if (to == null || !to.startsWith("/") || to.startsWith("//")) {
            to = "/";
        }
        res.addHeader("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; " + cookieSite() + "; Max-Age=" + ProxyTokens.TTL.toSeconds());
        res.setHeader("Cache-Control", "no-store");
        res.setStatus(302);
        res.setHeader("Location", to);
    }

    private void forward(HttpServletRequest req, HttpServletResponse res, ProxyTokens.Ticket ticket, String target) throws IOException {
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException e) {
            plain(res, 400, "잘못된 주소예요.");
            return;
        }
        if (!allowed(uri)) {
            plain(res, 403, "이 주소는 열 수 없어요(내부망·허용되지 않은 주소).");
            return;
        }
        byte[] body = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            plain(res, 413, "요청 본문이 너무 커요(10MB까지).");
            return;
        }
        HttpRequest.Builder out = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
        for (String name : Collections.list(req.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (ProxyRewrite.DROP_REQUEST.contains(lower) || lower.startsWith("sec-")) {
                continue;
            }
            for (String v : Collections.list(req.getHeaders(name))) {
                try {
                    out.header(name, v);
                } catch (IllegalArgumentException ignored) {
                    // HttpClient가 직접 정하는 헤더(예: date)는 건너뛴다
                }
            }
        }
        // 원래 사이트의 화면에서 부른 것처럼 — API가 출처를 확인해도 통과하게
        out.header("Origin", ticket.targetOrigin());
        out.header("Referer", ticket.targetOrigin() + "/");
        out.header("Accept-Encoding", "identity");
        String siteCookies = siteCookies(req);
        if (!siteCookies.isEmpty()) {
            out.header("Cookie", siteCookies);
        }
        String method = req.getMethod();
        out.method(method, body.length == 0 && ("GET".equals(method) || "HEAD".equals(method))
            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));

        HttpResponse<InputStream> upstream;
        try {
            upstream = http.send(out.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plain(res, 502, "원래 사이트가 응답하지 않았어요.");
            return;
        } catch (IOException e) {
            log.debug("프록시 요청 실패 {}: {}", uri.getHost(), e.getClass().getSimpleName());
            plain(res, 502, "원래 사이트에 연결하지 못했어요.");
            return;
        }
        res.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(":") || ProxyRewrite.DROP_RESPONSE.contains(lower)) {
                return;
            }
            values.forEach(v -> res.addHeader(name, v));
        });
        upstream.headers().allValues("set-cookie").forEach(c -> res.addHeader("Set-Cookie", ProxyRewrite.rewriteSetCookie(c, secureOrigin)));
        upstream.headers().firstValue("location")
            .ifPresent(loc -> res.setHeader("Location", ProxyRewrite.rewriteLocation(loc, uri, ticket.targetOrigin())));

        String contentType = upstream.headers().firstValue("content-type").orElse(null);
        try (InputStream in = upstream.body(); OutputStream os = res.getOutputStream()) {
            if (ProxyRewrite.isHtml(contentType) && !"HEAD".equals(method)) {
                Charset charset = charset(contentType);
                String html = new String(in.readAllBytes(), charset);
                byte[] rewritten = ProxyRewrite.injectHtml(html, ticket.targetOrigin(), snippetUrl).getBytes(charset);
                res.setContentLength(rewritten.length);
                res.setHeader("Cache-Control", "no-store");
                os.write(rewritten);
            } else {
                in.transferTo(os);
            }
        }
    }

    /**
     * 입장권 쿠키의 SameSite — 배포(https://view.{도메인})는 QAting과 같은 사이트라 Lax로 충분하다.
     * 로컬(http://view.localhost)은 localhost와 다른 사이트로 보여 iframe 안 쿠키가 막히므로 None; Secure(브라우저는 localhost를 안전한 출처로 본다)
     */
    private String cookieSite() {
        if (secureOrigin) {
            return "SameSite=Lax; Secure";
        }
        return proxyHost.endsWith("localhost") ? "SameSite=None; Secure" : "SameSite=Lax";
    }

    private boolean allowed(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            return false;
        }
        String key = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + uri.getPort();
        Instant until = allowedHosts.get(key);
        if (until != null && until.isAfter(Instant.now())) {
            return true;
        }
        if (!policy.allows(scheme + "://" + uri.getRawAuthority() + "/")) {
            return false;
        }
        allowedHosts.put(key, Instant.now().plus(HOST_CHECK_TTL));
        return true;
    }

    private static String cookie(HttpServletRequest req) {
        if (req.getCookies() == null) {
            return null;
        }
        for (Cookie c : req.getCookies()) {
            if (COOKIE.equals(c.getName())) {
                return URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** 사이트가 둔 쿠키만 원래 사이트로 — 입장권 쿠키는 보내지 않는다 */
    private static String siteCookies(HttpServletRequest req) {
        String raw = req.getHeader("Cookie");
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split(";")) {
            String p = part.trim();
            if (p.isEmpty() || p.startsWith(COOKIE + "=")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static Charset charset(String contentType) {
        Matcher m = CHARSET.matcher(contentType == null ? "" : contentType);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (IllegalArgumentException e) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void plain(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("text/plain;charset=UTF-8");
        res.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
    }
}
