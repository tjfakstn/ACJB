# 프로젝트 목록 & 연결 플로우 (02 Projects)

로그인 이후 첫 화면. Figma `안캡잘부-캡스톤디자인` 02 Projects — Refined 프레임 기반.

- 라우트: `/app/projects` (`src/pages/ProjectsPage.tsx`)
- Figma: node-id `424-3239` (02 Projects & Connect — Refined)

## 화면/상태

| 상태 | Figma | 설명 |
| --- | --- | --- |
| 빈 상태 | 02-2 | 연결된 프로젝트 0개. 점선 박스 + 1·2·3 단계 안내 (`EmptyState`) |
| 목록 | 02-1 | 카드 1개 이상. 상단 하이라이트 스트립 + 툴바 + 카드 리스트 |
| 연결 팝업 1 | 02-3 | 기본 정보(프로젝트 이름/설명) 입력 |
| 연결 팝업 2 | 02-4 | 서비스 연결(GitHub/Figma 링크) |
| 연결 팝업 3 | 02-6 | 분석 진행(스피너 + 진행률 + 3단계) → 완료 시 목록에 카드 추가 |

플로우: 빈 상태 → `새 프로젝트 연결` → 팝업(1→2→3) → `onComplete` → 목록에 카드 추가.

## 컴포넌트

- `ProjectsPage` — 페이지. 인증 가드(`fetchMe`) + 목록 상태 + 팝업 열고닫기.
- `AppHeader` — 로그인 후 공통 헤더(로고/알림/계정).
- `HighlightStrip` — 전체 수 + FAIL/이상없음 분포 바.
- `ProjectToolbar` — 검색/상태/정렬 (표시만, 미연동).
- `ProjectListItem` — 02-1 카드 한 줄.
- `EmptyState` — 02-2 빈 상태.
- `ConnectProjectDialog` — 3단계 연결 팝업(분석은 프론트 시뮬레이션).
- `types.ts` — `Project`, `ConnectProjectInput`.

## 디자인 토큰

`src/index.css`의 `@theme`에 `--color-qa-*`(primary/logo/success/warning/sub/border/surface) 추가 — Figma 변수에서 가져옴.
`bg-qa-primary`, `text-qa-success` 처럼 Tailwind 유틸로 사용.
→ **최유정**: 공용 디자인 토큰 정본으로 승격할지, 공용 `Button`에도 반영할지 확정 필요.

## 연결 성공/실패 규칙 (구현됨)

- **성공했을 때만 목록에 추가된다.** 연결/분석에 실패하면 팝업이 02-5 오류 화면으로 남고
  `onComplete`가 호출되지 않으므로, 실패한 프로젝트는 '모든 프로젝트' 목록에 뜨지 않는다.
- **QA 결과 / FAIL(불일치) 건수는 백엔드 결과값만 쓴다.** 프론트에서 지어내지 않는다.
  `attemptConnect`가 돌려준 `{ qaCount, failCount }`를 그대로 카드에 넣는다(`connectProject.ts`).
- 오류는 서비스(GitHub/Figma)별로 표시된다. 실패한 서비스 카드에 오류 표시 + 상단 배너가 뜨고,
  해당 링크를 수정하면 `시작하기`가 다시 활성화된다.

## 백엔드 연동 TODO (미구현)

말씀대로 **아직 로그인/연동 전**이라 목록·분석·집계는 스텁(`connectProject.ts`)이다. 이어서 할 일:

1. **목록 조회 / 프로젝트 연결 API 신설** — `openapi.yaml`에는 `/projects/{projectId}/...` 만 있고
   목록 조회(`GET /projects`)·연결 생성(`POST /projects`)이 없음. 스펙 확정 필요(오단비/설만수).
   - 목록 API는 정렬 기준을 명시적으로 고정할 것(AGENTS.md §5, 예: `created_at desc`).
2. **GitHub / Figma 링크 유효성 검증 (백엔드)** — 입력값이 형식만 맞는 게 아니라
   **실제 존재하고 접근 가능한 GitHub repo / Figma 파일 링크**인지 서버에서 검증해야 함.
   프론트는 비어있는지 정도만 보고, 유효성의 정본은 서버 응답으로 판단한다.
   현재 `attemptConnect`는 임시 스텁(링크가 비었거나 'fail' 포함 시 실패)으로, 실제 검증 호출로 교체.
3. **QA 결과 / FAIL(불일치) 집계** — `attemptConnect` 성공 응답의 `qaCount`/`failCount`를
   서버 분석 결과(figma sync + style-mismatches scan 집계)로 채운다. 현재 스텁은 0을 반환.
4. **분석 진행률** — 현재 1.1초 간격 더미. 실제 분석 작업 상태 폴링으로 교체.
5. `ProjectsPage` 의 마운트 로딩(`GET /projects`)과 `handleComplete`의 id 등을 실제 API 응답으로 교체.
