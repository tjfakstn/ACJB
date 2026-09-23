# API 명세 (초안 v0.1)

> 기계가 읽을 수 있는 버전(OpenAPI 3.0): [openapi.yaml](openapi.yaml) — 이 문서와 항상 동기화 유지할 것
>
> 상태: **초안** — 지금까지 나온 MVP 방향([ssot.md](ssot.md), [decision_log.md](decision_log.md))을 기준으로 한 1차 draft입니다. DB/인증 스택이 아직 TODO라 세부 스펙은 백엔드(Spring) 작업 시작하면서 계속 바뀔 수 있습니다.
> 엔드포인트 설계·확정은 담당자 설만수, 기능 우선순위는 오단비 확인 필요.

---

## 0. MVP 범위

| 우선순위 | 기능 |
|---|---|
| **P0 (MVP)** | Figma 프레임 동기화, Design Diff 스캔 실행/조회, QA Issue 생성(자동+수동), QA Issue → GitHub Issue 연동, QA Issue 목록/상세/상태 변경 |
| P1 (다음) | 재검증(Verification) 이력, GitHub 쪽 상태 역동기화(webhook) |

핵심 흐름: **Figma 프레임 동기화 → Design Diff 스캔 → (차이 발견 시) QA Issue 생성 → GitHub Issue로 전달 → 수정 후 Verification 등록**

---

## 1. 공통 사항

- **Base path**: `/api/v1` (TODO: 실제 배포 도메인 확정 전)
- **인증**: 구글 로그인(OAuth2, 아래 1.5 참고)은 실제로 동작 중. 그 외 QA 도메인 API는 TODO — DB/Auth 스택 미확정이라 초안 단계에서는 프로젝트당 API Key 헤더(`X-API-Key`)를 가정
- **응답 포맷** (공통 envelope, 초안):
  ```json
  {
    "success": true,
    "data": {},
    "error": null
  }
  ```
  실패 시:
  ```json
  { "success": false, "data": null, "error": { "code": "STRING_CODE", "message": "설명" } }
  ```
- **페이지네이션**: 목록 API는 `?page=1&size=20` 쿼리, 응답에 `data.items` + `data.total` (TODO: cursor 기반으로 바꿀지 논의)
- **날짜/시간**: ISO 8601 UTC (`2026-09-14T12:00:00Z`)

---

## 1.5 Auth (실제 구현됨)

다른 섹션과 달리 **이미 동작 중인** 구글 소셜로그인. Base path(`/api/v1`) 밖이 아니라 안에 있음 — 전부 `/api/v1/auth/...`.

### `GET /auth/login/google`
구글 로그인 시작. **브라우저 전체 리다이렉트 전용** (`<a href>` 클릭 등, fetch로 호출 금지 — accounts.google.com이 CORS를 안 열어줘서 fetch로 따라가면 에러남)

### `GET /auth/callback/google`
구글이 인가 코드와 함께 호출하는 콜백. 우리 쪽에서 직접 호출할 일 없음. Google Cloud Console에 등록된 리디렉션 URI와 반드시 일치해야 함: `{baseUrl}/api/v1/auth/callback/google`

### `GET /auth/me`
로그인 상태 확인. 인증 여부 무관하게 200 — `{ "authenticated": false }` 또는 `{ "authenticated": true, "name", "email", "picture" }`

### `GET /auth/logout`
로그아웃. 의도적으로 GET (POST면 프론트에서 CSRF 토큰 처리가 필요한데 아직 안 붙임 — TODO)

세션은 쿠키 기반이며 도메인(host) 기준으로 스코프됨 — 포트는 무관해서 `localhost:8080`에서 만든 세션이 `localhost:5173`(프론트) 요청에도 그대로 붙음. 구현: [backend/src/main/java/com/acjb/server/config/SecurityConfig.java](../backend/src/main/java/com/acjb/server/config/SecurityConfig.java)

---

## 2. Figma 연동

### `POST /projects/{projectId}/figma/sync`
Figma 파일에서 프레임/디자인 토큰(색상, 폰트, spacing 등) 정보를 가져와 동기화
- Request: `{ "figma_file_key": "string", "frame_ids": ["string"] | null }` (frame_ids 없으면 전체 동기화)
- Response: `{ "sync_id": "string", "status": "queued" }` (비동기 처리 가정)

### `GET /projects/{projectId}/figma/sync/{syncId}`
동기화 작업 상태 조회
- Response: `{ "status": "queued|running|done|failed", "frame_count": 12, "finished_at": "..." }`

