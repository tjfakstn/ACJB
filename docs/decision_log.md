# Decision Log

팀에서 확정한 결정들을 시간 순으로 기록합니다. "왜 이렇게 했는지"를 나중에 추적하기 위한 문서이며, 결과 값 자체(현재 스택/규칙 등)의 최신 상태는 [ssot.md](ssot.md) · [AGENTS.md](../AGENTS.md)를 참고하세요.

기록 형식: `## YYYY-MM-DD 결정 제목` → 결정 내용 / 배경(왜) / 결정자

---

## 2026-09-02 팀 결성 및 문제 정의

- **결정**: 아주대 소프트웨어학과 26-2 캡스톤디자인 '안캡잘부' 팀 결성. Figma-GitHub 기반 QA 관리 서비스로 방향 설정
- **왜**: 개발 과정에서 반복되는 QA 작업과 디자이너·개발자·PM 간 커뮤니케이션 문제가 팀 공통 관심사였음
- **결정자**: 팀 전체

## 2026-09-02 ~ 09-08 역할 분담 확정

- **결정**: 오단비 = 기획·개발 보조, 설만수 = 개발, 최유정 = 디자인
- **왜**: 3인 팀 규모에 맞춰 명확한 담당 영역 구분 필요
- **결정자**: 팀 전체

## 2026-09-08 GitHub 컨벤션 확정

- **결정**:
  - 브랜치: `main`(직접 커밋 금지) / `develop` / `feat·fix·docs·refactor·chore/<이슈번호>-<설명>`
  - 커밋: Conventional Commits (`feat` `fix` `docs` `refactor` `test` `chore` `style` `perf`)
  - PR/이슈 템플릿 3종(버그·기능요청·QA이슈) 도입
- **왜**: 초기 단계부터 협업 규칙을 명문화해 두면 팀원이 늘거나 작업이 많아져도 흔들리지 않음. 특히 QA 이슈 템플릿은 우리 서비스의 핵심 도메인(Figma-구현 차이)에 맞춰 스크린샷 비교 항목을 포함
- **보류**: 라벨 체계, 브랜치 보호 규칙(GitHub 실제 설정)은 이슈가 쌓이기 시작하면 다시 논의하기로 함
- **결정자**: 팀 전체 (설만수 문서화)

## 2026-09-09 설문 인터뷰 기반 MVP 방향 검증

- **결정**: MVP 핵심을 ① Figma-실제 구현 스타일 값 자동 대조, ② 발견된 diff를 구조화된 GitHub 이슈로 즉시 전환하는 흐름으로 우선순위화
- **왜**: 개발자 실제 QA 경험 인터뷰(4명) 결과, 응답자 전원이 "디자인-구현 차이를 사람이 눈으로/캡처해서 대조"하고 있었고, 재현정보 부족으로 인한 재질문이 반복적으로 시간을 소모하는 것으로 확인됨. 회귀 확인 자동화는 페인포인트가 가장 크지만 스코프가 커서(E2E 테스트 영역) 캡스톤 MVP 범위에서 제외
- **결정자**: 설만수 (인터뷰 분석), 팀 확정 필요

## 2026-09-09 저장소 표준 구조 골격 생성

- **결정**: `config/ data/ docs/ evals/ src/ tests/` 구조 도입. `src/qating/`에 QAIssue·DesignDiff·VerificationResult 스키마 및 프롬프트 골격 추가
- **왜**: AI 기반 자동 검증(Figma-구현 diff 판정, QA 이슈 생성) 파이프라인을 평가 가능한 형태로 구축하기 위한 표준 레이아웃 필요
- **결정자**: 오단비 (푸시)

## 2026-09-09 프론트엔드 스택 확정: React + Vite + TypeScript

- **결정**: Frontend = React + Vite + TypeScript (Tailwind CSS 예정), Backend = Spring(Spring Boot)
- **왜**:
  - 핵심 기능(디자인-구현 diff 시각화)에 필요한 이미지/캔버스 비교 라이브러리가 React 생태계에 풍부함
  - Spring이 REST API를 전담하므로 프론트는 SSR 불필요 → Next.js보다 가벼운 Vite SPA가 적합
  - AI 코딩 도구(Claude 등)의 React 학습량이 많아 개발 보조 인력의 생산성에 유리
