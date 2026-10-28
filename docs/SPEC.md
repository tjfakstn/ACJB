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
  - QA 이슈 등록 — 재현 정보(`screen_location`, `repro_steps`, `platform_scope`, `description`) 필수, `Environment`·스크린샷·Figma 링크 첨부
  - 자유서술 이슈 텍스트 → `IssueReportContext` 파싱과 빠진 항목 되묻기 (강의 3 구조화 출력)
  - 스타일 차이 기록 — `StyleMismatch`(`expected_value`, `actual_value`). 사람이 입력하는 경로와, Figma 시안과 배포된 웹 화면의 값을 자동으로 추출해 비교하는 경로(scan)를 모두 둔다 (2026-10-28 v1 포함 확정, 스파이크 `docs/spikes/` 통과)
    - scan 범위: **웹 화면, 단일 컴포넌트, `color`·`font_weight`·`radius` 3개 property**로 한정한다. 스파이크로 검증된 범위가 이것뿐이다
    - 범위 밖(앱 AOS/iOS 화면, `font_family`·`shadow`·`spacing`·`text`, alpha·그라디언트·hover 등 상태)은 scan 대상이 아니며 사람이 입력해 기록한다
  - 이슈 4단계 상태(`needs_check` → `fixed` → `resolved` / `needs_recheck`)와 변경 이력(`status_history`)
  - 상태 알림(`Notification`) — 이슈 등록 시 담당자에게, 조치 완료 시 등록자에게, 재검증 실패 시 담당자에게
  - 권한 — 프로젝트 멤버만 접근, `fixed` 처리는 담당자만, 재검증 등록은 등록자만
  - 프로젝트·멤버 조회 — 내가 속한 프로젝트와 그 멤버(담당자 선택용). 프로젝트·멤버 등록 API는 없고 시드 데이터로 넣는다
  - 알림 조회 — 내게 온 앱 내 알림만. 읽음 처리와 이메일·Slack 등 외부 채널은 v2
  - 인증 — 구글 로그인 세션 쿠키 + 상태 변경 요청의 CSRF 토큰(`X-XSRF-TOKEN` 헤더)
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
`/projects/{projectId}/**` 경로는 모두 로그인한 그 프로젝트의 `Member`만 호출할 수 있다(미인증 401, 비멤버 403 — AC12).
인증은 구글 로그인 세션 쿠키다. POST·PATCH는 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더에 실어 보내야 하고, 없거나 틀리면 403이다. 멤버를 가리키는 값(`assigned_to`, `reported_by`, `verified_by` 등)은 모두 멤버 id다.

- `GET /health` → `{status: "ok"}` **(구현됨)**
- `GET /auth/me` → `{authenticated, name, email, picture}` / 미인증 `{authenticated: false}` **(구현됨)**
- `GET /projects` → `Project[]` — 내가 속한 프로젝트만 (AC15)
- `GET /projects/{projectId}/members` → `Member[]` — 담당자 선택용 (AC15)
- `GET /projects/{projectId}/notifications?event?` → `Notification[]` — 내게 온 알림만, `created_at desc` (AC14)
- `POST /projects/{projectId}/qa-issues/parse` — body `{text}` → `{context: IssueReportContext, missing: string[]}` *(신규)*
- `POST /projects/{projectId}/qa-issues` — body `{description, screen_location, repro_steps[], platform_scope, environment{platform, device, browser, deploy_stage}, screenshots[], figma_link?, style_mismatch?{property, expected_value, actual_value}, assigned_to?}` → `QAIssue` (`status: "needs_check"`, `reported_by`는 로그인 사용자)
- `POST /projects/{projectId}/figma/sync` — body `{figma_file_key, frame_ids?}` → 202 `{sync_id, status: "queued"}` (비동기) / `GET .../figma/sync/{syncId}` → 작업 상태 / `GET .../figma/frames` → `FigmaFrame[]`
- `POST /projects/{projectId}/style-mismatches/scan` — body `{frame_id, node_id, target_url, selector}` → 202 `{scan_id, status: "queued"}` (비동기. 4절의 scan 범위만 처리. 서버가 Figma 값과 `target_url`의 `selector` 요소 computed style을 비교 — 동작 방식은 openapi 설명 참고) / `GET /projects/{projectId}/style-mismatches/scans/{scanId}` → 작업 상태
- `GET /projects/{projectId}/style-mismatches?frame_id?&property?` → `StyleMismatch[]` / `GET .../style-mismatches/{mismatchId}` → `StyleMismatch`
- `GET /projects/{projectId}/qa-issues/{issueId}` → `QAIssue` (재현 정보와 `status_history` 포함 — AC5)
- `POST /projects/{projectId}/style-mismatches/{mismatchId}/issues` — 기록된 `StyleMismatch`에서 이슈 생성 (자동 비교 경로)
- `PATCH /projects/{projectId}/qa-issues/{issueId}` — body `{status: "fixed", assigned_to?}` → `QAIssue` (허용 전이만, AC6 / 담당자 지정 시 알림, AC3 / `status: fixed`는 담당자만, AC13 / `assigned_to`는 멤버 id이고 null로 비우는 요청과 status·assigned_to 외의 필드는 400)
- `GET /projects/{projectId}/qa-issues?status=fixed` — 재확인 대기 이슈 목록
- `POST /projects/{projectId}/qa-issues/{issueId}/verifications` — body `{result: "pass"|"fail", scope?}` → `{verification: Verification, issue_status, repro_steps[]}` (`verified_by`는 로그인 사용자, 등록자만 가능 — AC13 / `fail`이면 담당자에게 알림 — AC11)
- `GET /projects/{projectId}/qa-issues/{issueId}/verifications` → `Verification[]`

