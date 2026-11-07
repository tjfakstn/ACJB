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
    - scan은 두 단계를 섞어 쓴다 (2026-11-04 팀 결정)
      - **초기 전체 비교 = 첫 QA** (2026-11-07 시안 반영 — 2026-11-04·11-05의 "배포 화면과 비교·제안만"을 바꿈): 프로젝트를 만들 때 소유자가 **Figma 파일과 GitHub 저장소를 연결**하고(공개 저장소만, 서버 토큰으로 읽는다), 두 단계로 진행한다
        1. **비교 분석**(프로젝트 시작): Figma 프레임·노드와 UI 코드 위치(파일·컴포넌트)의 짝(매핑)을 LLM으로 만든다. 이슈는 만들지 않는다
        2. **첫 QA 실행**(작업공간에서 소유자가 누름, 프로젝트당 성공 1번): 매핑된 짝마다 Figma 값과 코드 값을 비교해 다른 항목을 **이슈로 자동 저장**한다. 값은 근거에서만 채운다 — Figma 값은 Figma API로 읽은 노드 값, 코드 값은 LLM이 인용한 파일·줄에 그 값이 실제로 있을 때만 인정한다. 둘 중 하나라도 없으면 이슈로 만들지 않는다(AC2·N17). 담당자는 비워 두고 사람이 배정한다(AC23)
        - 배포 URL은 받지 않는다(사이트가 떠 있지 않아도 된다). 매핑 결과의 형태, 비교할 property 범위와 정확도 기준은 스파이크 후 이 절에 확정한다(N4)
        - 첫 QA 이후의 QA는 사람이 등록하는 이슈와 이력 기반 점검 후보(아래) 흐름만 있다
      - **단일 컴포넌트 scan**: 그 이후에는 사람이 Figma 노드와 화면 요소의 짝을 지정해 비교한다. 범위는 **웹 화면, 단일 컴포넌트, `color`·`font_weight`·`radius` 3개 property**로 한정한다. 스파이크로 검증된 범위가 이것뿐이다
    - 범위 밖(앱 AOS/iOS 화면, `font_family`·`shadow`·`spacing`·`text`, alpha·그라디언트·hover 등 상태)은 scan 대상이 아니며 사람이 입력해 기록한다
  - 이력 기반 점검 후보 (2차 LLM 호출 + RAG, 2026-11-04 팀 결정) — 첫 QA 이후 운영 중 정해진 호출 지점에서, 그 프로젝트에 쌓인 이슈·스타일 차이·재검증 이력을 근거 문서로 검색(RAG)해 다시 볼 곳을 제안한다. 결과는 근거(이슈 id 등)가 달린 제안일 뿐 자동으로 저장하지 않는다. (2026-11-05 확정) 호출 지점은 **재검증 범위 제안** 하나다 — `fixed` 이슈를 재검증할 때 요청하면(요청 시에만 호출) 그 이슈와 함께 다시 확인할 화면·이슈를 근거와 함께 제안한다(인터뷰 로그 2·6·8의 연결 화면 회귀). 검색은 임베딩으로 한다 — Voyage AI로 이슈·스타일 차이·재검증 기록의 벡터를 만들어 MySQL에 저장하고, 프로젝트 단위로 서버에서 유사도를 계산한다. LLM 응답은 근거 id 검사로 거른다 — 검색 결과에 없던 id를 근거로 단 제안은 버린다(N16). 제안 품질은 `evals/` 채점으로 잰다. (2026-11-06 상세 설계 확정, 근거 `docs/design-recheck-suggestions.md`) 요청은 재검증 등록 권한자만 할 수 있고, 제안 결과는 저장하지 않는다(매번 새로 계산해 응답만 돌려준다). 임베딩 대상은 이슈·스타일 차이·`fail` 재검증 기록이며 원본이 만들어질 때 한 번 만들고, 빠진 것은 제안 요청 때 보충한다 (AC20)
  - 이슈 5단계 상태(`needs_check` → `in_progress`(수정 중) → `fixed` → `resolved` / `needs_recheck`, `in_progress`는 건너뛸 수 있다 — 2026-11-07)와 변경 이력(`status_history`)
  - 상태 알림(`Notification`) — 이슈 등록 시 담당자에게, 조치 완료 시 등록자에게, 재검증 실패 시 담당자에게
  - 권한 — 프로젝트 멤버만 접근, `in_progress`·`fixed` 처리는 담당자만, Figma·GitHub 연결·비교 분석·첫 QA 실행·역할 변경은 소유자만, 재검증 등록은 등록자만(등록자가 프로젝트에서 제거됐으면 소유자)
  - 프로젝트·멤버 관리 (2026-11-04 팀 결정 — 시드 데이터로 넣던 방식은 폐기)
    - 로그인한 사용자 누구나 프로젝트를 만든다. 만든 사람이 그 프로젝트의 소유자(owner)이자 첫 멤버다
    - 프로젝트 멤버 누구나 다른 사람을 구글 이메일로 초대한다. 서버가 그 이메일로 초대 메일을 보내고, 초대받은 사람이 **같은 구글 계정으로 로그인해 수락**하면 멤버가 된다. 초대는 7일 뒤 만료된다. 메일을 못 받아도 로그인하면 앱에서 자기 초대를 보고 수락할 수 있다
    - 역할(`role`: designer/frontend/backend/planner)은 프로젝트마다 다르다. 초대받은 사람은 수락할 때 본인이 고르고, 만든 사람(소유자)은 역할 없이(미지정) 시작한다. 이후 변경은 소유자만 한다(소유자 본인 포함, 알림 없음 — 2026-11-07)
    - 프로젝트는 이름(필수, 2~40자)과 설명(선택, 200자 이하)으로 만든다. 설명은 작업공간·초대 미리보기·초대 메일에 보인다(2026-11-07 시안 PRJ-02)
    - 대기 중인 초대를 다시 보내면 새 초대를 만들지 않고 그 초대의 만료를 지금부터 7일로 늘리고 새 수락 링크를 메일로 보낸다(이전 링크는 무효 — 2026-11-07)
    - 초대를 수락하면 그 프로젝트 작업공간으로 들어간다
    - 멤버 제거는 소유자만 한다. 제거된 멤버가 담당자인 미해결 이슈는 담당자가 비워진다
    - 조회 — 내가 속한 프로젝트와 그 멤버(담당자 선택용)
    - 소유자는 프로젝트를 삭제할 수 있다 — 프로젝트와 그 안의 데이터를 모두 지우고 되돌릴 수 없다. 멤버 계정은 남는다(AC26, 2026-11-07)
    - 범위 밖(v1): 소유자 이전, 휴지통(삭제 복구), 초대 거절 기록, 멤버 스스로 나가기, 비공개 GitHub 저장소(GitHub App)
  - 프로젝트 작업공간 — 프로젝트를 열면 오는 홈. 연결 상태·비교 분석·첫 QA 상태·멤버 수·상태별 이슈 수를 한 번에 보여 준다(AC25, 2026-11-07)
  - 알림 조회 — 내게 온 앱 내 알림만. 읽음 처리와 이메일·Slack 등 외부 채널은 v2
  - 인증 — 구글 로그인 세션 쿠키 + 상태 변경 요청의 CSRF 토큰(`X-XSRF-TOKEN` 헤더)
  - 재검증 기록(`Verification`) — 최초 재현 절차 재사용, 결과에 따라 상태 자동 전환
  - 구글 로그인 (구현됨)
