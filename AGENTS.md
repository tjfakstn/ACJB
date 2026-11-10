# AGENTS.md

> 이 저장소에서 작업하는 AI 코딩 에이전트(Claude Code, Cursor, Copilot 등)가 **작업 중 상시 준수할 규칙**을 모은 문서.
> 역할 구분: **왜** 만드는가는 `docs/PROBLEM.md`, **무엇을** 만드는가는 `docs/SPEC.md`, **도메인 구조**의 정본은 `docs/ontology.yaml`이 담당한다. 이 문서는 그 정의를 복제하지 않고 어휘와 불변 규칙만 짧게 추려 가리킨다.
> 사람을 위한 소개는 `README.md`, 기여 규칙은 `CONTRIBUTING.md` 참고.

---

## 1. 제품 맥락

QAting — QA 이슈를 재현 정보와 함께 등록하고, 조치가 끝나면 등록자에게 바로 알리고, 등록 때의 재현 절차를 그대로 불러와 재검증하는 QA 자동화 서비스. 핵심 가치는 **"이슈 전달·조치 확인·재확인의 왕복(재질문, 목록 직접 확인, 재현 절차 재입력)을 구조화된 기록과 알림으로 줄인다"**. 아주대학교 소프트웨어학과 26-2 캡스톤디자인 팀 '안캡잘부'의 결과물이다. (v0.2, 2026-09-28 — 인터뷰 12건 기반으로 문제 정의·스펙 재검증, 배경: `docs/decision_log.md`)

완성된 결과물보다 **문제를 올바르게 정의하고 검증 가능한 형태로 해결해 나가는 과정**을 우선한다. 에이전트도 완성도 높은 큰 덩어리보다 **검증 가능한 작은 단위**로 작업할 것.

## 2. 도메인 용어집

어휘 층 발췌 — 전체 구조·관계·근거는 `docs/ontology.yaml`.

- **QAIssue**: `description`, `screen_location`, `repro_steps`, `platform_scope`(common/aos/ios — 이슈 분류용), `screenshots`, `figma_link`, `status`(needs_check/in_progress/fixed/needs_recheck/resolved — `in_progress`는 건너뛸 수 있음), `status_history`, `assigned_team`(planner/designer/frontend/backend — 담당 팀, 프로젝트 역할과 같은 값, 2026-11-08), `reported_by`, `source`(manual/first_qa)
- **StyleMismatch**: `property`(color/font_weight/font_size/font_family/radius/shadow/spacing/text), `expected_value`(Figma 기준값), `actual_value`(구현 값) — 둘 다 있어야 저장(↔ AC2). QAIssue에 연결되면 `style_mismatch` 필드. 자동 추출은 v1 포함: 프로젝트 초기에는 Figma ↔ GitHub 코드 **첫 QA**(비교 분석으로 매핑 → 소유자가 [첫 QA 실행], 프로젝트당 성공 1번)가 근거가 둘 다 있는 차이만 이슈로 자동 저장하고(N17), 이후에는 단일 컴포넌트 scan(웹·color/font_weight/radius 한정) — `docs/SPEC.md` 4절, 2026-11-07
- **Verification**: `result`(pass/fail), `scope`, `source`(manual/re_qa), `observed_value`(Re-QA가 다시 읽은 구현 값), `verified_by`, `verified_at` — `qa_issue_id`로 QAIssue에 종속. `pass`→`resolved`, `fail`→`needs_recheck`. 사람이 등록하거나(등록자), 소유자가 실행한 **자동 Re-QA**가 만든다(첫 QA 이슈만, 2026-11-08)
- **Notification**: `event`(issue_created/fixed/recheck_failed), `channel` — 담당 팀 지정 시 그 팀 역할 멤버 각각에게, 조치 완료(`fixed`) 시 등록자에게, 재검증 `fail` 시 담당 팀 역할 멤버 각각에게 정확히 1건씩
- **IssueReportContext**: 자유서술 텍스트 파싱 결과 — `screen`, `element`, `symptom`, `repro_steps`, `platform_scope`. 텍스트에 없는 값은 지어내지 않고 비워 `missing`에 담아 되물음
- **Environment**: `platform`(aos/ios/web), `device`, `browser`, `deploy_stage`(local/staging/production) — QAIssue의 실제 발생 환경
- **FigmaFrame**(`name`, `frame_url`) / **Screen**(`name`, `entry_condition`): 각각 디자인 기준 화면 / 배포된 실제 구현 화면. `StyleMismatch`가 이 둘을 비교
- **Member**: QA에 참여하는 팀원 — 구글 로그인 이메일로 식별, 처음 로그인할 때 생성. `role`(designer/frontend/backend/planner)은 **프로젝트마다** 따로 있다(초대받은 사람은 수락할 때 고르고, 만든 사람은 미지정으로 시작, 이후 변경은 소유자만)
- **Project**: QA 단위 — 로그인한 사용자 누구나 만들고, 만든 사람이 소유자(owner). 멤버 제거·역할 변경·Figma/GitHub 연결·비교 분석·첫 QA 실행·Re-QA 실행은 소유자만. 열면 작업공간 홈으로 간다
- **ManualQaPin**: 실시간 QA 워크스페이스에서 배포 화면(또는 첨부 이미지) 위에 찍은 수동 QA 위치 — `pin_number`(화면 ID `QA-M###`), `title`, `page_path`, `anchor`(px·비율), `viewport`, `location_basis`(live_overlay/capture_image), `capture_status`(captured/failed/user_attached/none), 선택 `severity`·`expected_result`·`actual_result`. QAIssue(`source: manual`)에 1:1로 붙는다. 배포 주소는 `Project.deploy_url`, 없으면 GitHub 최근 성공 배포 주소(AC29~AC31, 2026-11-10)
- **Invitation**: 멤버가 구글 이메일로 보낸 초대 — `status`(pending/accepted/canceled/expired), 7일 만료(재전송하면 그 초대를 7일 연장). 같은 구글 계정으로 로그인해 수락해야 멤버가 된다. 시드 데이터로 멤버를 넣던 방식은 폐기(2026-11-04)