## 6. 수용 기준 (테스트로 검증 — Definition of Done)

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
- **AC11** [이벤트 기반]: 재검증이 `fail`로 등록돼 이슈 상태가 `needs_recheck`로 바뀌면, QAting은 그 이슈의 담당자(`assigned_to`)에게 `Notification`(`event: recheck_failed`)을 정확히 1건 생성한다. 담당자가 없으면 생성하지 않는다. `pass`일 때는 생성하지 않는다. (등록·조치 알림 누락 → 재작업 요청이 다시 구두·메신저로 돌아감)
- **AC12** [상시 적용]: QAting은 항상 `/projects/{projectId}/**` 요청을 로그인한 그 프로젝트의 `Member`에게만 허용한다. 미인증이면 401, 로그인했지만 그 프로젝트 멤버가 아니면 403을 반환하고, 다른 프로젝트의 이슈·알림 데이터를 응답에 담지 않는다.
- **AC13** [상시 적용]: QAting은 항상 역할이 정해진 상태 변경을 정해진 사람에게만 허용한다. `status: fixed` PATCH는 요청자가 그 이슈의 `assigned_to`일 때만(담당자가 없으면 누구도 불가), 재검증 등록은 요청자가 그 이슈의 `reported_by`일 때만 허용하고, 그 밖에는 403을 반환한다. `assigned_to` 지정·변경은 프로젝트 멤버 누구나 할 수 있으나 그 프로젝트 멤버가 아닌 사람을 지정하면 400을 반환한다. 권한 검사는 상태 전이 검사(AC6)보다 먼저 하며, 403으로 거부된 요청은 상태·`status_history`·`Notification`을 바꾸지 않는다.

- **AC14** [이벤트 기반]: 프로젝트 멤버가 `GET /projects/{projectId}/notifications`를 호출하면, QAting은 그 요청자가 받는 `Notification`(`to_member_id`가 요청자)만 `created_at desc, id desc` 순으로 반환하고, 다른 멤버의 알림은 담지 않는다. (등록·조치 알림 누락 → 알림을 받을 곳이 없으면 목록을 직접 확인하게 된다)
- **AC15** [이벤트 기반]: 로그인한 사용자가 `GET /projects`를 호출하면, QAting은 그 사용자가 `Member`인 프로젝트만 `name asc, id asc` 순으로 반환한다. `GET /projects/{projectId}/members`는 그 프로젝트의 멤버(AC12)에게만 그 프로젝트 멤버 목록을 반환한다. (재현 정보 부족 → 담당자와 프로젝트를 고를 수단이 없으면 `assigned_to`를 채울 수 없다)

