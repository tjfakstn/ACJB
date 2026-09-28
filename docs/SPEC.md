# 제품 스펙 (SDD) & 수용 기준 (AC)

> 스펙 주도 개발(SDD). 이 문서가 "무엇을 만들지"의 단일 진실 공급원이다.
> 역할: **무엇을** 만드는가의 정본. **왜**는 `PROBLEM.md`, 도메인 구조와 어휘는 `ontology.yaml`, 상시 규칙은 `AGENTS.md`.
> 필드·값 이름은 `ontology.yaml`의 대표어를 그대로 쓴다.

## 1. 문제

QA 전담 인력 없이 QA를 나눠 맡는 제품팀의 구성원이, 발견한 차이와 이슈를 재현 가능한 근거와 함께 전달하고, 조치 여부를 바로 알고, 같은 절차로 다시 확인하지 못해, QA 라운드마다 재질문·목록 확인·재현 절차 재입력의 왕복을 반복한다. (상세: `PROBLEM.md`)

## 2. 타깃 사용자

QA 전담 인력 없이 QA를 나눠 맡는 제품팀의 개발자와 디자이너. 이슈를 등록하는 쪽(디자이너·기획자)과 받아서 고치는 쪽(개발자) 모두.
비대상: 테스트 데이터 세팅·초기화가 주된 페인인 백엔드 QA. 기획자/PM과 QA 전담 조직은 검증 전(`PROBLEM.md` 2절).

## 3. 핵심 기능 (한 문장)

차이·이슈를 **재현 정보와 함께 등록** → 조치가 끝나면 **등록자에게 바로 알림** → 등록할 때의 **재현 절차를 그대로 불러와 재검증**하고 닫는다.

## 4. 범위

- **포함**
  - QA 이슈 등록 — 재현 정보(`screen_location`, `repro_steps`, `platform_scope`, `Environment`) 필수, 스크린샷·Figma 링크 첨부
  - 자유서술 이슈 텍스트 → `IssueReportContext` 파싱과 빠진 항목 되묻기 (강의 3 구조화 출력)
  - 스타일 차이 기록 — `StyleMismatch`(`expected_value`, `actual_value`). 값을 자동으로 추출할지 사람이 입력할지는 스파이크(`docs/spikes/`) 결과로 정한다
  - 이슈 4단계 상태(`needs_check` → `fixed` → `resolved` / `needs_recheck`)와 변경 이력(`status_history`)
  - 상태 알림(`Notification`) — 이슈 등록 시 담당자에게, 조치 완료 시 등록자에게
  - 재검증 기록(`Verification`) — 최초 재현 절차 재사용, 결과에 따라 상태 자동 전환
  - 구글 로그인 (구현됨)
- **비포함** (`ontology.yaml`의 범위 밖과 동일)
  - 테스트 데이터 세팅·초기화
  - E2E 회귀 테스트 자동화 — 재확인은 사람이 하고, QAting은 기록과 절차 재사용만 맡는다
  - 디자인 변경 전파·토큰 동기화 (v2 후보)
  - 예외 상황 UI 재제작
  - 외부 이슈 트래커(GitHub Issues·Jira 등) 연동 — 응답자들이 쓰는 도구가 Jira·Notion·엑셀로 제각각이라(로그 2, 8, 11, 12) v1에서는 제외 (v2 후보)

## 5. 인터페이스

전체 명세: `docs/openapi.yaml` v0.2 (base `/api/v1`). 핵심만 발췌. 두 문서가 어긋나면 이 스펙이 정본이다.

- `GET /health` → `{status: "ok"}` **(구현됨)**
- `GET /auth/me` → `{authenticated, name, email, picture}` / 미인증 `{authenticated: false}` **(구현됨)**
- `POST /projects/{projectId}/qa-issues/parse` — body `{text}` → `{context: IssueReportContext, missing: string[]}` *(신규)*
- `POST /projects/{projectId}/qa-issues` — body `{description, screen_location, repro_steps[], platform_scope, environment{platform, device, browser, deploy_stage}, screenshots[], figma_link?, style_mismatch?{property, expected_value, actual_value}, assigned_to?}` → `QAIssue` (`status: "needs_check"`, `reported_by`는 로그인 사용자)
- `POST /projects/{projectId}/style-mismatches/{mismatchId}/issues` — 기록된 `StyleMismatch`에서 이슈 생성 (자동 비교 경로 — 스파이크 결과에 따라 포함)
- `PATCH /projects/{projectId}/qa-issues/{issueId}` — body `{status: "fixed", assigned_to?}` → `QAIssue` (허용 전이만, AC6)
- `GET /projects/{projectId}/qa-issues?status=fixed` — 재확인 대기 이슈 목록
- `POST /projects/{projectId}/qa-issues/{issueId}/verifications` — body `{result: "pass"|"fail", scope?}` → `{verification: Verification, issue_status, repro_steps[]}` (`verified_by`는 로그인 사용자)
- `GET /projects/{projectId}/qa-issues/{issueId}/verifications` → `Verification[]`

