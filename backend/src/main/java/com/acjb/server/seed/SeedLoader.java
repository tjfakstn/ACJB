package com.acjb.server.seed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;

/**
 * 서버가 뜰 때 시드 프로젝트와 멤버를 넣는다. 이미 있는 이메일·소속은 건드리지 않아서 여러 번 떠도 안전하다.
 * 멤버 관리 API가 없는 동안(v1) 프로젝트 멤버를 정하는 유일한 경로다.
 * 읽는 파일: members.local.csv(있으면) → 없으면 members.csv. 형식은 data/seed/members.csv 참고.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final ProjectRepository projects;
    private final MemberRepository members;
    private final ProjectMemberRepository projectMembers;

    @Value("${app.seed.dir:../data/seed}")
    private String seedDir;

    @Value("${app.seed.project-name:QAting}")
    private String projectName;

    public SeedLoader(ProjectRepository projects, MemberRepository members, ProjectMemberRepository projectMembers) {
        this.projects = projects;
        this.members = members;
        this.projectMembers = projectMembers;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        Path file = resolveSeedFile();
        if (file == null) {
            log.warn("시드 멤버 파일이 없어 건너뜀: {}/members(.local).csv", seedDir);
            return;
        }

        Project project = projects.findByName(projectName).orElseGet(() -> projects.save(new Project(projectName)));
        int added = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split(",", -1);
            if (cols.length != 3) {
                throw new IllegalStateException("시드 멤버 형식 오류(email,name,role): " + line);
            }
            String email = cols[0].trim().toLowerCase(Locale.ROOT);
            String name = cols[1].trim();
            MemberRole role = MemberRole.valueOf(cols[2].trim().toUpperCase(Locale.ROOT));

            Member member = members.findByEmail(email).orElseGet(() -> members.save(new Member(email, name, role)));
            if (!projectMembers.existsByProjectIdAndMemberId(project.getId(), member.getId())) {
                projectMembers.save(new ProjectMember(project, member));
                added++;
            }
        }
        log.info("시드 멤버 반영: 프로젝트 '{}', 새로 소속된 멤버 {}명 ({})", projectName, added, file.getFileName());
    }

    private Path resolveSeedFile() {
        for (String name : List.of("members.local.csv", "members.csv")) {
            Path candidate = Path.of(seedDir, name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