> AC1~AC8을 실행할 수 있는 골든 케이스는 `tests/harness/golden_cases.yaml`에 둔다(강의 5에서 작성). AC11~AC13과 6.1절 금지 기준의 골든 케이스도 같은 파일에 둔다(2026-10-11 설만수 승인 후 추가). AC14·AC15의 골든 케이스는 아직 없다(2026-10-28 AC 추가, 사람 승인 후 추가). AC8은 `src/qating/prompts/parse_query.md`의 실패 모드 점검표와도 연결된다. [상시 적용] AC(AC2, AC6, AC12, AC13)는 `AGENTS.md` 절대 규칙에도 같은 내용으로 둔다.

### 6.1 금지 기준 (구현 중 이탈 방지)

AC를 구현하면서 에이전트가 "편의상" 범위를 넓히거나 값을 지어내는 것을 막는 최소 금지 목록이다. 모두 "QAting은 ~하지 않는다"로 읽고, AC와 같은 무게로 검증한다(위반하면 그 AC도 통과하지 않은 것으로 본다). 골든 케이스는 `ng-` 접두사로 `tests/harness/golden_cases.yaml`에 둔다.

| ID | 관련 AC | 금지 내용 | 검증 |
|---|---|---|---|
| N1 | AC1 | 필수 항목이 비었을 때 `"미입력"`·`"N/A"`·`["-"]` 같은 기본값으로 채워 저장하지 않는다. 공백만 있는 문자열도 빈 값으로 본다 | `ng-001` |
| N2 | AC1·AC2 | 400으로 거부한 요청을 일부만 저장하지 않는다(예: `style_mismatch`만 빼고 이슈 저장) | `ng-002` |
| N3 | AC2 | 사람이 입력한 `StyleMismatch`에서 빠진 값을 추론하거나 Figma에서 가져와 채우지 않는다. scan 경로도 Figma와 구현 양쪽 값을 실제로 추출했을 때만 저장하고, 한쪽 추출에 실패하면 저장하지 않는다 | `sm-001` |
| N4 | AC2 | scan은 4절에 적힌 범위(웹 화면, 단일 컴포넌트, `color`·`font_weight`·`radius`)만 처리한다. 범위 밖 property·앱 화면을 추정해 채우지 않는다. (2026-10-28 scan v1 포함 확정으로 "만들지 않는다"에서 "범위 한정"으로 바뀜) | `ng-003` |
| N5 | AC3·AC4·AC11 | 실제 변화가 없는 요청(같은 담당자 재지정 등)으로 알림을 만들지 않는다. 정해진 3개(`issue_created`·`fixed`·`recheck_failed`) 외의 알림 종류를 만들지 않는다 | `ng-004` |
| N6 | AC5 | 등록 후 `repro_steps`·`screen_location`·`environment`를 바꾸지 않는다. PATCH는 `status`·`assigned_to`만 받고 다른 필드가 오면 400. 재검증 요청에 재현 절차가 들어와도 원본을 덮어쓰지 않는다 | `ng-005`, `ng-006` |
| N7 | AC6 | `status_history`를 수정·삭제하는 경로를 만들지 않는다. 409로 거부된 요청은 `status_history`를 늘리지 않는다. 같은 상태로의 전이(예: `fixed`→`fixed`)도 409 | `ng-007` |
| N8 | AC6·AC7 | `reported_by`·`verified_by`·`changed_by`와 각 시각을 요청 body에서 받지 않는다 — 항상 로그인 사용자와 서버 시각을 쓴다 | `ng-008` |
| N9 | AC8 | 파싱 API는 DB에 아무것도 쓰지 않는다(이슈 자동 등록 금지). 스키마 검증에 실패하면 원문을 넣거나 재시도로 값을 만들어 내지 않고 실패로 응답한다 | `ng-009` |
| N10 | AC12·AC13 | 다른 프로젝트의 `issueId`로 요청해도 그 데이터를 반환하지 않는다. 테스트용 인증 우회(특정 헤더·플래그)를 운영 코드에 넣지 않는다 | `ng-010` |
| N11 | 전체 | `ontology.yaml`에 없는 상태값·알림 종류·필드를 추가하지 않는다(예: `in_progress`, `closed`) | 스키마 검증 |
| N12 | 전체 | 응답 형태(`{success, data, error}`)와 구현된 AC9·AC10의 응답을 임의로 바꾸지 않는다 | 기존 골든 케이스 |
| N13 | 4절 | 4절 비포함 기능(외부 트래커 연동, E2E 자동화, 토큰 동기화)을 구현하지 않는다 | PR 리뷰 |