### `GET /projects/{projectId}/figma/frames`
동기화된 프레임 목록
- Response: `{ "items": [{ "frame_id", "name", "figma_node_url", "last_synced_at" }] }`

---

## 3. Design Diff (핵심 P0)

Figma 프레임과 실제 배포 화면을 대조해 스타일 차이를 찾는 기능.

### `POST /projects/{projectId}/design-diffs/scan`
특정 프레임 vs 실제 배포 URL을 비교하는 스캔 실행
- Request:
  ```json
  {
    "frame_id": "string",
    "target_url": "string",
    "target_selector": "string | null"
  }
  ```
- Response: `{ "scan_id": "string", "status": "queued" }`

### `GET /projects/{projectId}/design-diffs/scans/{scanId}`
스캔 상태 및 결과 요약 조회
- Response: `{ "status": "queued|running|done|failed", "diff_count": 3, "finished_at": "..." }`

### `GET /projects/{projectId}/design-diffs`
발견된 Design Diff 목록
- Query: `frame_id`, `severity`, `status` (open/ignored/resolved)
- Response: `{ "items": [DesignDiff, ...], "total": 10 }`

### `GET /projects/{projectId}/design-diffs/{diffId}`
단일 Diff 상세 (스크린샷 before/after 포함)

### `PATCH /projects/{projectId}/design-diffs/{diffId}`
상태 변경 (예: 의도된 차이라서 `ignored` 처리)
- Request: `{ "status": "ignored", "note": "string" }`

데이터 모델: [DesignDiff.schema.json](../src/qating/schemas/DesignDiff.schema.json)

---

## 4. QA Issue (핵심 P0)

### `POST /projects/{projectId}/design-diffs/{diffId}/issues`
발견된 Diff로부터 QA Issue 생성 (자동 경로)
- Request: `{ "title": "string | null", "priority": "high|medium|low" }` (title 없으면 diff 정보로 자동 생성)
- Response: `QAIssue`

### `POST /projects/{projectId}/qa-issues`
수동 QA Issue 생성 (버그/기능요청 등 Diff와 무관한 이슈, `.github/ISSUE_TEMPLATE/` 3종과 매칭)
- Request:
  ```json
  {
    "type": "bug|design_mismatch|feature",
    "title": "string",
    "description": "string",
    "repro_steps": ["string"],
    "environment": { "device": "string", "browser": "string", "url": "string" },
    "screenshots": ["url"],
    "priority": "high|medium|low"
  }
  ```
- Response: `QAIssue`

### `GET /projects/{projectId}/qa-issues`
목록 조회
- Query: `status`, `priority`, `type`, `assignee`

### `GET /projects/{projectId}/qa-issues/{issueId}`
상세 조회

### `PATCH /projects/{projectId}/qa-issues/{issueId}`
상태/우선순위/담당자 수정
- Request: `{ "status": "open|in_progress|resolved|closed|wontfix", "priority": "...", "assignee": "string" }`

### `POST /projects/{projectId}/qa-issues/{issueId}/github-sync`
GitHub Issue 생성 및 연동 (`.github/ISSUE_TEMPLATE/` 형식에 맞춰 본문 자동 구성)
- Request: `{ "repo": "owner/repo" }`
- Response: `{ "github_issue_url": "string", "github_issue_number": 12 }`

데이터 모델: [QAIssue.schema.json](../src/qating/schemas/QAIssue.schema.json)

---

## 5. Verification (P1)

### `POST /projects/{projectId}/qa-issues/{issueId}/verifications`
수정 완료 후 재검증 결과 등록
- Request: `{ "result": "pass|fail", "evidence_url": "string | null", "notes": "string | null" }`
- Response: `VerificationResult`
- 참고: `result: pass`이면 연결된 QAIssue 상태를 자동으로 `resolved`로 바꿀지는 TODO(정책 논의 필요)

### `GET /projects/{projectId}/qa-issues/{issueId}/verifications`
해당 이슈의 재검증 이력 조회

데이터 모델: [VerificationResult.schema.json](../src/qating/schemas/VerificationResult.schema.json)

---

## 6. 아직 안 정한 것 (TODO)

- 인증/인가 방식 (API Key? OAuth? 세션?) — DB/Auth 스택 확정 후
- 비동기 스캔 작업의 진행 상황을 polling이 아니라 webhook/SSE로 줄 것인지
- GitHub Issue 상태가 바뀌었을 때(닫힘 등) 우리 쪽 QAIssue로 역동기화할지 (webhook 수신)
- 에러 코드 목록 표준화
- 프로젝트/사용자 관리 API (지금은 `projectId`가 이미 있다고 가정)