- **비포함** (`ontology.yaml`의 범위 밖과 동일)
  - 테스트 데이터 세팅·초기화
  - E2E 회귀 테스트 자동화 — 재확인은 사람이 하고, QAting은 기록과 절차 재사용만 맡는다
  - 디자인 변경 전파·토큰 동기화 (v2 후보)
  - 예외 상황 UI 재제작
  - 외부 이슈 트래커(GitHub Issues·Jira 등) 연동 — 응답자들이 쓰는 도구가 Jira·Notion·엑셀로 제각각이라(로그 2, 8, 11, 12) v1에서는 제외 (v2 후보). GitHub 저장소의 **코드를 읽는 것**(첫 QA 입력)은 포함이고, 이슈·PR을 만들거나 읽는 연동은 비포함이다

## 5. 인터페이스

전체 명세: `docs/openapi.yaml` v0.5.4 (base `/api/v1`). 핵심만 발췌. 두 문서가 어긋나면 이 스펙이 정본이다.
`/projects/{projectId}/**` 경로는 모두 로그인한 그 프로젝트의 `Member`만 호출할 수 있다(미인증 401, 비멤버 403 — AC12).
인증은 구글 로그인 세션 쿠키다. POST·PATCH는 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더에 실어 보내야 하고, 없거나 틀리면 403이다. 멤버를 가리키는 값(`assigned_to`, `reported_by`, `verified_by` 등)은 모두 멤버 id다.