- **결정자**: 설만수 제안, 팀 확정 필요 (DB/AI/인프라 스택은 TODO)

## 2026-09-09 브랜치 전략 개정: 영역별 브랜치 (main / dev / server / client)

- **결정**: 기존 `develop` + 이슈번호 기반 `feat/fix/...` 브랜치 방식을 폐기하고, `main`(절대 미작업) / `dev`(통합) / `server`(백엔드) / `client`(프론트엔드) 4개 브랜치 체계로 전환. `server`/`client` → `dev`는 셀프 머지 허용, `dev` → `main`은 배포 시점에만
- **왜**: 실제 개발 인력이 설만수 혼자인 비중이 커서, 이슈마다 브랜치를 새로 파고 리뷰어를 구하는 기존 방식이 오버헤드였음. 담당 영역(백/프론트)별로 브랜치를 나누고 `main`은 배포 게이트로만 쓰는 게 1인 개발 흐름에 더 맞음
- **결정자**: 설만수

## 2026-09-09 분야별 작업 흐름 구체화: 하위 분기 → 영역 브랜치 병합 → dev 통합

- **결정**: 백엔드 작업은 `server`에서 `server/<작업 설명>`으로 분기해서 진행 후 `server`로 병합, `server`가 어느 정도 쌓이면 `dev`로 통합. 프론트도 동일 패턴(`client/<작업 설명>` → `client` → `dev`)
- **왜**: `server`/`client`에 바로 커밋만 하면 작업 단위가 섞여서 되돌리기 어려움. 작업 단위별로 하위 브랜치를 나누되, 매번 `dev`까지 왕복하지 않고 영역 브랜치에서 먼저 모았다가 통합하는 게 1인 개발 흐름에 맞음
- **결정자**: 설만수

## 2026-09-09 브랜치 네이밍: feat/fix/docs 접두사는 유지, 영역 브랜치 하위로 이동

- **결정**: 기존 타입 접두사(`feat` `fix` `docs` `refactor` `chore`)는 폐기하지 않고 `server/feat-<설명>` `client/fix-<설명>`처럼 영역 브랜치 하위에서 계속 사용. `server`/`client` → 각 영역 브랜치로 머지 → 최종적으로 `dev`로 머지하는 3단계 흐름으로 확정
- **추가 규칙**: `docs/decision_log.md`, `docs/ssot.md`, `AGENTS.md`, `CONTRIBUTING.md`처럼 모든 브랜치에 공통 적용돼야 하는 문서는 `server`/`client`가 아니라 `dev`에서 직접 작업한다 (지금 이 항목도 `dev` 브랜치에서 작성 중)
- **왜**: 타입 접두사는 커밋 히스토리 가독성에 여전히 유용해서 유지. 공통 문서를 영역 브랜치에서 각각 건드리면 server/client가 서로 다른 버전을 갖게 되어 dev 통합 시 충돌 위험이 커짐 → 공통 문서의 단일 진행 지점을 dev로 고정
- **참고**: 이 규칙을 적용하면 `server`/`client`에 남아있는 문서 사본은 최신이 아닐 수 있음 — server/client → dev 병합 시점에 문서 충돌이 날 수 있으니, 필요하면 dev의 최신 문서를 server/client로도 가끔 가져올 것
- **결정자**: 설만수

## 2026-09-14 API 명세 초안(v0.1) 작성

- **결정**: 지금까지 확정된 MVP 흐름(Figma-구현 diff → QA Issue → GitHub 연동 → 재검증)을 기준으로 REST API 초안을 [docs/api_spec.md](api_spec.md)에 작성. 함께 `src/qating/schemas/`의 `DesignDiff` `QAIssue` `VerificationResult` 빈 스키마도 초안 필드로 채움
- **왜**: 백엔드(Spring) 작업을 `server` 브랜치에서 시작하기 전에, 프론트/백엔드가 공유할 데이터 모델과 엔드포인트 형태를 먼저 합의해두기 위함. 인증/DB 스택이 아직 TODO라 세부는 계속 바뀔 여지 있음(v0.1)
- **결정자**: 설만수 초안, 팀 확정 필요

## 2026-09-23 PROBLEM.md / SPEC.md / AGENTS.md를 정식 산출물 형식으로 재작성

