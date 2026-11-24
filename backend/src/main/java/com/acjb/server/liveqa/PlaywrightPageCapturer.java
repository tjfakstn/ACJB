package com.acjb.server.liveqa;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.acjb.server.domain.PageElement;
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
 * 헤드리스 크로미움으로 배포 화면을 열어 찍는다. 기본은 화면(viewport)만, 수동 QA 등록용은 전체 페이지와 요소 위치(2026-11-20).
 * scan과 같은 주소 제한을 모든 하위 요청·리다이렉트에 건다(N19). 캡처마다 브라우저를 새로 띄우고 닫는다(Playwright 객체는 스레드 간 공유 불가).
 * 로그인·상태가 필요한 화면은 사용자가 보던 화면과 다를 수 있다(decision_log 2026-11-10).
 */
@Component
public class PlaywrightPageCapturer implements PageCapturer {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightPageCapturer.class);

    /** load 뒤 네트워크가 잠잠해질 때까지 조금 더 기다린다 — SPA는 load 시점에 내용이 비어 있는 경우가 많다 */
    private static final double SETTLE_MS = 3000;

    /** 캡처는 사용자가 화면 앞에서 기다린다 — 자리가 나기를 오래 기다리지 않는다 */
    private static final Duration SLOT_WAIT = Duration.ofSeconds(20);

    private final TargetUrlPolicy policy;
    private final double timeoutMs;
    private final com.acjb.server.scan.BrowserSlots slots;

    public PlaywrightPageCapturer(TargetUrlPolicy policy, @Value("${app.capture.timeout:30s}") Duration timeout,
                                  com.acjb.server.scan.BrowserSlots slots) {
        this.policy = policy;
        this.timeoutMs = timeout.toMillis();
        this.slots = slots;
    }

    /**
     * 보이는 요소의 위치(페이지 맨 위 기준)·CSS 경로·글자 앞부분. id가 있으면 #id에서 끊는다.
     * 숨김·크기 0·투명 요소와 html·body는 뺀다. 문서 순서대로라 뒤의 요소가 더 안쪽(자식)이다
     */
    private static final String ELEMENTS_JS = """
        ([max, maxHeight]) => {
          const ok = (id) => /^[A-Za-z][\\w-]*$/.test(id);
          const path = (el) => {
            const parts = [];
            for (let e = el; e && e.nodeType === 1 && e !== document.body && e !== document.documentElement && parts.length < 8; e = e.parentElement) {
              if (e.id && ok(e.id)) { parts.unshift('#' + e.id); break; }
              let p = e.tagName.toLowerCase();
              const same = e.parentElement ? Array.from(e.parentElement.children).filter((c) => c.tagName === e.tagName) : [];
              if (same.length > 1) p += ':nth-of-type(' + (same.indexOf(e) + 1) + ')';
              parts.unshift(p);
            }
            return parts.join(' > ');
          };
          const out = [];
          for (const el of document.body ? document.body.querySelectorAll('*') : []) {
            if (out.length >= max) break;
            const r = el.getBoundingClientRect();
            if (r.width < 1 || r.height < 1) continue;
            const y = Math.round(r.top + window.scrollY);
            if (y >= maxHeight) continue;
            const cs = getComputedStyle(el);
            if (cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0) continue;
            const text = (el.innerText || el.getAttribute('aria-label') || el.getAttribute('alt') || el.getAttribute('placeholder') || '')
              .replace(/\\s+/g, ' ').trim().slice(0, 200);
            out.push({ s: path(el).slice(0, 500), t: text, x: Math.round(r.left + window.scrollX), y, w: Math.round(r.width), h: Math.round(r.height) });
          }
          return out;
        }
        """;

    /** 동시에 띄우는 브라우저 수를 지킨다 — 자리가 없으면 BrowserSlots.Busy */
    @Override
    public byte[] capture(String url, int width, int height) {
        return slots.run(SLOT_WAIT, () -> doCapture(url, width, height, false).png());
    }

    @Override
    public PageShot captureFullPage(String url, int width, int height) {
        return slots.run(SLOT_WAIT, () -> doCapture(url, width, height, true));
    }

    private PageShot doCapture(String url, int width, int height, boolean fullPage) {
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
            if (!fullPage) {
                return new PageShot(page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG).setFullPage(false)), height, List.of());
            }
            int scrollHeight = ((Number) page.evaluate("() => Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)")).intValue();
            int pageHeight = Math.max(height, Math.min(scrollHeight, MAX_PAGE_HEIGHT));
            List<PageElement> elements = readElements(page.evaluate(ELEMENTS_JS, List.of(MAX_ELEMENTS, pageHeight)));
            byte[] png = page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG).setFullPage(true)
                .setClip(0, 0, width, pageHeight));
            return new PageShot(png, pageHeight, elements);
        } catch (CaptureFailedException e) {
            throw e;
        } catch (PlaywrightException e) {
            // 브라우저 미설치·실행 실패 등. 원문 메시지에 로컬 경로가 들어 있어 로그에만 남긴다
            log.warn("캡처 브라우저 실행 실패: {}", e.getClass().getSimpleName());
            log.debug("캡처 브라우저 실행 실패 상세", e);
            throw new CaptureFailedException("화면을 찍는 브라우저를 실행하지 못했어요(서버에 ./gradlew playwrightInstall 필요할 수 있음).", e);
        }
    }

    /** 브라우저가 돌려준 목록을 형식대로 읽는다 — 모양이 다른 항목은 버린다 */
    private static List<PageElement> readElements(Object raw) {
        List<PageElement> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("s") instanceof String sel && !sel.isBlank()
                && m.get("x") instanceof Number x && m.get("y") instanceof Number y && m.get("w") instanceof Number w && m.get("h") instanceof Number h) {
                String text = m.get("t") instanceof String t ? t : "";
                out.add(new PageElement(cut(sel, PageElement.SELECTOR_MAX), cut(text, PageElement.TEXT_MAX), x.intValue(), y.intValue(),
                    w.intValue(), h.intValue()));
            }
        }
        return out;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
