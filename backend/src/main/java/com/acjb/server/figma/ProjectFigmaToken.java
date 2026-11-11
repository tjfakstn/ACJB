package com.acjb.server.figma;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;

/**
 * 프로젝트에 소유자 Figma 토큰이 있으면 그 토큰으로 Figma를 부르게 한다(2026-11-11). 없으면 서버 토큰 그대로.
 * 비교 분석·첫 QA·Re-QA 작업과 Figma 미리보기가 작업 전체를 이것으로 감싼다
 */
@Component
public class ProjectFigmaToken {

    private final ProjectRepository projects;
    private final ProjectRunRepository runs;
    private final FigmaTokenCipher cipher;
    private final TransactionTemplate tx;

    public ProjectFigmaToken(ProjectRepository projects, ProjectRunRepository runs, FigmaTokenCipher cipher, TransactionTemplate tx) {
        this.projects = projects;
        this.runs = runs;
        this.cipher = cipher;
        this.tx = tx;
    }

    public String tokenOf(Long projectId) {
        if (projectId == null) {
            return null;
        }
        String enc = tx.execute(s -> projects.findById(projectId).map(p -> p.getFigmaTokenEnc()).orElse(null));
        return cipher.decrypt(enc);
    }

    public <T> T withProject(Long projectId, Supplier<T> body) {
        return FigmaTokenScope.with(tokenOf(projectId), body);
    }

    /** 비동기 실행(ProjectRun) — 실행이 없으면 서버 토큰으로 그대로 돌린다(작업이 알아서 끝낸다) */
    public void withRun(Long runId, Runnable body) {
        Long projectId = tx.execute(s -> runs.findById(runId).map(r -> r.getProject().getId()).orElse(null));
        FigmaTokenScope.run(tokenOf(projectId), body);
    }
}