- **결정**: 강의 지침("문제 정의, 스펙(SDD), AGENTS 작성 지침")에 맞춰 세 문서를 재작성. 인터뷰 응답 4건에 로그 번호(로그1~14)를 매겨 `docs/research/interviews.md`에 정리하고, `PROBLEM.md`의 증거·반증조건이 전부 로그 번호로 역추적되게 함. `docs/ontology.yaml`을 실제 도메인(Project/FigmaFrame/DesignDiff/QAIssue/TestDataset/VerificationResult)으로 채우고, `AGENTS.md`는 7절 구조(제품맥락/용어집/절대규칙/금지사항/코딩컨벤션/완료의정의/운영정보)로 재구성 — 기존 팀·Git 규칙은 삭제하지 않고 7절(운영정보) 아래로 편입. `tests/harness/golden_cases.yaml`에 AC1~AC8 대응 골든 케이스 초안 7건 추가. `CLAUDE.md`를 신설해 `@AGENTS.md` 임포트만 두는 구조로 분리
- **왜**: 이전 `PROBLEM.md`/`SPEC.md`는 빈 골격이었고, `AGENTS.md`는 팀 운영 규칙 위주라 "왜/무엇을/상시규칙"의 역할 구분이 없었음. 증거 없는 문제 정의, 테스트 불가능한 AC는 검증할 수 없다는 지침에 따라 전부 인터뷰 로그·실제 스키마에 근거하도록 다시 씀
- **결정자**: 설만수

## 2026-09-28 오단비의 PROBLEM/SPEC/ontology/openapi 재작성(v0.2)을 채택

- **결정**: 오단비가 인터뷰를 4건→12건(+관찰 1건)으로 확장하고 `main`에 직접 커밋한 재작성본을 그대로 채택. `PROBLEM.md`의 문제 중심이 "반복 수작업(화면 대조·데이터 세팅·재확인)"에서 "**전달·확인의 왕복**(재질문·알림 누락·재확인 반복)"으로 바뀌었고, 그에 따라 `SPEC.md`/`ontology.yaml`/`openapi.yaml`도 전면 개편됨:
  - **GitHub Issues 연동을 v1에서 제외** (v2 후보) — 응답자들이 쓰는 트래커가 Jira/Notion/엑셀로 제각각이라(interviews.md 로그2,8,11,12), openapi.yaml의 `github-sync` 엔드포인트는 `deprecated: true`로 설계 기록만 남김
  - **TestDataset(백엔드 테스트 데이터셋 관리) 제외** — ontology.yaml 범위 밖으로 명시
  - `DesignDiff` → `StyleMismatch`로 개명, 자동 검출(scan)의 MVP 포함 여부는 향후 스파이크 결과로 결정 (지금은 수동 입력 경로만 AC로 확정)
  - `Notification`(등록 시 담당자·조치완료 시 등록자에게 알림), `IssueReportContext`(자유서술 파싱), `status_history` 신규 도입
  - AC1이 "디자인 토큰 자동 검출"에서 "**재현정보 미비 시 등록 거부**"로 바뀜(검증 중심)
  - 이에 맞춰 `src/qating/schemas/`를 갱신(`DesignDiff`→`StyleMismatch`, `VerificationResult`→`Verification`, `Notification`/`IssueReportContext` 신규), `AGENTS.md` 2·3절(용어집·절대규칙)을 새 AC 번호에 맞게 재작성, `tests/harness/golden_cases.yaml`을 새 AC1~10 기준으로 다시 작성, 중복·구버전이 된 `docs/api_spec.md`는 삭제(SPEC.md 5절 + openapi.yaml이 대체)
- **왜**: 응답자 수가 3배로 늘면서 반증 조건("1차 사용자 중 과반이 겪는가")을 더 신뢰할 수 있는 표본으로 재판정할 수 있게 됐고, 그 결과 원래 가설(자동 대조가 핵심)보다 전달·확인 왕복이 더 우선순위 높은 문제로 확인됨. 근거가 바뀌면 스펙을 바꾸는 것이 이 프로젝트가 따르는 원칙(강의 4 지침)과 일치해 채택함
- **경고**: 이 결정은 `main`에 직접 커밋된 것을 사후 승인한 것임 — `main` 직접 커밋 금지 규칙(AGENTS.md 4절)을 이번엔 지키지 못했음. 팀 논의 후 반영이었는지 확인 필요, 다음부터는 `server`/`client` 브랜치로 작업하도록 팀 내 재공지 필요
- **결정자**: 오단비 작성, 설만수 검토 후 dev/server/client 반영