## 6. 수용 기준 (테스트로 검증 — Definition of Done)

### 6. 수용 기준 (테스트로 검증 — Definition of Done)

각 AC는 EARS 문형 "[조건]일 때, QAting은 [동작]한다"로 쓰고, 조건 자리의 유형을 [ ]에 표시한다. 괄호 안은 근거가 된 문제 정의서의 증거다.

- **AC1** [이벤트 기반]: 사용자가 QA 이슈를 등록하면, QAting은 `description`·`screen_location`·`repro_steps`·`platform_scope` 중 하나라도 비어 있을 때 등록을 거부하고(400) 빠진 항목 이름을 반환한다. (재현 정보 부족 → 재질문과 대기)
- **AC2** [상시 적용]: QAting은 항상 `StyleMismatch`를 `expected_value`와 `actual_value`가 **둘 다** 있을 때만 저장한다. 한쪽만 있으면 거부한다(400). (디자인-구현 차이를 사람이 눈으로 대조)
- **AC3** [이벤트 기반]: QA 이슈가 등록될 때 `assigned_to`가 지정돼 있거나, 이후 PATCH로 `assigned_to`가 새로 지정되거나 바뀌면, QAting은 그 담당자에게 `Notification`(`event: issue_created`)을 정확히 1건 생성한다. 담당자가 없으면 생성하지 않는다. (등록·조치 알림 누락)
- **AC4** [이벤트 기반]: 이슈 상태가 `fixed`로 바뀌면, QAting은 그 이슈의 등록자(`reported_by`)에게 `Notification`(`event: fixed`)을 정확히 1건 생성한다. (등록·조치 알림 누락 → 목록을 직접 확인)
- **AC5** [이벤트 기반]: 사용자가 `fixed` 상태의 이슈를 조회하면(`GET /qa-issues/{issueId}`), QAting은 등록 때 기록된 `repro_steps`·`screen_location`·`Environment`를 함께 반환한다. 재검증을 등록할 때는 재현 절차를 입력받지 않는다. (수정 후 재확인을 처음과 같은 과정으로 반복)
- **AC6** [상시 적용]: QAting은 항상 정해진 상태 전이만 허용한다. `needs_check → fixed`와 `needs_recheck → fixed`는 PATCH로만, `fixed → resolved`(pass)와 `fixed → needs_recheck`(fail)는 재검증 등록으로만 일어난다. 그 밖의 전이 요청은 거부한다(409). 허용된 모든 상태 변경은 `status_history`에 변경 시각과 변경자를 남긴다. (상태 수동 표기, 관찰 1)
- **AC7** [이벤트 기반]: 재검증이 등록되면, QAting은 `result`가 `pass`일 때 이슈 상태를 `resolved`로, `fail`일 때 `needs_recheck`로 바꾸고, 그 `Verification`에 `verified_by`와 `verified_at`을 기록한다. (상태 수동 표기, 관찰 1)
- **AC8** [예외 대응]: 자유서술 이슈 텍스트에 `screen`·`element`·`repro_steps`에 해당하는 내용이 없으면, QAting은 그 값을 지어내지 않고 비워 둔 채 `missing`에 빠진 항목 이름을 담아 되묻는다. (재현 정보 부족 → 재질문과 대기)
- **AC9** [이벤트 기반]: `GET /api/v1/health` 요청이 오면, QAting은 200과 `{"status":"ok"}`를 반환한다. **(구현됨)**
- **AC10** [이벤트 기반]: 미인증 상태에서 `GET /api/v1/auth/me` 요청이 오면, QAting은 200과 `{"authenticated": false}`를 반환한다. **(구현됨)** 

> AC1~AC8의 실행 가능한 골든 케이스: `tests/harness/golden_cases.yaml` (강의 5에서 작성). AC8은 `src/qating/prompts/parse_query.md`의 실패 모드 점검표와도 연결된다.
