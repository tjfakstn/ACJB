package com.acjb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.acjb.server.domain.FigmaFrame;
import com.acjb.server.domain.JobStatus;
import com.acjb.server.domain.Member;
import com.acjb.server.domain.MemberRole;
import com.acjb.server.domain.MismatchSource;
import com.acjb.server.domain.Project;
import com.acjb.server.domain.ProjectMember;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.StyleProperty;
import com.acjb.server.domain.StyleScan;
import com.acjb.server.figma.FigmaApiException;
import com.acjb.server.figma.FigmaClient;
import com.acjb.server.repository.FigmaFrameRepository;
import com.acjb.server.repository.MemberRepository;
import com.acjb.server.repository.ProjectMemberRepository;
import com.acjb.server.repository.ProjectRepository;
import com.acjb.server.repository.StyleMismatchRepository;
import com.acjb.server.repository.StyleScanRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/**
 * StyleScanJob의 비교·저장(Figma·브라우저는 가짜). 작업이 트랜잭션을 직접 나눠 쓰므로 테스트도 트랜잭션 없이 돌고 끝에 지운다.
 * 마지막 테스트는 요청이 커밋된 뒤 실제로 백그라운드에서 끝나는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StyleScanJobTest {

    private static final String KEY = "GBZhzBWLOVirx9MMd5zddI";
    private static final String URL = "http://localhost:5173";

    @Autowired MockMvc mvc;
    @Autowired StyleScanJob job;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired FigmaFrameRepository frames;
    @Autowired StyleScanRepository scans;
    @Autowired StyleMismatchRepository mismatches;
    @MockitoBean FigmaClient figma;
    @MockitoBean ComputedStyleReader reader;

    Project project;
    Member owner;
    Member me;
    ProjectMember membership;
    FigmaFrame frame;

    @BeforeEach
    void setUp() {
        owner = members.save(new Member("owner-" + java.util.UUID.randomUUID() + "@test.dev", "소유자"));
        project = projects.save(new Project("scan-job", owner));
        me = members.save(new Member("scan-job@test.dev", "멤버"));
        membership = projectMembers.save(new ProjectMember(project, me, MemberRole.DESIGNER));
        frame = frames.save(new FigmaFrame(project, "84:759", KEY, "시작하기", "https://www.figma.com/design/x"));
        when(figma.isConfigured()).thenReturn(true);
        when(figma.nodeDocument(KEY, "84:759")).thenReturn(Optional.of(JsonMapper.builder().build().readTree(StyleRulesTest.SPIKE_FRAME)));
    }

    @AfterEach
    void cleanUp() {
        mismatches.findAll().stream().filter(m -> m.getProject().getId().equals(project.getId())).forEach(mismatches::delete);
        scans.findAll().stream().filter(s -> s.getProject().getId().equals(project.getId())).forEach(scans::delete);
        frames.delete(frame);
        projectMembers.delete(membership);
        projects.delete(project);
        members.delete(me);
        members.delete(owner);
    }

    private StyleScan newScan(String nodeId) {
        return scans.save(new StyleScan(project, "84:759", nodeId, URL, "button.primary", me));
    }

    private List<StyleMismatch> savedFor(StyleScan scan) {
        return mismatches.findAll().stream().filter(m -> m.getScan() != null && m.getScan().getId().equals(scan.getId())).toList();
    }

    private static ComputedStyleReader.ComputedStyle css(StyleValues.Rgb bg, Integer weight, Double radius) {
        return new ComputedStyleReader.ComputedStyle(bg, new StyleValues.Rgb(255, 255, 255), weight, radius);
    }

    @Test
    void 스파이크_불일치_버튼이면_세_property가_모두_차이로_저장된다() {
        // 실제 Button primary: bg-gray-800(oklch → 30,41,57), font-medium 500, rounded-md 6px
        when(reader.read(URL, "button.primary")).thenReturn(css(new StyleValues.Rgb(30, 41, 57), 500, 6.0));
        StyleScan scan = newScan("84:759");

        job.run(scan.getId());

        StyleScan done = scans.findById(scan.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(done.getMismatchCount()).isEqualTo(3);
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(savedFor(scan)).extracting(StyleMismatch::getProperty, StyleMismatch::getExpectedValue, StyleMismatch::getActualValue)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(StyleProperty.COLOR, "#0072DC", "#1E2939"),
                org.assertj.core.groups.Tuple.tuple(StyleProperty.FONT_WEIGHT, "600", "500"),
                org.assertj.core.groups.Tuple.tuple(StyleProperty.RADIUS, "8px", "6px"));
        assertThat(savedFor(scan)).allSatisfy(m -> {
            assertThat(m.getSource()).isEqualTo(MismatchSource.SCAN);
            assertThat(m.getFrameId()).isEqualTo("84:759");
            assertThat(m.getNodeId()).isEqualTo("84:759");
            assertThat(m.getSelector()).isEqualTo("button.primary");
            assertThat(m.getScreen()).isEqualTo(URL);
        });
    }

    @Test
    void 모두_허용_오차_안이면_done_0건() {
        when(reader.read(URL, "button.primary")).thenReturn(css(new StyleValues.Rgb(1, 113, 221), 600, 8.0));
        StyleScan scan = newScan("84:759");

        job.run(scan.getId());

        assertThat(scans.findById(scan.getId()).orElseThrow()).satisfies(s -> {
            assertThat(s.getStatus()).isEqualTo(JobStatus.DONE);
            assertThat(s.getMismatchCount()).isZero();
        });
        assertThat(savedFor(scan)).isEmpty();
    }

    @Test
    void 한쪽에서_추출하지_못한_property는_저장하지_않는다_N3() {
        // 화면 반경이 모서리마다 달라 null — radius는 비교하지 않고 color만 차이
        when(reader.read(URL, "button.primary")).thenReturn(css(new StyleValues.Rgb(30, 41, 57), 600, null));
        StyleScan scan = newScan("84:759");

        job.run(scan.getId());

        assertThat(scans.findById(scan.getId()).orElseThrow().getMismatchCount()).isEqualTo(1);
        assertThat(savedFor(scan)).singleElement().satisfies(m -> assertThat(m.getProperty()).isEqualTo(StyleProperty.COLOR));
    }

    @Test
    void TEXT_노드를_고르면_글자색과_비교한다() {
        when(reader.read(URL, "button.primary")).thenReturn(
            new ComputedStyleReader.ComputedStyle(new StyleValues.Rgb(0, 0, 0), new StyleValues.Rgb(250, 250, 250), 600, 8.0));
        StyleScan scan = newScan("84:760");

        job.run(scan.getId());

        // 글자 #FFFFFF vs #FAFAFA → 차이, 굵기 같음, TEXT 노드에는 반경이 없어 비교 안 함
        assertThat(savedFor(scan)).singleElement().satisfies(m -> {
            assertThat(m.getProperty()).isEqualTo(StyleProperty.COLOR);
            assertThat(m.getExpectedValue()).isEqualTo("#FFFFFF");
            assertThat(m.getActualValue()).isEqualTo("#FAFAFA");
        });
    }

    @Test
    void 비교할_값이_하나도_없으면_failed() {
        when(reader.read(URL, "button.primary")).thenReturn(new ComputedStyleReader.ComputedStyle(null, null, null, null));
        StyleScan scan = newScan("84:759");

        job.run(scan.getId());

        assertThat(scans.findById(scan.getId()).orElseThrow()).satisfies(s -> {
            assertThat(s.getStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(s.getErrorMessage()).contains("비교할 수 있는 값이 없습니다");
        });
        assertThat(savedFor(scan)).isEmpty();
    }

    @Test
    void node_id가_프레임_안에_없거나_화면_요소를_못_찾거나_Figma가_실패하면_failed() {
        StyleScan outside = newScan("99:1");
        job.run(outside.getId());
        assertThat(scans.findById(outside.getId()).orElseThrow().getErrorMessage()).contains("node_id가 frame_id 안에 없습니다");

        when(reader.read(anyString(), anyString())).thenThrow(new ScanFailedException("selector에 맞는 요소를 화면에서 찾지 못했습니다."));
        StyleScan noElement = newScan("84:759");
        job.run(noElement.getId());
        assertThat(scans.findById(noElement.getId()).orElseThrow()).satisfies(s -> {
            assertThat(s.getStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(s.getErrorMessage()).contains("selector");
        });

        when(figma.nodeDocument(KEY, "84:759")).thenThrow(new FigmaApiException("Figma 접근이 거부됐습니다(토큰 권한 또는 파일 공유 설정 확인)."));
        StyleScan figmaDown = newScan("84:759");
        job.run(figmaDown.getId());
        assertThat(scans.findById(figmaDown.getId()).orElseThrow().getErrorMessage()).contains("Figma 접근이 거부");

        assertThat(savedFor(outside)).isEmpty();
        assertThat(savedFor(noElement)).isEmpty();
        assertThat(savedFor(figmaDown)).isEmpty();
    }

    @Test
    void 커밋_뒤_백그라운드에서_scan이_끝난다() throws Exception {
        when(reader.read(URL, "button.primary")).thenReturn(css(new StyleValues.Rgb(30, 41, 57), 600, 8.0));

        mvc.perform(post("/api/v1/projects/" + project.getId() + "/style-mismatches/scan")
                .with(oauth2Login().attributes(a -> a.put("email", me.getEmail())))
                .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"frame_id\":\"84:759\",\"node_id\":\"84:759\",\"target_url\":\"" + URL + "\",\"selector\":\"button.primary\"}"))
            .andExpect(status().isAccepted());

        JobStatus last = null;
        for (int i = 0; i < 50 && last != JobStatus.DONE && last != JobStatus.FAILED; i++) {
            Thread.sleep(100);
            last = scans.findAll().stream().filter(s -> s.getProject().getId().equals(project.getId()))
                .map(StyleScan::getStatus).findFirst().orElse(null);
        }
        assertThat(last).isEqualTo(JobStatus.DONE);
        assertThat(mismatches.findAll()).filteredOn(m -> m.getProject().getId().equals(project.getId())).singleElement()
            .satisfies(m -> assertThat(m.getProperty()).isEqualTo(StyleProperty.COLOR));
    }
}