## 2026-09-30 강의 3/4 체크리스트 2차 감사 반영

- **결정**: 오단비의 2차 감사(강의 3/4 체크리스트 기준, 항목별 ✅/🟡/🔴 판정)에서 나온 지적을 전부 반영:
  - `AGENTS.md`: GitHub 흔적 3곳(5절 코딩 컨벤션, 7.2 기술 스택, 7.4 환경 변수) 제거, 절대 규칙 6(시크릿)을 AC 대응이 없다는 이유로 4절 금지 사항으로 이동, 절대 규칙 3을 AC3 전체(PATCH로 담당자 신규 지정 시 알림 포함)에 맞게 재작성, 용어집에 `Member`(role) 클래스·QAIssue의 `screenshots`/`figma_link`·FigmaFrame/Screen 필드명(`name`/`frame_url`/`entry_condition`) 추가, `IssueReportContext.screen`과 `QAIssue.screen_location`이 다른 필드라는 혼동 방지 메모 추가, 5절에 "LLM 구조화 출력은 수신 후 스키마로 검증" 규칙 추가
  - `README.md`: "Figma와 GitHub를 연결해 AI가 자동 검증"이라는 옛 문구를 현재 SPEC.md(재현 정보 등록 → 알림 → 재검증)에 맞게 재작성
  - `docs/spikes/2026-09-28_style-mismatch-auto-extraction.md`: 성공 조건·방법·결과 표에서 제각각이던 property 목록(color/font_weight/font_family/spacing 등)을 `color`/`font_weight`/`radius` 3개로 통일, 건별 판정 규칙(정규화·허용 오차) 표 추가, "이 가정을 먼저 검증하는 이유" 단락 추가, 평가 데이터 출처(Figma 파일 키·프론트 커밋)는 실행 시 채우는 자리로 명시. 실험 결과·판정은 여전히 TBD(다음 세션에 실제 실행)
  - 스키마: `QAIssue.description`/`screen_location`에 `minLength: 1`, `repro_steps`에 `minItems: 1` 추가(AC1의 "비어 있으면 거부"를 스키마 레벨에서도 보장), `StyleMismatch.expected_value`/`actual_value`에 `minLength: 1` 추가(AC2), `IssueReportContext`에 5개 필드 전부 `required` 추가(키 누락과 값 null을 구분)
  - `parse_query.md`: platform_scope 규칙에서 "플랫폼 구분 없이 말함"과 "언급 자체가 없음"이 같은 결과(null)로 뭉뚱그려지던 모호성을 조건 분기로 명확화, `<issue_text>` 태그 구분자 도입(프롬프트 인젝션 방어 규칙 포함), few-shot 예시 2개 추가, 실행 모델(`claude-sonnet-5`)·실행 일시 기록, 실패 모드 점검표에 "모호"/"범위 위반" 유형 추가해 지침이 요구하는 5유형을 모두 커버(7개 입력으로 확장)
  - `tests/harness/golden_cases.yaml`: `qi-001`에 `error_message_contains: "screen_location"` 추가(빠진 항목 이름이 실제로 반환되는지 검증), `pc-001`에 `context.screen`이 null인지 확인하는 키 추가(AC8의 핵심인 "지어내지 않음" 검증), AC3에 `nt-003`(PATCH로 담당자 신규 지정 시 알림)·`nt-004`(담당자 없으면 알림 0건) 추가, AC6에 `st-003`(PATCH로 resolved 직접 요청 → 409) 추가
- **작동 확인**: 이 대화 세션에서 시스템이 `CLAUDE.md`와 `AGENTS.md` 내용을 자동으로 로드해 보여줌 — `@AGENTS.md` 임포트 정상 확인. 용어집 대표어(`screen_location`, `platform_scope`, `repro_steps` 등)를 이번 세션 전체 응답과 수정본에서 변형 없이 그대로 사용했음을 직접 확인(예: AGENTS.md 용어집 수정 시에도 ontology.yaml의 필드명을 그대로 복사)
- **왜**: 1차 감사(2026-09-28, `parse_query.md`/스파이크 문서/`IssueReportContext.schema.json` 갭)에 이어, 강의 3/4 체크리스트를 항목별로 재대조한 2차 감사가 더 세밀한 불일치(AC-규칙 매핑 누락, 스키마가 빈 값을 막지 못하는 문제, 프롬프트 규칙의 모호성 등)를 찾아냈고, 지침이 "규칙마다 AC 번호 대응, 하나라도 비면 검증되지 않음"을 명시적으로 요구하기 때문
- **결정자**: 오단비 감사, 설만수 반영

