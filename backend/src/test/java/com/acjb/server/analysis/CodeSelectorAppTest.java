package com.acjb.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.acjb.server.github.GithubClient;

/** 앱 저장소의 UI 코드 고르기(2026-11-11) — Flutter .dart를 읽고, 생성 코드·네이티브 폴더는 뺀다 */
class CodeSelectorAppTest {

    @Test
    void Flutter와_React_Native의_화면_코드만_고른다() {
        List<GithubClient.TreeFile> tree = List.of(
            new GithubClient.TreeFile("lib/main.dart", 100),
            new GithubClient.TreeFile("lib/screens/home_screen.dart", 100),
            new GithubClient.TreeFile("lib/models/user.g.dart", 100),
            new GithubClient.TreeFile("lib/models/user.freezed.dart", 100),
            new GithubClient.TreeFile("test/widget_test.dart", 100),
            new GithubClient.TreeFile(".dart_tool/flutter_build/x.dart", 100),
            new GithubClient.TreeFile("android/app/src/main/kotlin/MainActivity.kt", 100),
            new GithubClient.TreeFile("ios/Runner/AppDelegate.swift", 100),
            new GithubClient.TreeFile("src/screens/Home.tsx", 100),
            new GithubClient.TreeFile("android/app/build.gradle", 100));
        assertThat(CodeSelector.select(tree)).extracting(GithubClient.TreeFile::path)
            .containsExactly("lib/main.dart", "lib/screens/home_screen.dart", "src/screens/Home.tsx");
    }

    @Test
    void 코드가_대부분_Dart면_Flutter_노드_지시문을_쓴다() {
        DesignCodeMapper.CodeBundle flutter = new DesignCodeMapper.CodeBundle("o/r", "sha",
            Map.of("lib/main.dart", "", "lib/home.dart", "", "web/flutter_bootstrap.js", ""));
        DesignCodeMapper.CodeBundle rn = new DesignCodeMapper.CodeBundle("o/r", "sha", Map.of("App.tsx", "", "src/Home.tsx", ""));
        assertThat(ClaudeDesignCodeMapper.isFlutter(flutter)).isTrue();
        assertThat(ClaudeDesignCodeMapper.isFlutter(rn)).isFalse();
    }
}