혼동 주의: `QAIssue.platform_scope`(이슈 분류용, common 포함)와 `Environment.platform`(실제 발생 환경, aos/ios/web만)은 다른 값 집합이다 — 혼용하지 말 것. `IssueReportContext.screen`(파싱 결과, 화면 이름만)과 `QAIssue.screen_location`(자유 텍스트, screen+element가 합쳐진 값)도 다른 필드다 — 파싱 응답을 그대로 DB 컬럼에 넣지 않는다(`src/qating/prompts/parse_query.md` 필드 매핑 표 참고). GitHub **이슈·PR** 연동(`github_issue_url` 등)은 v1 비포함(`docs/openapi.yaml`에 `deprecated: true`로 설계만 남음) — 새로 만들지 말 것. GitHub는 연결된 공개 저장소의 코드·커밋·PR을 읽기만 한다(첫 QA 2026-11-07, 이슈 상세 코드 추적 AC34 2026-11-10) — 이슈·PR을 만들거나 바꾸지 않는다.

## 3. 절대 규칙 (위반한 결과물은 수용하지 않는다)

1. QA 이슈 등록 시 `description`·`screen_location`·`repro_steps`·`platform_scope` 중 하나라도 비어 있으면 등록을 거부(400)한다 — 빠진 항목을 지어내지 않는다. (↔ SPEC.md AC1)
2. `StyleMismatch`는 `expected_value`와 `actual_value`가 **둘 다** 있을 때만 저장한다 — 근거 없이 "다름"만 저장하지 않는다. (↔ AC2)
3. QA 이슈가 등록될 때 담당 팀(`assigned_team`)이 지정돼 있거나 이후 PATCH로 담당 팀이 새로 지정·변경되면, 그 프로젝트에서 역할이 그 팀인 멤버 **각각에게** `Notification`(`issue_created`)을 **정확히 1건씩** 생성한다(팀이 없거나 그 역할 멤버가 없으면 생성하지 않는다). 상태가 `fixed`로 바뀌면 등록자에게 `Notification`(`fixed`)을, 재검증(수동·Re-QA) `fail`로 `needs_recheck`가 되면 담당 팀 역할 멤버 각각에게 `Notification`(`recheck_failed`)을 정확히 1건씩 생성한다 — 중복 생성하거나 누락하지 않는다. (↔ AC3, AC4, AC11)
4. 정해진 상태 전이만 허용한다: `needs_check`/`needs_recheck` → `in_progress`, `in_progress` → `fixed`, `needs_check`/`needs_recheck` → `fixed`는 PATCH로만, `fixed` → `resolved`/`needs_recheck`는 재검증 등록이나 Re-QA로만 일어난다. 그 외 요청은 거부(409)하고, 허용된 모든 변경은 `status_history`에 남긴다. (↔ AC6)
5. 재검증을 조회·등록할 때는 최초 등록된 재현 절차(`repro_steps`)를 그대로 반환한다 — 재입력을 요구하지 않는다. (↔ AC5)
6. `/projects/{projectId}/**`는 로그인한 그 프로젝트 멤버만 호출한다(미인증 401, 비멤버 403). (↔ AC12)
7. `in_progress`·`fixed` 처리는 프로젝트 역할이 그 이슈의 담당 팀인 멤버만(팀이 없으면 누구도 불가), 재검증 등록은 그 이슈의 등록자만(등록자가 프로젝트에서 제거됐으면 소유자), Re-QA 실행은 소유자만 할 수 있다(그 외 403). 권한 검사를 상태 전이 검사보다 먼저 하고, 거부된 요청은 아무 데이터도 바꾸지 않는다. (↔ AC13)