- `GET /health` → `{status: "ok"}` **(구현됨)**
- `GET /auth/me` → `{authenticated, id, name, email, picture}` (`id`는 내 멤버 id, 2026-11-05 추가) / 미인증 `{authenticated: false}` **(구현됨)**
- `GET /projects` → `ProjectSummary[]` — 내가 속한 프로젝트만, 목록 카드용 연결·분석 상태와 이슈 수·멤버 미리보기 포함 (AC15)
- `POST /projects` — body `{name, description?}` → 201 `Project` — 요청자가 소유자이자 역할 미지정 멤버 (AC16)
- `DELETE /projects/{projectId}` → 204 — 프로젝트와 그 안의 데이터를 모두 삭제, 소유자만 (AC26)
- `GET /projects/{projectId}` → `ProjectDetail` — 작업공간 홈용: 내 역할, Figma·GitHub 연결, 비교 분석·첫 QA 상태, 멤버 수·대기 초대 수, 상태별 이슈 수 (AC25)
- `PUT /projects/{projectId}/connections/figma` · `PUT .../connections/github` — body `{url}` → 연결 정보(접근 확인 후 저장) — 소유자만 (AC21)
- `POST /projects/{projectId}/analysis` → 202 `AnalysisRun`(비동기) / `GET .../analysis` → 최근 비교 분석 상태 — 소유자만 시작 (AC22)
- `POST /projects/{projectId}/first-qa` → 202 `FirstQaRun`(비동기) / `GET .../first-qa` → 첫 QA 상태와 만든 이슈 수 — 소유자만 시작, 성공 1번 (AC23)
- `GET /projects/{projectId}/members` → `Member[]` — 담당자 선택용, 프로젝트별 `role` 포함 (AC15)
- `PATCH /projects/{projectId}/members/{memberId}` — body `{role}` → 역할 변경, 소유자만 (AC24)
- `DELETE /projects/{projectId}/members/{memberId}` — 소유자만 (AC19)
- `POST /projects/{projectId}/invitations` — body `{email}` → 201 `Invitation` + 초대 메일 발송, 같은 이메일에 대기 중 초대가 있으면 200으로 그 초대를 연장해 다시 보낸다 (AC17)
- `POST /projects/{projectId}/invitations/{invitationId}/resend` → 200 `Invitation` — 대기 중 초대의 만료를 7일 연장하고 새 링크로 다시 보낸다(멤버 누구나) (AC17)
- `GET /projects/{projectId}/invitations` → 대기 중인 초대 목록 / `DELETE .../invitations/{invitationId}` → 초대 취소(초대한 사람 또는 소유자)
- `GET /invitations/mine` → 로그인 이메일로 온 대기 중인 초대 / `POST /invitations/mine/{invitationId}/accept` — body `{role}` (메일을 못 받았을 때 앱에서 수락하는 경로, 규칙은 AC18과 같다)
- `GET /invitations/{token}` → 초대 미리보기(프로젝트 이름·설명·초대한 사람·만료 시각) / `POST /invitations/{token}/accept` — body `{role}` → 멤버가 됨 (AC18). `/invitations/**`는 로그인만 필요하다(아직 그 프로젝트 멤버가 아니므로 AC12 대상 아님)
- `GET /projects/{projectId}/notifications?event?` → `Notification[]` — 내게 온 알림만, `created_at desc` (AC14)
- `POST /projects/{projectId}/qa-issues/parse` — body `{text}` → `{context: IssueReportContext, missing: string[]}` *(신규)*
- `POST /projects/{projectId}/qa-issues` — body `{description, screen_location, repro_steps[], platform_scope, environment{platform, device, browser, deploy_stage}, screenshots[], figma_link?, style_mismatch?{property, expected_value, actual_value}, assigned_to?}` → `QAIssue` (`status: "needs_check"`, `reported_by`는 로그인 사용자)
- `POST /projects/{projectId}/figma/sync` — body `{figma_file_key, frame_ids?}` → 202 `{sync_id, status: "queued"}` (비동기) / `GET .../figma/sync/{syncId}` → 작업 상태 / `GET .../figma/frames` → `FigmaFrame[]`
- `POST /projects/{projectId}/style-mismatches/scan` — body `{frame_id, node_id, target_url, selector}` → 202 `{scan_id, status: "queued"}` (비동기. 4절의 scan 범위만 처리. 서버가 Figma 값과 `target_url`의 `selector` 요소 computed style을 비교 — 동작 방식은 openapi 설명 참고) / `GET /projects/{projectId}/style-mismatches/scans/{scanId}` → 작업 상태
- `GET /projects/{projectId}/style-mismatches?frame_id?&property?` → `StyleMismatch[]` / `GET .../style-mismatches/{mismatchId}` → `StyleMismatch`
- `GET /projects/{projectId}/qa-issues/{issueId}` → `QAIssue` (재현 정보와 `status_history` 포함 — AC5)
- `POST /projects/{projectId}/style-mismatches/{mismatchId}/issues` — 기록된 `StyleMismatch`에서 이슈 생성 (자동 비교 경로)
- `PATCH /projects/{projectId}/qa-issues/{issueId}` — body `{status: "in_progress"|"fixed", assigned_to?}` → `QAIssue` (허용 전이만, AC6 / 담당자 지정 시 알림, AC3 / `status`는 담당자만, AC13 / `assigned_to`는 멤버 id이고 null로 비우는 요청과 status·assigned_to 외의 필드는 400)
- `GET /projects/{projectId}/qa-issues?status&source&assigned_to` — 이슈 목록(`status=fixed`는 재확인 대기, `source=first_qa`는 첫 QA 결과)
- `POST /projects/{projectId}/qa-issues/{issueId}/verifications` — body `{result: "pass"|"fail", scope?}` → `{verification: Verification, issue_status, repro_steps[]}` (`verified_by`는 로그인 사용자, 등록자만 가능, 등록자가 제거됐으면 소유자 — AC13 / `fail`이면 담당자에게 알림 — AC11)
- `GET /projects/{projectId}/qa-issues/{issueId}/verifications` → `Verification[]`
- `POST /projects/{projectId}/qa-issues/{issueId}/recheck-suggestions` — 본문 없음 → `{issue_id, suggestions[{screen, reason, evidence[{type, id}]}], searched_count, dropped_count}` (재검증 범위 제안, 재검증 등록 권한자만, `fixed` 이슈만 — AC20. 결과는 저장하지 않는다)

