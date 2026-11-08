package com.acjb.server.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.common.ErrorCodes;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.RunKind;
import com.acjb.server.domain.RunStatus;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.ProjectRunRepository;

import jakarta.persistence.EntityManager;

/**
 * AC26 — 소유자의 프로젝트 완전 삭제. FK에 CASCADE를 걸지 않았으므로 자식부터 한 트랜잭션으로 지운다(순서: docs/db-schema.md).
 * 멤버 계정(members)과 다른 프로젝트의 데이터는 건드리지 않는다(N15)
 */
@Service
public class ProjectDeletionService {

    /** 이 프로젝트 이슈에 딸린 표 — qa_issues보다 먼저 지운다 */
    private static final List<String> ISSUE_CHILDREN = List.of(
        "notifications", "verifications", "status_histories", "qa_issue_repro_steps", "qa_issue_screenshots");
    /** project_id로 바로 지우는 표 — 이 순서대로(style_mismatches는 style_scans를 참조) */
    private static final List<String> PROJECT_CHILDREN = List.of(
        "qa_issues", "style_mismatches", "style_scans", "figma_syncs", "figma_frames", "embeddings", "design_code_mappings", "project_runs",
        "invitations", "project_members");

    private final ProjectRepository projects;
    private final ProjectRunRepository runs;
    private final EntityManager em;

    public ProjectDeletionService(ProjectRepository projects, ProjectRunRepository runs, EntityManager em) {
        this.projects = projects;
        this.runs = runs;
        this.em = em;
    }

    @Transactional
    public void delete(Long projectId, Member requester) {
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
        if (!project.getOwner().getId().equals(requester.getId())) {
            throw ApiException.forbidden("프로젝트 삭제는 소유자만 할 수 있습니다.");
        }
        for (RunKind kind : RunKind.values()) {
            boolean running = runs.findFirstByProjectIdAndKindOrderByCreatedAtDescIdDesc(projectId, kind)
                .map(r -> r.getStatus() == RunStatus.QUEUED || r.getStatus() == RunStatus.RUNNING).orElse(false);
            if (running) {
                // 비동기 실행이 지운 프로젝트에 결과를 쓰려다 실패하지 않게 끝날 때까지 막는다
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOT_READY, "비교 분석이나 첫 QA가 진행 중이에요. 끝난 뒤에 삭제해 주세요.");
            }
        }
        em.flush();
        for (String table : ISSUE_CHILDREN) {
            em.createNativeQuery("DELETE c FROM " + table + " c JOIN qa_issues i ON c.qa_issue_id = i.id WHERE i.project_id = :pid")
                .setParameter("pid", projectId).executeUpdate();
        }
        for (String table : PROJECT_CHILDREN) {
            em.createNativeQuery("DELETE FROM " + table + " WHERE project_id = :pid").setParameter("pid", projectId).executeUpdate();
        }
        em.createNativeQuery("DELETE FROM projects WHERE id = :pid").setParameter("pid", projectId).executeUpdate();
        // 영속성 컨텍스트에 남은 엔티티가 지워진 행을 다시 쓰지 않게 비운다
        em.clear();
    }
}