## 4. 금지 사항 (에이전트에게 위임할 때 항상 걸린다)

1. **테스트와 골든 케이스를 스스로 판단해 고치지 않는다.** `tests/` 아래 파일과 `tests/harness/golden_cases.yaml`의 변경은 사람이 승인한다. 실패하면 테스트가 아니라 구현을 고친다. 사람이 지시한 변경과 포매팅 정리는 예외이나, 그때도 단언문·기대값·skip 조건은 건드리지 않는다.
2. **완료 조건을 임의로 좁히지 않는다.** skip, xfail, 케이스 삭제로 테스트를 통과시키지 않는다.
3. **근거 없는 결과를 보고하지 않는다.** 통과했다면 각 케이스를 왜 통과하는지 한 줄씩 설명한다.
4. 요청하지 않은 범위까지 임의로 수정하지 않는다.
5. 확인되지 않은 API 스펙을 추측해서 구현하지 않는다 — 모르면 물어본다.
6. `main` 브랜치에 직접 커밋하지 않는다 (7절 Git 규칙 참고).
7. 시크릿(Google Client Secret, DB 비밀번호 등)을 코드나 로그에 남기지 않는다.
8. `docs/SPEC.md` 6.1절 금지 기준(N1~N18)을 어기지 않는다 — 값을 지어내 채우거나, 일부만 저장하거나, 정해지지 않은 상태·알림·엔드포인트를 만들지 않는다.

**모호할 때**: 추측해서 진행하지 말고, 확인이 필요한 지점을 명시하고 멈춘다.

## 5. 코딩 컨벤션

- Frontend: TypeScript strict 모드, 컴포넌트 PascalCase, 함수/변수 camelCase
- Backend: Java, Spring Boot 컨벤션(Controller/Config 패키지 분리)
- 상수: UPPER_SNAKE_CASE
- 주석은 "무엇"보다 "왜"를 남긴다
- 외부 API(Figma) 응답은 반드시 타입/스키마로 검증한 뒤 사용한다
- **목록을 반환하는 API는 정렬 기준을 명시적으로 고정한다**(예: `created_at desc`). 같은 조건에 순서가 흔들리면 골든 케이스가 성립하지 않는다.
- **LLM 구조화 출력은 수신 후 반드시 JSON Schema로 검증한다.** `parse_query.md`가 반환한 JSON은 `IssueReportContext.schema.json`(특히 `required`·`additionalProperties: false`)으로 검증하고, 스키마를 벗어나면 파싱 실패로 처리한다 — LLM 출력을 검증 없이 그대로 신뢰하지 않는다.

## 6. 완료의 정의

- 관련 골든 케이스(`tests/harness/golden_cases.yaml`) 통과
- 백엔드: `./gradlew test` 통과 / 프론트: `npm run build` 통과
- 변경 근거를 실제 실행 결과(curl, 브라우저 확인 등)로 설명 가능 — "동작할 것 같다"가 아니라 **실제로 실행해 본 결과**를 근거로 삼는다
- UI 변경 시 Figma 화면과의 차이를 스크린샷으로 PR에 첨부한다

---

## 7. 운영 정보

### 7.1 팀 & 역할

| 이름 | 역할 | 주 담당 영역 |
| --- | --- | --- |
| 오단비 | 기획 · 개발 보조 | 제품 정의, 스펙 |
| 설만수 | 개발 | 전체 개발 |
| 최유정 | 디자인 | Figma 디자인 시스템, 브랜드 |

- 제품 스펙/우선순위 결정 → 오단비
- 코드·개발 전반 → 설만수 (오단비가 개발 보조)
- UI 컴포넌트·디자인 토큰 변경 → 최유정

에이전트는 위 영역에 영향을 주는 변경을 할 때, PR 설명에 담당자를 명시할 것.

### 7.2 기술 스택

| 영역 | 스택 | 비고 |
| --- | --- | --- |
| Frontend | React + Vite + TypeScript + Tailwind CSS | Spring API를 호출하는 순수 SPA |
| Backend | Spring Boot(Java 21) | |
| DB | MySQL | 개발 단계부터 MySQL 사용(2026-10-28 결정 — 배포 때 DB를 바꾸지 않는다). 접속 정보는 `.env`, 키는 `.env.example`에 추가 |
| 인증 | Google OAuth2 (구현됨) | |
| 외부 연동 | Figma API, GitHub API(공개 저장소 코드·커밋·PR 읽기 — 첫 QA 2026-11-07, 코드 추적 2026-11-10), Claude API(parse·비교 분석·첫 QA·이력 기반 점검 후보), Gmail SMTP(초대 메일), Voyage AI(임베딩 — 이력 기반 점검 후보 검색, 2026-11-05) | GitHub 이슈·PR 연동은 v1 비포함(v2 후보, `docs/openapi.yaml` `deprecated: true`) |
| 인프라 / 배포 | AWS Lightsail(서울, Ubuntu 24.04) 1대 + docker compose — Caddy(HTTPS·프론트 정적 파일)·백엔드·MySQL (2026-11-10) | 구성·절차는 `deploy/README.md`, 값은 서버의 `deploy/.env` |