오류 코드는 2026-11-07에 3개를 더해 16개다 — `CONNECTION_FORBIDDEN`(422)·`CONNECTION_FAILED`(502)·`NOT_READY`(409). 목록은 `docs/openapi.yaml` `Error.code`

## 6. 수용 기준 (테스트로 검증 — Definition of Done)

각 AC는 EARS 문형 "[조건]일 때, QAting은 [동작]한다"로 쓰고, 조건 자리의 유형을 [ ]에 표시한다. 괄호 안은 근거가 된 문제 정의서의 증거다.

- **AC1** [이벤트 기반]: 사용자가 QA 이슈를 등록하면, QAting은 `description`·`screen_location`·`repro_steps`·`platform_scope` 중 하나라도 비어 있을 때 등록을 거부하고(400) 빠진 항목 이름을 반환한다. (재현 정보 부족 → 재질문과 대기)
- **AC2** [상시 적용]: QAting은 항상 `StyleMismatch`를 `expected_value`와 `actual_value`가 **둘 다** 있을 때만 저장한다. 한쪽만 있으면 거부한다(400). (디자인-구현 차이를 사람이 눈으로 대조)
- **AC3** [이벤트 기반]: QA 이슈가 등록될 때 `assigned_to`가 지정돼 있거나, 이후 PATCH로 `assigned_to`가 새로 지정되거나 바뀌면, QAting은 그 담당자에게 `Notification`(`event: issue_created`)을 정확히 1건 생성한다. 담당자가 없으면 생성하지 않는다. (등록·조치 알림 누락)
- **AC4** [이벤트 기반]: 이슈 상태가 `fixed`로 바뀌면, QAting은 그 이슈의 등록자(`reported_by`)에게 `Notification`(`event: fixed`)을 정확히 1건 생성한다. (등록·조치 알림 누락 → 목록을 직접 확인)
- **AC5** [이벤트 기반]: 사용자가 `fixed` 상태의 이슈를 조회하면(`GET /qa-issues/{issueId}`), QAting은 등록 때 기록된 `repro_steps`·`screen_location`·`Environment`를 함께 반환한다. 재검증을 등록할 때는 재현 절차를 입력받지 않는다. (수정 후 재확인을 처음과 같은 과정으로 반복)
- **AC6** [상시 적용]: QAting은 항상 정해진 상태 전이만 허용한다. `needs_check`·`needs_recheck` → `in_progress`, `in_progress` → `fixed`, `needs_check`·`needs_recheck` → `fixed`(수정 중 건너뛰기)는 PATCH로만, `fixed → resolved`(pass)와 `fixed → needs_recheck`(fail)는 재검증 등록으로만 일어난다. 그 밖의 전이 요청은 거부한다(409). 허용된 모든 상태 변경은 `status_history`에 변경 시각과 변경자를 남긴다. (상태 수동 표기, 관찰 1)
- **AC7** [이벤트 기반]: 재검증이 등록되면, QAting은 `result`가 `pass`일 때 이슈 상태를 `resolved`로, `fail`일 때 `needs_recheck`로 바꾸고, 그 `Verification`에 `verified_by`와 `verified_at`을 기록한다. (상태 수동 표기, 관찰 1)
- **AC8** [예외 대응]: 자유서술 이슈 텍스트에 `screen`·`element`·`repro_steps`에 해당하는 내용이 없으면, QAting은 그 값을 지어내지 않고 비워 둔 채 `missing`에 빠진 항목 이름을 담아 되묻는다. (재현 정보 부족 → 재질문과 대기)
- **AC9** [이벤트 기반]: `GET /api/v1/health` 요청이 오면, QAting은 200과 `{"status":"ok"}`를 반환한다. **(구현됨)**
- **AC10** [이벤트 기반]: 미인증 상태에서 `GET /api/v1/auth/me` 요청이 오면, QAting은 200과 `{"authenticated": false}`를 반환한다. **(구현됨)**
- **AC11** [이벤트 기반]: 재검증이 `fail`로 등록돼 이슈 상태가 `needs_recheck`로 바뀌면, QAting은 그 이슈의 담당자(`assigned_to`)에게 `Notification`(`event: recheck_failed`)을 정확히 1건 생성한다. 담당자가 없으면 생성하지 않는다. `pass`일 때는 생성하지 않는다. (등록·조치 알림 누락 → 재작업 요청이 다시 구두·메신저로 돌아감)
- **AC12** [상시 적용]: QAting은 항상 `/projects/{projectId}/**` 요청을 로그인한 그 프로젝트의 `Member`에게만 허용한다. 미인증이면 401, 로그인했지만 그 프로젝트 멤버가 아니면 403을 반환하고, 다른 프로젝트의 이슈·알림 데이터를 응답에 담지 않는다.
- **AC13** [상시 적용]: QAting은 항상 역할이 정해진 상태 변경을 정해진 사람에게만 허용한다. `status: in_progress`·`status: fixed` PATCH는 요청자가 그 이슈의 `assigned_to`일 때만(담당자가 없으면 누구도 불가), 재검증 등록은 요청자가 그 이슈의 `reported_by`일 때만 허용하고(단, `reported_by`가 더 이상 그 프로젝트 멤버가 아니면 프로젝트 소유자에게 허용한다 — 2026-11-05), 그 밖에는 403을 반환한다. `assigned_to` 지정·변경은 프로젝트 멤버 누구나 할 수 있으나 그 프로젝트 멤버가 아닌 사람을 지정하면 400을 반환한다. 권한 검사는 상태 전이 검사(AC6)보다 먼저 하며, 403으로 거부된 요청은 상태·`status_history`·`Notification`을 바꾸지 않는다.