## 2026-10-11 재검증 fail 알림·권한 AC 추가, 금지 기준(SPEC 6.1) 신설

- **결정**:
  - `SPEC.md`에 AC11(재검증 `fail` → 담당자에게 `Notification`(`recheck_failed`) 1건), AC12(`/projects/{projectId}/**`는 그 프로젝트 멤버만, 미인증 401·비멤버 403), AC13(`fixed` 처리는 담당자만·재검증 등록은 등록자만, 권한 검사가 전이 검사보다 먼저, 비멤버 담당자 지정은 400) 추가. 기존 AC1~AC10 번호는 골든 케이스가 참조하므로 유지
  - `SPEC.md` 6.1절 금지 기준 N1~N13 신설 — 구현 중 에이전트가 값을 지어내 채우거나, 부분 저장하거나, 정해지지 않은 상태·알림·엔드포인트를 만드는 이탈을 막는 최소 목록. `AGENTS.md` 4절 금지 사항 8번으로 연결
  - 연동 갱신: `Notification.schema.json`·`ontology.yaml`의 `event`에 `recheck_failed` 추가, `AGENTS.md` 용어집·절대 규칙 3·6·7, `openapi.yaml`에 `Forbidden`(403) 응답과 PATCH/verifications 설명
  - `tests/harness/golden_cases.yaml`: AC11(`nt-005`·`nt-006`), AC12(`au-001`·`au-002`), AC13(`au-003`~`au-007`), 금지 기준(`ng-001`~`ng-010`) 추가. 권한 규칙 때문에 요청자가 정해져야 하는 기존 케이스(`nt-002`·`st-001`·`st-002`·`vf-002`·`vf-003`)에 `actor`만 추가 — 단언문·기대값은 그대로
- **왜**: 재검증 `fail` 시 담당자에게 알릴 이벤트가 없어 핵심 흐름(등록 → 알림 → 재검증)의 되돌아가는 쪽이 비어 있었고(알림 누락 페인 재발), 누가 `fixed` 처리·재검증을 할 수 있는지가 정의돼 있지 않았음. 또 AC는 "해야 하는 것"만 적혀 있어 구현 중 에이전트가 범위를 넓히는 것을 막을 기준이 없었음
- **열린 질문**: 등록자 부재 시 대리 재검증 허용 여부, 담당자 없는 이슈의 `fixed` 처리 금지가 맞는지, 프로젝트 멤버 관리 API 부재(당분간 시드 데이터로 넣을지) — 오단비 확인 필요
- **결정자**: 설만수 (골든 케이스 추가 승인 포함)

## 2026-10-28 scan v1 포함, AC13 권한 유지, DB는 MySQL

- **결정**:
  - **scan(Figma 시안과 배포된 웹 화면의 값을 자동 추출·비교)을 v1에 포함한다.** 범위는 웹 화면, 단일 컴포넌트, `color`·`font_weight`·`radius` 3개 property로 한정하고, 그 밖의 property·앱 화면은 사람이 입력한다. `SPEC.md` 4·5절에 반영, 6.1절 N4는 "만들지 않는다"에서 "범위만 처리한다"로, N3는 사람 입력 경로와 scan 경로를 구분하도록 고침. 골든 케이스 `ng-003`은 "scan 엔드포인트 404"에서 "유효한 요청이면 202와 `scan_id`"로 갱신(설만수 승인)
  - **AC13은 기존 규칙을 유지한다.** `fixed` 처리는 담당자만(담당자가 없으면 불가), 재검증 등록은 등록자만. 10-11에 열어 둔 질문 3개는 이렇게 닫는다: 대리 재검증은 v1 제외, 담당자 없는 이슈의 `fixed` 처리 불가 유지, 프로젝트 멤버는 시드 데이터로 시작. (한때 "프로젝트 멤버 누구나 `fixed`·재검증 가능"을 검토했으나, 담당자(`assigned_to`, 고치는 사람)와 등록자(`reported_by`, 등록한 사람)가 다른 개념이라는 점을 확인한 뒤 취소)
  - **DB는 개발 단계부터 MySQL을 쓴다.** 개발용 H2를 거쳐 배포 때 바꾸는 방식은 쓰지 않는다. 접속 정보는 `.env`에, 키는 `.env.example`에 둔다
