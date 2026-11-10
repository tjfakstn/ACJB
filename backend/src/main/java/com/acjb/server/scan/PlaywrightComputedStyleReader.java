package com.acjb.server.scan;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * 헤드리스 크로미움으로 target_url을 열고 selector 요소 하나의 getComputedStyle을 읽는다(스파이크와 같은 방식).
 * scan마다 브라우저를 새로 띄우고 닫는다 — Playwright 객체는 스레드 간에 공유할 수 없다.
 * 화면이 여는 모든 요청을 TargetUrlPolicy로 검사하고, 리다이렉트도 한 단계씩 다시 검사해 내부망으로 새는 요청을 끊는다.
 * 브라우저는 ./gradlew playwrightInstall 로 미리 설치한다(실행 중 자동 다운로드는 끈다).
 */
@Component
public class PlaywrightComputedStyleReader implements ComputedStyleReader {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightComputedStyleReader.class);

    /** 요소에서 값을 꺼내는 스크립트. 색은 1×1 캔버스에 칠해 sRGB로 읽는다(oklch·color() 등 형식과 무관) */
    private static final String EXTRACT_JS = """
        el => {
          const cs = getComputedStyle(el);
          const ctx = document.createElement('canvas').getContext('2d', { willReadFrequently: true });
          const rgba = c => {
            ctx.clearRect(0, 0, 1, 1);
            ctx.fillStyle = 'rgba(0,0,0,0)';
            ctx.fillStyle = c;
            ctx.fillRect(0, 0, 1, 1);
            return Array.from(ctx.getImageData(0, 0, 1, 1).data);
          };
          return {
            background: rgba(cs.backgroundColor),
            text: rgba(cs.color),
            opacity: cs.opacity,
            fontWeight: cs.fontWeight,
            radii: [cs.borderTopLeftRadius, cs.borderTopRightRadius, cs.borderBottomRightRadius, cs.borderBottomLeftRadius]
          };
        }""";

    private final TargetUrlPolicy policy;
    private final double timeoutMs;

    public PlaywrightComputedStyleReader(TargetUrlPolicy policy, @Value("${app.scan.timeout:30s}") Duration timeout) {
        this.policy = policy;
        this.timeoutMs = timeout.toMillis();
    }

    @Override
    public ComputedStyle read(String targetUrl, String selector) {
        Playwright.CreateOptions create = new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
        try (Playwright pw = Playwright.create(create);
             Browser browser = launch(pw);
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                 .setViewportSize(1440, 900)
                 .setServiceWorkers(ServiceWorkerPolicy.BLOCK))) {
            GuardedBrowserRoutes.install(context, policy, timeoutMs);
            Page page = context.newPage();
            page.setDefaultTimeout(timeoutMs);
            Response response;
            try {
                response = page.navigate(targetUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout(timeoutMs));
            } catch (TimeoutError e) {
                throw new ScanFailedException("target_url 화면이 시간 안에 열리지 않았습니다.");
            } catch (PlaywrightException e) {
                throw new ScanFailedException("target_url 화면을 열지 못했습니다(주소·네트워크 또는 차단된 리다이렉트 확인).");
            }
            if (response == null || response.status() >= 400) {
                throw new ScanFailedException("target_url 화면이 오류를 돌려줬습니다" + (response == null ? "." : "(HTTP " + response.status() + ")."));
            }
            if (!policy.allows(page.url())) {
                throw new ScanFailedException("target_url이 허용되지 않은 주소로 이동했습니다.");
            }
            Locator matches = page.locator(selector);
            try {
                matches.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(timeoutMs));
            } catch (TimeoutError e) {
                throw new ScanFailedException("selector에 맞는 요소를 화면에서 찾지 못했습니다.");
            } catch (PlaywrightException e) {
                throw new ScanFailedException("selector 형식이 올바르지 않습니다.");
            }
            int count = matches.count();
            if (count != 1) {
                throw new ScanFailedException("selector에 맞는 요소가 " + count + "개입니다. 단일 컴포넌트 하나만 가리키도록 selector를 좁혀 주세요.");
            }
            Object raw = matches.first().evaluate(EXTRACT_JS);
            return parse(raw);
        } catch (ScanFailedException e) {
            throw e;
        } catch (PlaywrightException e) {
            // 브라우저 미설치·실행 실패 등. 원문 메시지에 로컬 경로가 들어 있어 로그에만 남긴다
            log.warn("scan 브라우저 실행 실패: {}", e.getClass().getSimpleName());
            log.debug("scan 브라우저 실행 실패 상세", e);
            throw new ScanFailedException("화면을 여는 브라우저를 실행하지 못했습니다(서버에 ./gradlew playwrightInstall 필요할 수 있음).", e);
        }
    }

    private static Browser launch(Playwright pw) {
        return pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @SuppressWarnings("unchecked")
    static ComputedStyle parse(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ScanFailedException("화면에서 스타일 값을 읽지 못했습니다.");
        }
        boolean opaqueElement = parseDouble(map.get("opacity")) == null || parseDouble(map.get("opacity")) >= 1.0;
        StyleValues.Rgb background = opaqueElement ? rgb(map.get("background")) : null;
        StyleValues.Rgb text = opaqueElement ? rgb(map.get("text")) : null;
        Integer weight = null;
        Double w = parseDouble(map.get("fontWeight"));
        if (w != null && w == Math.rint(w) && w >= 1 && w <= 1000) {
            weight = (int) Math.round(w);
        }
        Double radius = null;
        if (map.get("radii") instanceof List<?> radii && radii.size() == 4) {
            Double first = null;
            boolean uniform = true;
            for (Object r : radii) {
                Double v = px(r);
                if (v == null || (first != null && !first.equals(v))) {
                    uniform = false;
                    break;
                }
                first = v;
            }
            radius = uniform ? first : null;
        }
        return new ComputedStyle(background, text, weight, radius);
    }

    /** 캔버스 결과 [r,g,b,a]. 완전히 불투명할 때만 값으로 본다(alpha는 scan 범위 밖) */
    private static StyleValues.Rgb rgb(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() != 4) {
            return null;
        }
        int[] c = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!(list.get(i) instanceof Number n)) {
                return null;
            }
            c[i] = n.intValue();
        }
        return c[3] == 255 ? new StyleValues.Rgb(c[0], c[1], c[2]) : null;
    }

    /** "8px"만 받는다. "8px 4px"(타원)·"50%" 등은 null */
    private static Double px(Object raw) {
        if (!(raw instanceof String s) || !s.endsWith("px") || s.contains(" ")) {
            return null;
        }
        Double v = parseDouble(s.substring(0, s.length() - 2));
        return v != null && v >= 0 ? v : null;
    }

    private static Double parseDouble(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