결정 배경: [docs/decision_log.md](docs/decision_log.md)

### 7.3 저장소 구조

```
.
├── AGENTS.md
├── CONTRIBUTING.md
├── README.md
├── .github/            # PR/이슈 템플릿
├── backend/            # Spring Boot 프로젝트
├── frontend/           # React + Vite 프로젝트
├── config/
├── data/
├── deploy/             # 운영 배포(docker compose·Caddy·Dockerfile) — 절차는 deploy/README.md
├── docs/               # PROBLEM/SPEC/ontology/openapi/decision_log/ssot/research/spikes
├── evals/
├── src/qating/         # 데이터 모델 스키마(JSON Schema), 프롬프트
└── tests/harness/      # 골든 케이스
```

새 디렉터리를 만들 때는 이 표를 함께 갱신할 것. 문서(`docs/`)와 코드가 어긋나면 문서를 먼저 고칠 것.

### 7.4 개발 환경 & 자주 쓰는 명령어

```bash
# 백엔드
cd backend && cp .env.example .env   # Google OAuth Client ID/Secret 채우기
./gradlew bootRun                    # http://localhost:8080
./gradlew test

# 프론트엔드
cd frontend && npm install
npm run dev                          # http://localhost:5173
npm run build
```

**환경 변수**

- 실제 값은 `.env`에 두고 절대 커밋하지 않는다 (`backend/.env`는 gitignore 처리됨)
- 새 환경 변수를 추가하면 `.env.example`에 키와 설명을 함께 추가한다
- Figma / Google 토큰은 로컬에서만 사용하고 로그에 출력하지 않는다

### 7.5 Git & 협업 규칙

**브랜치**

```
main                     # 배포 가능한 상태 — 절대 직접 작업/커밋 금지. dev에서 병합될 때만 갱신
dev                      # 통합 브랜치 — server + client 통합 검증 + 전 브랜치 공통 문서 작업
server                   # 백엔드(Spring) 통합 브랜치
client                   # 프론트엔드(React) 통합 브랜치
server-feat-<설명>        # server에서 분기하는 기능별 작업 브랜치 (server/feat-... 형태는 git이 브랜치명 충돌로 거부하므로 하이픈 사용)
server-fix-<설명>
client-feat-<설명>
client-fix-<설명>
```

- 기능/수정 단위 작업 흐름: `server`(또는 `client`)에서 `server-feat-<설명>` 처럼 분기 → 작업 완료되면 해당 영역 브랜치로 병합 → 영역 브랜치가 어느 정도 쌓이면 `dev`로 통합
- 아주 작은 수정이면 하위 브랜치 없이 `server`/`client`에 바로 커밋해도 됨
- `dev` → `main`: **배포할 때만** 병합. 병합 전 dev에서 실제로 동작 확인 필수
- **모든 브랜치에 공통으로 적용되는 문서**(`docs/` 전역 문서, `AGENTS.md`, `CONTRIBUTING.md`)는 `server`/`client`가 아니라 **`dev`에서 직접 작업**

**커밋 메시지** — Conventional Commits (`feat` `fix` `docs` `refactor` `test` `chore` `style` `perf`)

**PR** — 하나의 PR은 하나의 목적만. [PR 템플릿](.github/PULL_REQUEST_TEMPLATE.md)에 무엇을/왜/어떻게 검증했는지 작성. `server`/`client`→`dev`는 셀프 머지 가능, `dev`→`main`은 가능하면 팀원 확인 후.

**이슈** — [이슈 템플릿](.github/ISSUE_TEMPLATE/) 3종(버그/기능요청/QA이슈) 사용.

전체 배경: [docs/decision_log.md](docs/decision_log.md)

### 7.6 참고 링크

- API 명세: [docs/SPEC.md](docs/SPEC.md)(정본) / [docs/openapi.yaml](docs/openapi.yaml)(기계가 읽는 버전, 어긋나면 SPEC.md가 정본)
- 프로젝트 현황 스냅샷: [docs/ssot.md](docs/ssot.md)
- 인터뷰 로그: [docs/research/interviews.md](docs/research/interviews.md)
- 배포 주소: TODO

### 7.7 진행 상태

구현 진행 상태는 이 문서에 적지 않는다 — `./gradlew test` / `npm run build` 결과와 `docs/decision_log.md`가 원천이다.