- **왜**:
  - scan: 스파이크(2026-10-07)에서 성공 조건 3개가 모두 충족돼(6/6) 포기할 근거가 나오지 않았다. 다만 검증된 범위가 위 한계뿐이라 포함 범위를 같은 한계로 묶었다. 일치 샘플 3건이 직접 만든 것이라 실제 화면에서의 오탐률은 아직 모른다 → scan 구현 시 측정
  - AC13: 역할 분리를 유지해야 인터뷰의 "처음 확인한 사람이 다시 확인한다" 흐름이 지켜지고, 대리 재검증과 멤버 관리 API는 v1 범위를 키운다
  - MySQL: 배포 시 DB를 바꾸면 쿼리·방언 차이를 한 번 더 검증해야 해서 번거롭다. 처음부터 같은 DB로 간다
- **남은 일**: 로컬 MySQL 실행 방식(예: docker compose)과 `./gradlew test`용 테스트 DB 분리 방법 확정, scan 범위 밖 property를 거부하는 골든 케이스 추가(사람 승인 필요), 이 결정에 대한 오단비 확인(제품 스펙 담당)
- **결정자**: 설만수

## 2026-10-28 구현에 빠져 있던 API 명세 보강 (알림 조회, 프로젝트·멤버 조회, CSRF, scan 동작 방식)

- **결정**: 구현하려면 명세에 있어야 하는 엔드포인트·규칙을 `SPEC.md`와 `openapi.yaml`에 추가했다.
  - **알림 조회**: `GET /projects/{projectId}/notifications` — 내게 온 앱 내 알림만, `created_at desc`. 읽음 처리·이메일·Slack은 v2 (AC14 신설)
  - **프로젝트·멤버 조회**: `GET /projects`, `GET /projects/{projectId}/members` — 내가 속한 프로젝트와 담당자 선택용 멤버 목록. 멤버를 가리키는 값(`assigned_to`·`reported_by`·`verified_by`)은 멤버 id로 통일 (AC15 신설)
  - **인증·CSRF**: `openapi.yaml`의 `X-API-Key`를 실제 방식인 구글 세션 쿠키로 정정하고, POST·PATCH는 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더에 실어 보내도록 명시
  - **PATCH 규칙**: `assigned_to`를 null로 비우는 요청과 `status`·`assigned_to` 외 필드는 400
  - **scan 동작 방식(제안)**: 요청을 `{frame_id, node_id, target_url, selector}`로 확장. 서버가 Figma REST API로 `node_id` 값을 읽고(토큰은 서버 환경변수), 헤드리스 브라우저로 `target_url`의 `selector` 요소 computed style을 읽어 비교. SPEC 5절에 누락돼 있던 `figma/sync`·`figma/frames`·`style-mismatches` 조회·이슈 상세 조회도 나열
- **왜**: 알림은 생성만 정의돼 있고 사용자가 받을 경로가 없어 핵심 가치("알림으로 왕복을 줄인다")가 비어 있었다. 프론트가 `projectId`와 담당자 후보를 알아낼 방법이 없었고, `assigned_to`가 무엇을 가리키는지 정해지지 않았다. 세션 쿠키 인증에서는 CSRF 보호가 켜져 있어 토큰 규칙 없이는 POST·PATCH가 403이 난다. scan은 요청만으로 Figma 노드와 화면 요소를 짝지을 수 없었고, 스파이크가 개발자 도구로 값을 읽은 것을 서버가 자동으로 하려면 헤드리스 브라우저가 필요하다
- **남은 일**: AC14·AC15 골든 케이스 추가(사람 승인 필요), scan 동작 방식(헤드리스 브라우저 사용, Figma 토큰 서버 보관)에 대한 오단비 확인
- **결정자**: 설만수 (scan 동작 방식은 제안 상태)
