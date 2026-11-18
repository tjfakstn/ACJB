package com.acjb.server.golden;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.Yaml;

/**
 * 골든 케이스 파일과 SPEC을 읽는다. 테스트는 backend/에서 돌기 때문에 저장소 루트는 한 단계 위다.
 * 케이스를 코드에 다시 적지 않고 이 파일에서 읽어야, 케이스의 기대값을 바꾸면 테스트 결과도 바뀐다.
 */
public final class GoldenCases {

    public static final Path CASES = repoRoot().resolve("tests/harness/golden_cases.yaml");
    public static final Path SPEC = repoRoot().resolve("docs/SPEC.md");

    private static final Pattern SPEC_AC = Pattern.compile("^- \\*\\*(AC\\d+)\\*\\*", Pattern.MULTILINE);

    private GoldenCases() {
    }

    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("tests/harness/golden_cases.yaml"))) {
                return p;
            }
        }
        throw new IllegalStateException("tests/harness/golden_cases.yaml을 찾지 못했어요(작업 위치: " + here + ")");
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> load() {
        try (Reader r = Files.newBufferedReader(CASES, StandardCharsets.UTF_8)) {
            Map<String, Object> root = new Yaml().load(r);
            return new ArrayList<>((List<Map<String, Object>>) root.get("cases"));
        } catch (IOException e) {
            throw new IllegalStateException("골든 케이스 파일을 읽지 못했어요: " + CASES, e);
        }
    }

    /** SPEC.md 5절의 수용 기준 번호(AC1, AC2, …) — 정의된 순서대로 */
    public static Set<String> specAcs() {
        try {
            Matcher m = SPEC_AC.matcher(Files.readString(SPEC, StandardCharsets.UTF_8));
            Set<String> acs = new LinkedHashSet<>();
            while (m.find()) {
                acs.add(m.group(1));
            }
            return acs;
        } catch (IOException e) {
            throw new IllegalStateException("SPEC을 읽지 못했어요: " + SPEC, e);
        }
    }
}
