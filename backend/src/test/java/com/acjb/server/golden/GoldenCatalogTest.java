package com.acjb.server.golden;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * 골든 케이스 파일 자체를 검사한다(케이스를 실행하는 것은 GoldenApiHarnessTest와 각 *ApiTest).
 * <ul>
 *   <li>케이스 형식: id 형식·중복 없음, ac가 SPEC.md에 있는 AC, category(normal·boundary·forbidden), 금지 케이스(ng-*)는 N번호</li>
 *   <li>SPEC.md의 모든 AC에 케이스가 1개 이상 있다</li>
 *   <li>모든 케이스가 실행되는 곳에 연결돼 있다 — 하니스({@code harness: api})이거나, 이름이 케이스 id로 시작하는 테스트 메서드이거나,
 *       {@code @GoldenCase("id")}를 단 테스트 메서드. 연결되지 않은 케이스가 있으면 실패한다(검증하지 않는 케이스를 두지 않는다)</li>
 * </ul>
 */
class GoldenCatalogTest {

    private static final Pattern ID = Pattern.compile("^[a-z]+-\\d{3}$");
    private static final Pattern AC = Pattern.compile("AC\\d+");
    private static final Pattern RULE = Pattern.compile("^N\\d+$");
    private static final Set<String> CATEGORIES = Set.of("normal", "boundary", "forbidden");
    private static final Set<String> HARNESSES = Set.of(GoldenApiHarnessTest.HARNESS);

    static List<Map<String, Object>> cases;
    static Set<String> specAcs;

    @BeforeAll
    static void load() {
        cases = GoldenCases.load();
        specAcs = GoldenCases.specAcs();
    }

    @Test
    void 케이스_id는_형식에_맞고_겹치지_않는다() {
        Set<String> seen = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String id = String.valueOf(c.get("id"));
            if (!ID.matcher(id).matches()) {
                problems.add(id + ": id 형식(소문자-숫자3자리)이 아니다");
            }
            if (!seen.add(id)) {
                problems.add(id + ": 같은 id가 두 번 있다");
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void 케이스마다_SPEC에_있는_AC와_범주가_있다() {
        assertThat(specAcs).as("SPEC.md에서 AC를 찾지 못했다").isNotEmpty();
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String id = String.valueOf(c.get("id"));
            Matcher m = AC.matcher(String.valueOf(c.get("ac")));
            boolean any = false;
            while (m.find()) {
                any = true;
                if (!specAcs.contains(m.group())) {
                    problems.add(id + ": SPEC에 없는 " + m.group());
                }
            }
            if (!any) {
                problems.add(id + ": ac가 없다");
            }
            if (!CATEGORIES.contains(String.valueOf(c.get("category")))) {
                problems.add(id + ": category가 normal·boundary·forbidden 중 하나가 아니다(" + c.get("category") + ")");
            }
            if (id.startsWith("ng-")) {
                if (!"forbidden".equals(c.get("category"))) {
                    problems.add(id + ": 금지 기준 케이스는 category forbidden이어야 한다");
                }
                if (!RULE.matcher(String.valueOf(c.get("rule"))).matches()) {
                    problems.add(id + ": 금지 기준 케이스는 rule(N번호)이 있어야 한다");
                }
            }
            if (c.get("description") == null || c.get("input") == null || c.get("expected") == null) {
                problems.add(id + ": description·input·expected가 모두 있어야 한다");
            }
            if (c.get("harness") != null && !HARNESSES.contains(String.valueOf(c.get("harness")))) {
                problems.add(id + ": 없는 하니스 " + c.get("harness"));
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void SPEC의_모든_AC에_케이스가_있다() {
        Set<String> covered = new HashSet<>();
        for (Map<String, Object> c : cases) {
            Matcher m = AC.matcher(String.valueOf(c.get("ac")));
            while (m.find()) {
                covered.add(m.group());
            }
        }
        Set<String> missing = new LinkedHashSet<>(specAcs);
        missing.removeAll(covered);
        assertThat(missing).as("골든 케이스가 없는 AC").isEmpty();
    }

    @Test
    void 정상_경계_금지_범주가_모두_있다() {
        Set<String> used = new HashSet<>();
        cases.forEach(c -> used.add(String.valueOf(c.get("category"))));
        assertThat(used).containsAll(CATEGORIES);
    }

    @Test
    void 모든_케이스가_하니스나_테스트_메서드에_연결된다() throws Exception {
        List<Method> tests = testMethods();
        assertThat(tests).as("테스트 메서드를 찾지 못했다").isNotEmpty();
        Set<String> annotated = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Method m : tests) {
            GoldenCase g = m.getAnnotation(GoldenCase.class);
            if (g != null) {
                annotated.addAll(List.of(g.value()));
            }
            names.add(m.getName().toLowerCase(Locale.ROOT));
        }
        Set<String> ids = new HashSet<>();
        cases.forEach(c -> ids.add(String.valueOf(c.get("id"))));
        Set<String> unknown = new TreeSet<>(annotated);
        unknown.removeAll(ids);
        assertThat(unknown).as("@GoldenCase가 가리키는 케이스가 파일에 없다").isEmpty();

        Set<String> unlinked = new TreeSet<>();
        for (Map<String, Object> c : cases) {
            String id = String.valueOf(c.get("id"));
            if (c.get("harness") != null || annotated.contains(id)) {
                continue;
            }
            String compact = id.replace("-", "");
            String underscored = id.replace("-", "_");
            boolean byName = names.stream().anyMatch(n -> n.startsWith(compact + "_") || n.startsWith(compact + "b_")
                || n.startsWith(underscored + "_"));
            if (!byName) {
                unlinked.add(id);
            }
        }
        assertThat(unlinked).as("실행되는 테스트에 연결되지 않은 케이스").isEmpty();
    }

    /** com.acjb.server 아래 *Test 클래스의 @Test·@TestFactory 메서드 */
    private static List<Method> testMethods() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter((reader, factory) -> reader.getClassMetadata().getClassName().endsWith("Test"));
        List<Method> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.acjb.server")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            for (Method m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Test.class) || m.isAnnotationPresent(TestFactory.class)) {
                    out.add(m);
                }
            }
        }
        return out;
    }
}
