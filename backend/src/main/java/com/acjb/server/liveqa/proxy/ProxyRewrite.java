package com.acjb.server.liveqa.proxy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 연결 화면(프록시)에서 응답을 고치는 규칙 — 순수 함수라 따로 시험한다(2026-11-20).
 * <ul>
 *   <li>HTML: &lt;head&gt; 바로 뒤에 ① 요청 주소 바꾸기 스크립트(사이트가 다른 도메인 API를 불러도 프록시를 거치게)와 ② QAting 스니펫을 넣는다</li>
 *   <li>헤더: 화면 삽입을 막는 헤더(X-Frame-Options·CSP 등)는 빼고, 쿠키는 프록시 주소에 맞게, 같은 사이트로의 리다이렉트는 프록시 경로로</li>
 * </ul>
 */
public final class ProxyRewrite {

    /** 다른 도메인 요청을 프록시로 돌리는 경로 — 뒤에 원래 주소(base64url) */
    public static final String EXT_PREFIX = "/__qating/ext/";

    private static final Pattern HEAD = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML = Pattern.compile("<html(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern COOKIE_DOMAIN = Pattern.compile(";\\s*domain=[^;]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern COOKIE_SECURE = Pattern.compile(";\\s*secure(?=;|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COOKIE_SAMESITE_NONE = Pattern.compile(";\\s*samesite=none", Pattern.CASE_INSENSITIVE);

    /** 응답에서 빼는 헤더 — 화면 삽입·보안 정책은 원래 도메인 기준이라 프록시 주소에서는 맞지 않는다 */
    public static final Set<String> DROP_RESPONSE = Set.of("x-frame-options", "content-security-policy",
        "content-security-policy-report-only", "strict-transport-security", "content-length", "transfer-encoding", "connection",
        "keep-alive", "content-encoding", "cross-origin-opener-policy", "cross-origin-embedder-policy", "cross-origin-resource-policy",
        "access-control-allow-origin", "access-control-allow-credentials", "access-control-allow-methods", "access-control-allow-headers",
        "access-control-expose-headers", "access-control-max-age", "set-cookie", "location", "alt-svc", "report-to", "nel");

    /** 원래 사이트로 보내지 않는 요청 헤더 — 연결·압축·출처는 프록시가 다시 정한다 */
    public static final Set<String> DROP_REQUEST = Set.of("host", "connection", "keep-alive", "content-length", "transfer-encoding",
        "accept-encoding", "origin", "referer", "cookie", "upgrade", "te", "trailer", "proxy-authorization", "expect",
        "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port", "x-real-ip", "forwarded", "via");

    private ProxyRewrite() {
    }

    public static String encodeUrl(String url) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }

    public static String decodeUrl(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    /** &lt;head&gt; 바로 뒤에(없으면 &lt;html&gt; 뒤, 그것도 없으면 맨 앞) 주소 바꾸기 스크립트와 스니펫을 넣는다 — 사이트 스크립트보다 먼저 돈다 */
    public static String injectHtml(String html, String targetOrigin, String snippetUrl) {
        String tags = "<script>" + shim(targetOrigin) + "</script><script src=\"" + escapeAttr(snippetUrl) + "\" async></script>";
        Matcher head = HEAD.matcher(html);
        if (head.find()) {
            return html.substring(0, head.end()) + tags + html.substring(head.end());
        }
        Matcher root = HTML.matcher(html);
        if (root.find()) {
            return html.substring(0, root.end()) + "<head>" + tags + "</head>" + html.substring(root.end());
        }
        return tags + html;
    }

    /**
     * 사이트 스크립트가 fetch·XMLHttpRequest·EventSource로 부르는 주소를 바꾼다.
     * 원래 사이트 주소면 프록시의 같은 경로로, 다른 http(s) 주소면 EXT_PREFIX + base64url로 — 서버가 대신 불러 CORS에 막히지 않는다
     */
    static String shim(String targetOrigin) {
        return """
            (function(){var T=%s,P=location.origin,X=%s;
            function b(s){return btoa(unescape(encodeURIComponent(s))).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'')}
            function rw(u){try{var a=new URL(String(u),location.href);if(a.origin===P||(a.protocol!=='http:'&&a.protocol!=='https:'))return u;
            if(a.origin===T)return a.pathname+a.search+a.hash;return X+b(a.href)}catch(e){return u}}
            var f=window.fetch;if(f)window.fetch=function(i,o){if(typeof i==='string'||(window.URL&&i instanceof URL))i=rw(i);
            else if(i&&i.url){var n=rw(i.url);if(n!==i.url)i=new Request(n,i)}return f.call(this,i,o)};
            var op=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){arguments[1]=rw(u);return op.apply(this,arguments)};
            if(window.EventSource){var E=window.EventSource,N=function(u,c){return new E(rw(u),c)};N.prototype=E.prototype;window.EventSource=N}
            })();""".formatted(jsString(targetOrigin), jsString(EXT_PREFIX)).replace("\n", "");
    }

    /** 원래 사이트로의 리다이렉트는 프록시 경로로. 다른 사이트(예: 로그인 제공자)는 그대로 둔다 */
    public static String rewriteLocation(String location, URI requestUri, String targetOrigin) {
        try {
            URI resolved = requestUri.resolve(location);
            String origin = resolved.getScheme() + "://" + resolved.getRawAuthority();
            if (origin.equalsIgnoreCase(targetOrigin)) {
                String path = resolved.getRawPath() == null || resolved.getRawPath().isEmpty() ? "/" : resolved.getRawPath();
                return path + (resolved.getRawQuery() == null ? "" : "?" + resolved.getRawQuery())
                    + (resolved.getRawFragment() == null ? "" : "#" + resolved.getRawFragment());
            }
            return location;
        } catch (IllegalArgumentException e) {
            return location;
        }
    }

    /** 사이트 쿠키를 프록시 주소에 맞춘다 — Domain은 빼고(프록시 호스트에만), http 개발 환경에서는 Secure·SameSite=None도 뺀다 */
    public static String rewriteSetCookie(String cookie, boolean secureOrigin) {
        String out = COOKIE_DOMAIN.matcher(cookie).replaceAll("");
        if (!secureOrigin) {
            out = COOKIE_SECURE.matcher(out).replaceAll("");
            out = COOKIE_SAMESITE_NONE.matcher(out).replaceAll("; SameSite=Lax");
        }
        return out;
    }

    public static boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html");
    }

    private static String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\u003c") + "'";
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
