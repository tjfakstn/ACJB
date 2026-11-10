package com.acjb.server.liveqa;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.acjb.server.scan.GuardedBrowserRoutes;
import com.acjb.server.scan.TargetUrlPolicy;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * 헤드리스 크로미움으로 배포 화면을 열어 화면(viewport)만 찍는다 — 핀 좌표가 viewport 기준이라 전체 페이지가 아니라 첫 화면을 찍는다.
 * scan과 같은 주소 제한을 모든 하위 요청·리다이렉트에 건다(N19). 캡처마다 브라우저를 새로 띄우고 닫는다(Playwright 객체는 스레드 간 공유 불가).
 * 로그인·상태가 필요한 화면은 사용자가 보던 화면과 다를 수 있다(decision_log 2026-11-10).
 */
@Component
public class PlaywrightPageCapturer implements PageCapturer {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightPageCapturer.class);

    /** load 뒤 네트워크가 잠잠해질 때까지 조금 더 기다린다 — SPA는 load 시점에 내용이 비어 있는 경우가 많다 */
    private static final double SETTLE_MS = 3000;

    private final TargetUrlPolicy policy;
    private final double timeoutMs;

    public PlaywrightPageCapturer(TargetUrlPolicy policy, @Value("${app.capture.timeout:30s}") Duration timeout) {
        this.policy = policy;
        this.timeoutMs = timeout.toMillis();
    }

    @Override
    public byte[] capture(String url, int width, int height) {
        Playwright.CreateOptions create = new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
        try (Playwright pw = Playwright.create(create);
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                 .setViewportSize(width, height)
                 .setServiceWorkers(ServiceWorkerPolicy.BLOCK))) {
            GuardedBrowserRoutes.install(context, policy, timeoutMs);
            Page page = context.newPage();
            page.setDefaultTimeout(timeoutMs);
            Response response;
            try {
                response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout(timeoutMs));
            } catch (TimeoutError e) {
                throw new CaptureFailedException("배포 화면이 시간 안에 열리지 않았어요.");
            } catch (PlaywrightException e) {
                throw new CaptureFailedException("배포 화면을 열지 못했어요(주소·네트워크 또는 차단된 리다이렉트 확인).");
            }
            if (response == null || response.status() >= 400) {
                throw new CaptureFailedException("배포 화면이 오류를 돌려줬어요" + (response == null ? "." : "(HTTP " + response.status() + ")."));
            }
            if (!policy.allows(page.url())) {
                throw new CaptureFailedException("배포 화면이 허용되지 않은 주소로 이동했어요.");
            }
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(SETTLE_MS));
            } catch (TimeoutError e) {
                // 계속 요청을 보내는 화면(폴링·광고)도 있다 — 기다리지 않고 지금 화면을 찍는다
            }
            return page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG).setFullPage(false));
        } catch (CaptureFailedException e) {
            throw e;
        } catch (PlaywrightException e) {
            // 브라우저 미설치·실행 실패 등. 원문 메시지에 로컬 경로가 들어 있어 로그에만 남긴다
            log.warn("캡처 브라우저 실행 실패: {}", e.getClass().getSimpleName());
            log.debug("캡처 브라우저 실행 실패 상세", e);
            throw new CaptureFailedException("화면을 찍는 브라우저를 실행하지 못했어요(서버에 ./gradlew playwrightInstall 필요할 수 있음).", e);
        }
    }
}