- **AC14** [이벤트 기반]: 프로젝트 멤버가 `GET /projects/{projectId}/notifications`를 호출하면, QAting은 그 요청자가 받는 `Notification`(`to_member_id`가 요청자)만 `created_at desc, id desc` 순으로 반환하고, 다른 멤버의 알림은 담지 않는다. (등록·조치 알림 누락 → 알림을 받을 곳이 없으면 목록을 직접 확인하게 된다)
- **AC15** [이벤트 기반]: 로그인한 사용자가 `GET /projects`를 호출하면, QAting은 그 사용자가 `Member`인 프로젝트만 `name asc, id asc` 순으로 반환한다. 각 항목에는 설명, Figma·GitHub 연결 여부, 비교 분석의 최근 상태와 마지막 성공 시각, 전체 이슈 수(`issue_count`)와 미해결(`resolved`가 아닌) 이슈 수(`open_issue_count`), 멤버 수와 이름순 앞 3명을 담는다(시안 PRJ-01 — "QA 결과 n건"=전체, "FAIL n건"=미해결, 2026-11-07). `GET /projects/{projectId}/members`는 그 프로젝트의 멤버(AC12)에게만 그 프로젝트 멤버 목록을 반환한다. (재현 정보 부족 → 담당자와 프로젝트를 고를 수단이 없으면 `assigned_to`를 채울 수 없다)

- **AC16** [이벤트 기반]: 로그인한 사용자가 `POST /projects`를 호출하면, QAting은 프로젝트를 만들고 요청자를 그 프로젝트의 소유자이자 역할 미지정(`role: null`)의 멤버로 등록한다(역할은 이후 AC24로 정한다 — 2026-11-07). `name`이 비었거나 공백을 뺀 길이가 2~40자가 아니거나 허용 문자(한글·영문·숫자·공백·`-`·`_`) 밖이면, 또는 `description`이 200자를 넘으면 거부하고(400) 아무것도 만들지 않는다. 처음 로그인한 구글 계정은 그때 `Member`로 만들어진다.
- **AC17** [이벤트 기반]: 프로젝트 멤버가 구글 이메일로 초대하면, QAting은 7일 뒤 만료되는 대기 중 초대(`Invitation`)를 1건 만들고 그 이메일로 초대 메일을 1통 보낸다. 이미 그 프로젝트 멤버인 이메일이면 거부한다(409). 같은 이메일에 대기 중인 초대가 있거나 재전송(`.../resend`)을 요청하면, 새 초대를 만들지 않고 그 초대의 만료를 요청 시각부터 7일로 늘리고 수락 링크 토큰을 새로 발급해 메일을 1통 보낸다(이전 링크는 무효). 대기 중이 아닌 초대의 재전송은 409다. 메일 발송이 실패해도 초대는 남고, 초대받은 사람은 로그인해 앱에서 수락할 수 있다.
- **AC18** [예외 대응]: 초대를 수락할 때, QAting은 로그인한 구글 이메일이 초대받은 이메일과 같지 않으면 거부하고(403), 초대가 만료·취소·이미 수락된 상태면 거부하며(409), `role`이 정해진 값이 아니면 거부한다(400). 그 밖에는 요청자를 고른 `role`의 멤버로 등록하고 초대를 수락됨으로 바꾼다. 거부된 요청은 멤버를 만들지 않는다.
- **AC19** [이벤트 기반]: 소유자가 멤버를 제거하면, QAting은 그 멤버를 프로젝트에서 빼고, 그 멤버가 담당자인 미해결(`resolved`가 아닌) 이슈의 `assigned_to`를 비운다. 상태·`status_history`·알림·재검증 기록은 바꾸지 않는다. 소유자가 아닌 사람의 제거 요청은 403, 소유자 자신을 제거하는 요청은 409다. 제거된 사람은 이후 AC12대로 그 프로젝트에 접근할 수 없다.
- **AC20** [이벤트 기반]: 재검증 등록 권한자(AC13)가 `fixed` 이슈에 재검증 범위 제안을 요청하면, QAting은 같은 프로젝트의 이슈·스타일 차이·`fail` 재검증 기록 중 그 이슈와 의미가 가까운 문서를 최대 8개 검색해 LLM에 주고, 같이 다시 볼 화면을 최대 5개 근거(검색된 문서의 type·id)와 함께 반환한다. 근거 id가 비었거나 하나라도 검색 결과에 없던 제안은 버리고 `dropped_count`에 센다. 검색 결과가 0건이면 LLM을 부르지 않고 빈 제안을 반환한다. 권한이 없으면 403, `fixed`가 아니면 409이며 이때 외부 호출을 하지 않는다. LLM·임베딩 호출 실패나 스키마 위반은 502(`SUGGESTION_FAILED`)이고 일부 결과를 돌려주지 않는다. 어느 경우에도 이슈·`StyleMismatch`·`Verification`·알림을 만들거나 바꾸지 않는다. (재검증 범위 판단 → 연결 화면 회귀를 놓친다, 인터뷰 로그 2·6·8)

