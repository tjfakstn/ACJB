package com.acjb.server.scan;

import java.net.URI;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Route;

/**
 * 서버 브라우저가 여는 모든 요청을 TargetUrlPolicy로 검사한다(scan·배포 화면 캡처 공통).
 * 리다이렉트는 브라우저에 맡기지 않는다 — 브라우저가 따라가는 다음 요청은 route를 거치지 않는다
 * (실측 2026-11-04: 302 → 169.254.169.254에 브라우저가 직접 접속). 서버가 한 단계씩 주소를 검사하며 따라간 뒤 결과만 넘겨준다
 */
public final class GuardedBrowserRoutes {

    private static final int MAX_REDIRECTS = 5;

    private GuardedBrowserRoutes() {
    }

    public static void install(BrowserContext context, TargetUrlPolicy policy, double timeoutMs) {
        context.route("**/*", route -> fetchChecked(route, policy, timeoutMs));
    }

    /** 요청을 대신 보내고, 3xx면 Location을 검사한 뒤 직접 따라간다. 막힌 주소가 나오면 그 요청을 끊는다 */
    private static void fetchChecked(Route route, TargetUrlPolicy policy, double timeoutMs) {
        String url = route.request().url();
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (!policy.allows(url)) {
                    route.abort("blockedbyclient");
                    return;
                }
                APIResponse response = route.fetch(new Route.FetchOptions().setUrl(url).setMaxRedirects(0).setTimeout(timeoutMs));
                String location = response.headers().get("location");
                if (response.status() >= 300 && response.status() < 400 && location != null) {
                    url = URI.create(url).resolve(location.trim()).toString();
                    continue;
                }
                route.fulfill(new Route.FulfillOptions().setResponse(response));
                return;
            }
            route.abort("failed");
        } catch (PlaywrightException | IllegalArgumentException e) {
            route.abort("failed");
        }
    }
}
