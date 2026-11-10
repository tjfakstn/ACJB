package com.acjb.server.liveqa;

import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.acjb.server.common.ApiException;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.Project;
import com.acjb.server.github.GithubApiException;
import com.acjb.server.github.GithubClient;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.scan.TargetUrlPolicy;

/**
 * 실시간 QA 워크스페이스가 띄울 배포 주소(AC29). 소유자가 저장한 주소 > 연결된 GitHub 저장소의 최근 성공 배포 주소 > 없음.
 * 외부 호출(GitHub·헤더 확인)은 트랜잭션 밖에서 한다 — DB 연결을 붙잡고 기다리지 않게.
 */
@Service
public class DeployTargetService {

    private static final Logger log = LoggerFactory.getLogger(DeployTargetService.class);

    public enum Source { MANUAL, GITHUB_DEPLOYMENT, NONE }

    /** 주소와 출처만 — 캡처·등록에서 쓴다 */
    public record Target(String url, Source source) {
    }

    private final ProjectRepository projects;
    private final GithubClient github;
    private final TargetUrlPolicy policy;
    private final EmbedChecker embedChecker;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DeployTargetService(ProjectRepository projects, GithubClient github, TargetUrlPolicy policy, EmbedChecker embedChecker,
                               TransactionTemplate tx, Clock clock) {
        this.projects = projects;
        this.github = github;
        this.policy = policy;
        this.embedChecker = embedChecker;
        this.tx = tx;
        this.clock = clock;
    }

    public DeployTargetView get(Long projectId) {
        Project project = load(projectId);
        Target target = resolve(project);
        EmbedChecker.Result embed = target.url() == null ? EmbedChecker.Result.unknown(null) : embedChecker.check(target.url());
        return DeployTargetView.of(target, project.getDeployUrl(), embed);
    }

    /** PUT deploy-url — 소유자만. null·빈 값이면 지운다 */
    public DeployTargetView update(Long projectId, String rawUrl, Member requester) {
        String url = rawUrl == null || rawUrl.isBlank() ? null : rawUrl.trim();
        if (url != null) {
            try {
                policy.check(url);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(e.getMessage().replace("target_url", "배포 주소"));
            }
        }
        tx.executeWithoutResult(status -> {
            Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
            if (!project.getOwner().getId().equals(requester.getId())) {
                throw ApiException.forbidden("배포 주소는 소유자만 바꿀 수 있습니다.");
            }
            project.changeDeployUrl(url, clock.instant());
        });
        return get(projectId);
    }

    /** 캡처·등록용 — 헤더 확인 없이 주소만 */
    public Target resolve(Long projectId) {
        return resolve(load(projectId));
    }

    private Target resolve(Project project) {
        if (project.getDeployUrl() != null) {
            return new Target(project.getDeployUrl(), Source.MANUAL);
        }
        String repo = project.getGithubRepo();
        if (repo != null && repo.contains("/")) {
            String[] ownerRepo = repo.split("/", 2);
            try {
                Optional<String> url = github.latestDeploymentUrl(ownerRepo[0], ownerRepo[1]);
                // GitHub가 준 주소도 같은 주소 제한을 거친다(N19) — 통과하지 못하면 없는 것으로 본다
                if (url.isPresent() && policy.allows(url.get())) {
                    return new Target(url.get(), Source.GITHUB_DEPLOYMENT);
                }
            } catch (GithubApiException e) {
                // 배포 기록을 못 읽어도 화면은 목록만으로 쓸 수 있다 — 오류로 막지 않는다
                log.info("GitHub 배포 기록 조회 실패: status={}", e.status());
            }
        }
        return new Target(null, Source.NONE);
    }

    private Project load(Long projectId) {
        return projects.findById(projectId).orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
    }
}