- **AC21** [이벤트 기반]: 소유자가 Figma·GitHub 링크를 연결하면, QAting은 링크 형식(Figma: Figma 앱의 페이지 [공유하기]에서 복사한 **페이지 링크** `figma.com/design/{key}/{이름}?node-id={페이지 id}`만 — 2026-11-07, GitHub: `github.com/{owner}/{repo}`)을 검사하고(400) 서버 자격 증명으로 실제 접근이 되는지 확인한 뒤에만 연결을 저장한다. Figma는 `node-id`가 그 파일의 페이지일 때만 받고(프레임·섹션 링크, `node-id` 없는 링크, 프로토타입·브랜치·프로젝트 링크는 400) 파일 이름과 함께 페이지 id·이름을 저장한다 — 이후 동기화·비교 분석은 이 페이지 안만 본다. GitHub는 분석할 브랜치를 고른다 — `branch`를 빼면 기본 브랜치, 보내면 저장소에 있는 브랜치여야 하고(없으면 400) 응답에 고를 수 있는 브랜치 목록을 담는다(2026-11-07). 접근 권한이 없으면(Figma 파일 보기 권한 없음, GitHub 404 — GitHub는 비공개와 없는 저장소를 구분해 주지 않으므로 둘 다 여기) 422(`CONNECTION_FORBIDDEN`), 없는 Figma 파일이나 응답 지연·외부 서버 오류면 502(`CONNECTION_FAILED`)이고 이때 기존 연결을 바꾸지 않는다. 소유자가 아니면 403, 비교 분석이 성공한 뒤의 링크 변경은 409다. (시안 CON-01~05·E1~E5)
- **AC22** [이벤트 기반]: 소유자가 비교 분석을 시작하면, QAting은 Figma·GitHub가 둘 다 연결돼 있을 때만 비동기 실행을 1건 만들고(202) Figma 노드와 코드 위치의 매핑을 저장한다. 연결이 덜 됐거나, 실행 중이거나, 이미 성공했으면 409(`NOT_READY`)다. 실패하면 상태가 `failed`가 되고 다시 시작할 수 있다. 비교 분석은 이슈·`StyleMismatch`를 만들지 않는다. (시안 CON-06·06F·06R)
- **AC23** [이벤트 기반]: 소유자가 첫 QA를 실행하면, QAting은 비교 분석이 성공했고 첫 QA가 아직 성공하지 않았을 때만 비동기 실행을 1건 만들고(202), 매핑된 짝마다 Figma 값과 코드 값을 비교해 다른 항목을 이슈로 저장한다. 각 이슈는 `source: first_qa`, `status: needs_check`, `reported_by`=실행한 소유자, `assigned_to` 없음, `platform_scope: common`이고, `screen_location`은 Figma 프레임·노드 이름, `repro_steps`는 ["'{프레임}' 화면을 연다", "'{노드}'를 확인한다"], `style_mismatch`는 Figma 값(expected)과 코드 값(actual)이다. 근거가 모자란 항목은 저장하지 않고 `dropped_count`에 센다(N17). 실행 중·이미 성공·분석 전이면 409(`NOT_READY`). 실패하면 이슈를 하나도 남기지 않고 상태가 `failed`가 되며 다시 실행할 수 있다. 담당자가 없으므로 알림은 만들지 않는다(AC3). (시안 QA-01~04·02F·04P)
- **AC24** [이벤트 기반]: 소유자가 멤버의 역할을 바꾸면, QAting은 그 프로젝트에서의 `role`만 바꾼다(소유자 본인 포함). 소유자가 아니면 403, 그 프로젝트 멤버가 아니면 404, 정해진 값이 아니면 400이다. 이슈·담당자·알림은 바꾸지 않는다. (시안 MEM-06)
- **AC25** [이벤트 기반]: 프로젝트 멤버가 `GET /projects/{projectId}`를 호출하면, QAting은 이름·설명·소유자·내 역할(미지정이면 null), Figma·GitHub 연결(없으면 null), 비교 분석·첫 QA의 최근 상태(`none`/`queued`/`running`/`succeeded`/`failed`), 멤버 수·대기 중 초대 수, 상태별 이슈 수(5개 상태 모두, 0 포함)를 반환한다. 비멤버는 AC12대로 403. (시안 WS-01·01N·01M·02)
- **AC26** [이벤트 기반]: 소유자가 프로젝트를 삭제하면, QAting은 그 프로젝트와 그 안의 이슈(재현 절차·스크린샷·`status_history`)·재검증·알림·스타일 차이·scan·Figma 동기화·프레임·임베딩·비교 분석/첫 QA 실행·초대·멤버십을 한 트랜잭션으로 모두 지우고 204를 반환한다. `Member` 계정과 다른 프로젝트의 데이터는 그대로다. 소유자가 아니면 403이고 아무것도 지우지 않는다. 비교 분석·첫 QA가 실행 중(queued·running)이면 409(`NOT_READY`)다. 삭제한 뒤 그 프로젝트 경로는 AC12대로 403이다(멤버가 아니게 되므로). 이름을 입력해 확인하는 것은 화면의 일이다. (2026-11-07)
> AC1~AC8을 실행할 수 있는 골든 케이스는 `tests/harness/golden_cases.yaml`에 둔다(강의 5에서 작성). AC11~AC13과 6.1절 금지 기준의 골든 케이스도 같은 파일에 둔다(2026-10-11 설만수 승인 후 추가). AC14~AC19와 AC13 수정분(등록자 제거 시 소유자 재검증)의 골든 케이스도 같은 파일에 둔다(2026-11-05 설만수 승인 후 추가). AC20과 N16의 골든 케이스(`rs-001`~`rs-004`, `ng-014`~`ng-016`)도 같은 파일에 둔다(2026-11-06 설만수 승인 후 추가). AC21~AC25, AC6·AC13·AC17 수정분, N17의 골든 케이스와 AC15·AC16 수정분(`pj-003`·`pj-004` 교체, `pj-007`·`pj-008`), AC26(`pj-009`~`pj-011`)(`st-004`~`st-007`, `au-010`, `iv-003` 교체, `iv-008`·`iv-009`, `cn-001`~`cn-005`, `an-001`~`an-003`, `fq-001`~`fq-004`, `rl-001`~`rl-003`, `ws-001`·`ws-002`, `ng-017`~`ng-019`)도 같은 파일에 둔다(2026-11-07 설만수 승인 후 추가). AC8은 `src/qating/prompts/parse_query.md`의 실패 모드 점검표와도 연결된다. [상시 적용] AC(AC2, AC6, AC12, AC13)는 `AGENTS.md` 절대 규칙에도 같은 내용으로 둔다.

