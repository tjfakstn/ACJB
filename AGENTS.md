# AGENTS.md

> 이 저장소에서 작업하는 AI 코딩 에이전트(Claude Code, Cursor, Copilot 등)가 **작업 중 상시 준수할 규칙**을 모은 문서.
> 역할 구분: **왜** 만드는가는 `docs/PROBLEM.md`, **무엇을** 만드는가는 `docs/SPEC.md`, **도메인 구조**의 정본은 `docs/ontology.yaml`이 담당한다. 이 문서는 그 정의를 복제하지 않고 어휘와 불변 규칙만 짧게 추려 가리킨다.
> 사람을 위한 소개는 `README.md`, 기여 규칙은 `CONTRIBUTING.md` 참고.

---

## 1. 제품 맥락

QAting — Figma와 GitHub를 연동해 디자인과 실제 구현의 차이를 자동으로 검증하고, 발견된 QA 이슈를 재현 정보와 함께 구조화해 관리하는 개발 QA 자동화 서비스. 핵심 가치는 **"매번 반복하는 QA 수작업(화면 대조, 이슈 재현, 재확인)을 구조화된 근거로 대체한다"**. 아주대학교 소프트웨어학과 26-2 캡스톤디자인 팀 '안캡잘부'의 결과물이다.

완성된 결과물보다 **문제를 올바르게 정의하고 검증 가능한 형태로 해결해 나가는 과정**을 우선한다. 에이전트도 완성도 높은 큰 덩어리보다 **검증 가능한 작은 단위**로 작업할 것.

## 2. 도메인 용어집

어휘 층 발췌 — 전체 구조·관계·근거는 `docs/ontology.yaml`.

- **Project**: Figma 파일(`figma_file_key`) + GitHub 저장소(`github_repo`) 1개씩을 연결하는 작업 단위
- **FigmaFrame**: `frame_id`, `name`, `figma_node_url` — Design Diff 비교의 기준(expected) 쪽
- **DesignDiff**: `diff_type`(color/typography/spacing/radius/shadow/layout/text_content), `property`, `expected_value`(Figma 기준값), `actual_value`(실제 값), `severity`, `status`(open/ignored/resolved)
- **QAIssue**: `type`(bug/design_mismatch/feature), `repro_steps`, `environment`, `screenshots`, `status`(open/in_progress/resolved/closed/wontfix), `github_issue_url`
- **TestDataset**: `name`(시나리오명), `seed_spec`, `depends_on_external`(외부 서비스 의존 시 true — 싱크 이슈 위험 표시)
- **VerificationResult**: `result`(pass/fail), `verified_by`(사람 또는 AI), `evidence_url` — `qa_issue_id`로 QAIssue에 종속

혼동 주의: `DesignDiff.status`(open/ignored/resolved)와 `QAIssue.status`(open/in_progress/resolved/closed/wontfix)는 값 집합이 다르다 — 혼용하지 말 것.

## 3. 절대 규칙 (위반한 결과물은 수용하지 않는다)

1. Design Diff는 항상 Figma 기준값(`expected_value`)과 실제 값(`actual_value`)을 함께 제시한다 — 근거 없이 "다름"만 반환하지 않는다. (↔ SPEC.md AC2)
2. QA 이슈 생성 시 재현 정보(대상 URL, 스크린샷)를 자동으로 채운다 — 사람이 다시 입력하게 하지 않는다. (↔ AC3)
3. 존재하지 않는 리소스 ID로 요청이 오면 항상 404 + 표준 오류 형식(`{success:false, error:{code,message}}`)을 반환한다 — 데이터를 임의로 만들어내지 않는다. (↔ AC6)
4. 재검증 결과를 등록할 때는 최초 등록된 재현 절차(`repro_steps`)를 그대로 이력에 남긴다 — 재입력을 요구하지 않는다. (↔ AC5)
5. 시크릿(Google Client Secret, DB 비밀번호 등)을 코드나 로그에 남기지 않는다.

## 4. 금지 사항 (에이전트에게 위임할 때 항상 걸린다)

1. **테스트와 골든 케이스를 스스로 판단해 고치지 않는다.** `tests/` 아래 파일과 `tests/harness/golden_cases.yaml`의 변경은 사람이 승인한다. 실패하면 테스트가 아니라 구현을 고친다. 사람이 지시한 변경과 포매팅 정리는 예외이나, 그때도 단언문·기대값·skip 조건은 건드리지 않는다.
2. **완료 조건을 임의로 좁히지 않는다.** skip, xfail, 케이스 삭제로 테스트를 통과시키지 않는다.
3. **근거 없는 결과를 보고하지 않는다.** 통과했다면 각 케이스를 왜 통과하는지 한 줄씩 설명한다.
4. 요청하지 않은 범위까지 임의로 수정하지 않는다.
5. 확인되지 않은 API 스펙을 추측해서 구현하지 않는다 — 모르면 물어본다.
6. `main` 브랜치에 직접 커밋하지 않는다 (7절 Git 규칙 참고).

**모호할 때**: 추측해서 진행하지 말고, 확인이 필요한 지점을 명시하고 멈춘다.

## 5. 코딩 컨벤션

- Frontend: TypeScript strict 모드, 컴포넌트 PascalCase, 함수/변수 camelCase
- Backend: Java, Spring Boot 컨벤션(Controller/Config 패키지 분리)
- 상수: UPPER_SNAKE_CASE
- 주석은 "무엇"보다 "왜"를 남긴다
- 외부 API(Figma, GitHub) 응답은 반드시 타입/스키마로 검증한 뒤 사용한다
- **목록을 반환하는 API는 정렬 기준을 명시적으로 고정한다**(예: `created_at desc`). 같은 조건에 순서가 흔들리면 골든 케이스가 성립하지 않는다.

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
| DB | TODO | |
| 인증 | Google OAuth2 (구현됨) | |
| 외부 연동 | Figma API, GitHub API | |
| 인프라 / 배포 | TODO | |

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
- Figma / GitHub / Google 토큰은 로컬에서만 사용하고 로그에 출력하지 않는다

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

- API 명세: [docs/openapi.yaml](docs/openapi.yaml) / [docs/api_spec.md](docs/api_spec.md)
- 프로젝트 현황 스냅샷: [docs/ssot.md](docs/ssot.md)
- 인터뷰 로그: [docs/research/interviews.md](docs/research/interviews.md)
- 배포 주소: TODO

### 7.7 진행 상태

구현 진행 상태는 이 문서에 적지 않는다 — `./gradlew test` / `npm run build` 결과와 `docs/decision_log.md`가 원천이다.
