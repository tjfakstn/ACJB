# 제품 스펙 (SDD) & 수용 기준 (AC)

> 스펙 주도 개발(SDD). 이 문서가 "무엇을 만들지"의 단일 진실 공급원이다.
> 역할: **무엇을** 만드는가의 정본. **왜**는 `PROBLEM.md`, 도메인 구조는 `ontology.yaml`, 상시 규칙은 `AGENTS.md`.

## 1. 문제

별도 QA 인력 없이 개발·디자인·기획을 겸하는 소규모 개발팀 구성원이, 디자인-구현 차이 확인과 이슈 재현을 근거화하지 못해 매 QA 라운드마다 같은 수작업을 반복한다. (상세: `PROBLEM.md`)

## 2. 타깃 사용자

전담 QA 인력이 없어 개발자·디자이너·기획자가 QA를 겸하는 소규모 개발팀(3~10인) 구성원. 비대상: 전담 QA 조직을 갖춘 대규모 엔터프라이즈, 모바일 네이티브 앱 QA, 성능/부하 테스트.

## 3. 핵심 기능 (한 문장)

배포된 화면을 Figma 디자인과 자동 대조해 차이를 검출 → 재현 정보를 포함한 QA 이슈로 구조화해 GitHub Issue로 연동 → 수정 후 동일 재현 절차로 재검증.

## 4. 범위

- **포함**: 구글 로그인 및 프로젝트(Figma 파일 + GitHub 저장소) 관리, Figma 프레임/디자인 토큰 동기화, 배포 화면과의 Design Diff 자동 검출, QA 이슈 생성(자동+수동) 및 GitHub Issue 연동, 재검증(Verification) 이력 관리, 시나리오 단위 테스트 데이터셋 프리셋 관리
- **비포함**: 결제/과금, 모바일 네이티브 앱 QA, 성능/부하 테스트, 리뷰·텍스트 기반 AI 자동 분류, 다국어 지원 (→ `ontology.yaml`의 `out_of_scope`와 동일)

## 5. 인터페이스

전체 명세: `docs/openapi.yaml`. 핵심 엔드포인트만 발췌.

- `GET /api/v1/health` → `{status: "ok"}` (구현됨)
- `GET /api/v1/auth/me` → `{authenticated, name, email, picture}` (구현됨)
- `POST /projects/{projectId}/design-diffs/scan` — body `{frame_id, target_url}` → `{scan_id, status}`
- `GET /projects/{projectId}/design-diffs` → `{items: DesignDiff[], total}`
- `POST /projects/{projectId}/design-diffs/{diffId}/issues` — body `{title?, priority}` → `QAIssue`
- `POST /projects/{projectId}/qa-issues/{issueId}/github-sync` — body `{repo}` → `{github_issue_url, github_issue_number}`
- `POST /projects/{projectId}/qa-issues/{issueId}/verifications` — body `{result, evidence_url?, notes?}` → `VerificationResult`

## 6. 수용 기준 (테스트로 검증 — Definition of Done)

각 AC는 EARS 문형 "[조건]일 때, QAting은 [동작]한다"로 쓰고, 조건 자리의 유형을 [ ]에 표시한다.

- **AC1** [이벤트 기반]: 사용자가 Design Diff 스캔을 요청하면, QAting은 Figma 프레임과 대상 URL의 대표 디자인 토큰(색상·폰트·간격)을 비교해 불일치 항목을 목록으로 반환한다.
- **AC2** [상시 적용]: QAting은 항상 Design Diff 결과 항목마다 Figma 기준값(`expected_value`)과 실제 값(`actual_value`)을 함께 제시한다 — 근거 없이 "다름"만 반환하지 않는다.
- **AC3** [이벤트 기반]: 사용자가 Design Diff로부터 QA 이슈를 생성하면, QAting은 재현 정보(대상 URL, 스크린샷)를 이슈에 자동으로 채운다 — 사람이 다시 입력하지 않는다. (↔ PROBLEM.md 로그1·로그8)
- **AC4** [이벤트 기반]: 사용자가 QA 이슈에 GitHub 연동을 요청하면, QAting은 등록된 재현 정보를 포함한 GitHub Issue를 생성하고 그 URL과 번호를 반환한다.
- **AC5** [이벤트 기반]: 사용자가 QA 이슈에 재검증 결과를 등록하면, QAting은 최초 등록된 재현 절차(`repro_steps`)를 함께 이력에 남겨 이후 조회 시 재입력 없이 확인할 수 있게 한다. (↔ PROBLEM.md 로그6·로그9)
- **AC6** [상시 적용]: QAting은 존재하지 않는 프레임/이슈 ID로 요청이 오면 항상 404와 표준 오류 형식(`{success:false, error:{code,message}}`)을 반환한다 — 임의로 데이터를 만들어내지 않는다.
- **AC7** [이벤트 기반]: `GET /api/v1/health` 요청이 오면, QAting은 200과 `{"status":"ok"}`를 반환한다 (구현됨).
- **AC8** [이벤트 기반]: `GET /api/v1/auth/me` 요청이 오면, QAting은 인증 여부와 무관하게 200으로 응답한다 — 미인증이면 `{authenticated:false}` (구현됨).

> AC1~AC6의 실행 가능한 골든 케이스: `tests/harness/golden_cases.yaml`. 백엔드 로직이 아직 없어 케이스는 입출력 형태만 먼저 고정해 둔 초안이며, 구현 시 이 케이스를 통과시키는 것을 목표로 한다.