### 6.1 금지 기준 (구현 중 이탈 방지)

AC를 구현하면서 에이전트가 "편의상" 범위를 넓히거나 값을 지어내는 것을 막는 최소 금지 목록이다. 모두 "QAting은 ~하지 않는다"로 읽고, AC와 같은 무게로 검증한다(위반하면 그 AC도 통과하지 않은 것으로 본다). 골든 케이스는 `ng-` 접두사로 `tests/harness/golden_cases.yaml`에 둔다.

| ID | 관련 AC | 금지 내용 | 검증 |
|---|---|---|---|
| N1 | AC1 | 필수 항목이 비었을 때 `"미입력"`·`"N/A"`·`["-"]` 같은 기본값으로 채워 저장하지 않는다. 공백만 있는 문자열도 빈 값으로 본다 | `ng-001` |
| N2 | AC1·AC2 | 400으로 거부한 요청을 일부만 저장하지 않는다(예: `style_mismatch`만 빼고 이슈 저장) | `ng-002` |
| N3 | AC2 | 사람이 입력한 `StyleMismatch`에서 빠진 값을 추론하거나 Figma에서 가져와 채우지 않는다. scan 경로도 Figma와 구현 양쪽 값을 실제로 추출했을 때만 저장하고, 한쪽 추출에 실패하면 저장하지 않는다 | `sm-001` |
| N4 | AC2 | 단일 컴포넌트 scan은 4절에 적힌 범위(웹 화면, 단일 컴포넌트, `color`·`font_weight`·`radius`)만 처리한다. 범위 밖 property·앱 화면을 추정해 채우지 않는다. 첫 QA(초기 전체 비교)의 property 범위는 스파이크 후 4절에 확정하기 전까지 넓히지 않는다. (2026-10-28 scan v1 포함 확정으로 "만들지 않는다"에서 "범위 한정"으로, 2026-11-04 초기 전체 비교 추가, 2026-11-07 Figma↔코드 첫 QA로 바꿈) | `ng-003` |
| N5 | AC3·AC4·AC11 | 실제 변화가 없는 요청(같은 담당자 재지정 등)으로 알림을 만들지 않는다. 정해진 3개(`issue_created`·`fixed`·`recheck_failed`) 외의 알림 종류를 만들지 않는다 | `ng-004` |
| N6 | AC5 | 등록 후 `repro_steps`·`screen_location`·`environment`를 바꾸지 않는다. PATCH는 `status`·`assigned_to`만 받고 다른 필드가 오면 400. 재검증 요청에 재현 절차가 들어와도 원본을 덮어쓰지 않는다 | `ng-005`, `ng-006` |
| N7 | AC6 | `status_history`를 수정·삭제하는 경로를 만들지 않는다. 409로 거부된 요청은 `status_history`를 늘리지 않는다. 같은 상태로의 전이(예: `fixed`→`fixed`)도 409 | `ng-007` |
| N8 | AC6·AC7 | `reported_by`·`verified_by`·`changed_by`와 각 시각을 요청 body에서 받지 않는다 — 항상 로그인 사용자와 서버 시각을 쓴다 | `ng-008` |
| N9 | AC8 | 파싱 API는 DB에 아무것도 쓰지 않는다(이슈 자동 등록 금지). 스키마 검증에 실패하면 원문을 넣거나 재시도로 값을 만들어 내지 않고 실패로 응답한다 | `ng-009` |
| N10 | AC12·AC13 | 다른 프로젝트의 `issueId`로 요청해도 그 데이터를 반환하지 않는다. 테스트용 인증 우회(특정 헤더·플래그)를 운영 코드에 넣지 않는다 | `ng-010` |
| N11 | 전체 | `ontology.yaml`에 없는 상태값·알림 종류·필드를 추가하지 않는다(예: `closed`. `in_progress`는 2026-11-07 ontology에 추가됨) | 스키마 검증 |
| N12 | 전체 | 응답 형태(`{success, data, error}`)와 구현된 AC9·AC10의 응답을 임의로 바꾸지 않는다 | 기존 골든 케이스 |
| N13 | 4절 | 4절 비포함 기능(외부 트래커 연동 — GitHub 이슈·PR 포함, E2E 자동화, 토큰 동기화, 비공개 저장소용 GitHub App)을 구현하지 않는다. GitHub는 연결된 공개 저장소의 코드를 읽기만 한다 | PR 리뷰 |
| N14 | AC17·AC18 | 초대받은 이메일과 다른 계정, 만료·취소·이미 수락된 초대로 멤버를 만들지 않는다. 초대 토큰을 응답 목록·로그에 노출하지 않는다(토큰은 초대 메일의 수락 링크에만 담는다) | `ng-011` |
| N15 | AC16·AC19·AC26 | 소유자 없는 프로젝트를 만들지 않고, 소유자를 제거하지 않는다. 멤버를 제거할 때 이슈·`status_history`·알림·재검증 기록을 지우지 않는다(담당자만 비운다). 이 기록들을 지우는 길은 소유자의 프로젝트 삭제(AC26) 하나뿐이고, 그때도 다른 프로젝트의 데이터와 멤버 계정은 건드리지 않는다 | `ng-012`·`ng-013`·`pj-009`·`pj-010` |
| N16 | 4절·AC20 | 비교 분석·이력 기반 점검 후보의 LLM 결과로 이슈·`StyleMismatch`를 자동 저장하지 않는다(자동 저장은 첫 QA 한 지점뿐 — AC23·N17). 근거(실제 노드·화면·추출값, 실제 이슈 id)가 없는 후보는 버린다. 다른 프로젝트의 데이터를 검색·근거로 쓰지 않는다 | `ng-014`~`ng-016` |
| N17 | AC23 | 첫 QA는 Figma API로 읽은 노드 값과, LLM이 인용한 파일·줄에 실제로 있는 코드 값이 **둘 다** 있을 때만 이슈를 만든다. LLM이 말한 값만으로 저장하지 않고, 인용한 파일·줄이 저장소에 없거나 그 값이 없으면 버린다. 실패한 실행의 이슈를 일부만 남기지 않는다. 다른 프로젝트의 연결·매핑을 쓰지 않는다 | `ng-017`~`ng-019` |
