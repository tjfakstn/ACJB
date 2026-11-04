package com.acjb.server.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.repository.FigmaFrameRepository;
import com.acjb.server.repository.FigmaSyncRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.StyleMismatchRepository;
import com.acjb.server.repository.StyleScanRepository;

/** db-schema.md에서 새로 추가한 4개 테이블(figma_syncs·figma_frames·style_scans·style_mismatches)이 저장·조회되는지만 확인한다 */
@SpringBootTest
@Transactional
class FigmaScanPersistenceTest {

    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired FigmaSyncRepository syncs;
    @Autowired FigmaFrameRepository frames;
    @Autowired StyleScanRepository scans;
    @Autowired StyleMismatchRepository mismatches;

    @Test
    void 동기화_작업은_queued로_저장되고_frame_ids를_JSON으로_읽는다() {
        Project project = projects.save(new Project("figma-test"));
        Member requester = members.save(new Member("sync@test.dev", "요청자", MemberRole.DESIGNER));

        FigmaSync sync = syncs.saveAndFlush(new FigmaSync(project, "FILEKEY", List.of("84:759", "84:760"), requester));
        FigmaSync all = syncs.saveAndFlush(new FigmaSync(project, "FILEKEY", null, requester));

        assertThat(syncs.findById(sync.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(syncs.findById(sync.getId()).orElseThrow().getFrameIds()).containsExactly("84:759", "84:760");
        assertThat(syncs.findById(all.getId()).orElseThrow().getFrameIds()).isNull();
    }

    @Test
    void 같은_프로젝트에_같은_frame_id는_두_번_저장되지_않는다() {
        Project project = projects.save(new Project("frame-test"));
        frames.saveAndFlush(new FigmaFrame(project, "84:759", "FILEKEY", "홈", "https://figma.com/a"));

        assertThat(frames.findByProjectIdAndFrameId(project.getId(), "84:759")).isPresent();
        assertThatThrownBy(() -> frames.saveAndFlush(new FigmaFrame(project, "84:759", "FILEKEY", "홈2", "https://figma.com/b")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void scan이_찾은_차이는_scan의_화면_정보를_그대로_가진다() {
        Project project = projects.save(new Project("scan-test"));
        Member requester = members.save(new Member("scan@test.dev", "요청자", MemberRole.DEVELOPER));
        StyleScan scan = scans.save(new StyleScan(project, "84:759", "84:800", "https://app.test/home", ".btn-primary", requester));

        StyleMismatch found = mismatches.saveAndFlush(StyleMismatch.fromScan(scan, StyleProperty.COLOR, "#1A73E8", "#1A73E9"));
        StyleMismatch manual = mismatches.saveAndFlush(StyleMismatch.manual(project, null, "설정 화면", StyleProperty.SHADOW, "0 2 4", "없음"));

        StyleMismatch loaded = mismatches.findById(found.getId()).orElseThrow();
        assertThat(loaded.getSource()).isEqualTo(MismatchSource.SCAN);
        assertThat(loaded.getScan().getId()).isEqualTo(scan.getId());
        assertThat(loaded.getScreen()).isEqualTo("https://app.test/home");
        assertThat(loaded.getSelector()).isEqualTo(".btn-primary");
        assertThat(mismatches.findById(manual.getId()).orElseThrow().getScan()).isNull();
        assertThat(mismatches.findByProjectIdOrderByCreatedAtDescIdDesc(project.getId()))
            .extracting(StyleMismatch::getId).containsExactly(manual.getId(), found.getId());
    }

    @Test
    void expected_value나_actual_value가_null이면_DB가_저장을_거부한다() {
        Project project = projects.save(new Project("ac2-test"));

        assertThatThrownBy(() -> mismatches.saveAndFlush(StyleMismatch.manual(project, null, null, StyleProperty.RADIUS, "8px", null)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
