package com.acjb.server.analysis;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.acjb.server.github.GithubClient;

/**
 * 저장소에서 비교 분석에 넘길 UI 코드 파일을 고른다(2026-11-07).
 * 스파이크는 frontend/src를 통째로 넣었다 — 저장소 구조를 모르므로 확장자·경로 규칙으로 고르고, 너무 크면 실패로 끝낸다
 * (잘라서 넘기면 매핑이 조용히 빠지므로). 큰 저장소의 파일 고르기는 다음 단계 과제다.
 * 앱(2026-11-11): React Native는 웹과 같은 확장자, Flutter는 .dart. 네이티브 폴더(android·ios 등)와 생성된 Dart 코드는 뺀다
 */
final class CodeSelector {

    static final Set<String> EXTENSIONS = Set.of(".tsx", ".jsx", ".ts", ".js", ".vue", ".svelte", ".dart");
    /** 빌드 결과·의존성·테스트 같은 화면이 아닌 경로 */
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", "dist", "build", ".next", "out", "coverage", "vendor",
        "__tests__", "__mocks__", "test", "tests", "e2e", ".storybook", "public", "scripts",
        // 앱 — 네이티브 프로젝트 폴더와 Flutter·Expo 도구 폴더(2026-11-11)
        "android", "ios", "macos", "windows", "linux", ".dart_tool", "integration_test", ".expo");
    /** build_runner 등이 만든 Dart 코드 */
    private static final java.util.regex.Pattern GENERATED_DART = java.util.regex.Pattern.compile(".*\\.(g|freezed|gr|mocks|config)\\.dart$");
    /** 파일 하나가 이보다 크면 생성된 코드로 보고 뺀다 */
    static final long MAX_FILE_BYTES = 100_000;
    static final int MAX_FILES = 300;

    private CodeSelector() {
    }

    static List<GithubClient.TreeFile> select(List<GithubClient.TreeFile> tree) {
        return tree.stream().filter(f -> isUiCode(f.path()) && f.size() <= MAX_FILE_BYTES)
            .sorted((a, b) -> a.path().compareTo(b.path())).toList();
    }

    static boolean isUiCode(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (EXTENSIONS.stream().noneMatch(name::endsWith)) {
            return false;
        }
        if (name.endsWith(".d.ts") || name.matches(".*\\.(test|spec|stories)\\.[a-z]+$") || name.matches(".*\\.config\\.[a-z]+$")
            || name.startsWith("eslint") || name.startsWith(".") || GENERATED_DART.matcher(name).matches()) {
            return false;
        }
        for (String dir : lower.split("/")) {
            if (SKIP_DIRS.contains(dir)) {
                return false;
            }
        }
        return true;
    }
}
