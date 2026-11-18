# tests — 골든 케이스(결정 층)

QAting은 품질을 두 층으로 본다.

| 층 | 묻는 것 | 어디에 |
|---|---|---|
| **결정 층** | 항상 지켜야 하는 규칙을 지키는가 — 한 번이라도 어기면 실패 | 이 폴더의 [`harness/golden_cases.yaml`](harness/golden_cases.yaml) + 백엔드 테스트 |
| 통계 층 | LLM 기능이 같은 AC를 얼마나 자주 지키는가 — 비율·합격선 | [`evals/`](../evals/) — 설계는 [`evals/EVAL_DESIGN.md`](../evals/EVAL_DESIGN.md) |

통계 층의 지표·데이터셋·러너는 EVAL_DESIGN.md에만 적는다. 이 문서는 결정 층만 다룬다.

## 골든 케이스란

- [`harness/golden_cases.yaml`](harness/golden_cases.yaml)의 각 케이스는 [`docs/SPEC.md`](../docs/SPEC.md)의 수용 기준(AC) 하나, 또는 6.1절 금지 기준(N번호, `ng-*` 케이스)에 대응한다. **SPEC의 AC1~AC37 모두에 케이스가 있다.**
- 케이스는 `id`, `ac`, `category`, `description`, `input`(엔드포인트·본문·요청자 `actor`·사전 조건 `precondition`), `expected`(상태 코드, 응답에 있어야 할 키·값, 사후 조건 `followup`)로 적는다.
- `category`: `normal`(정상 동작) · `boundary`(경계·예외 입력 — 거부, 0건, 만료, 한도, 같은 요청 반복 등) · `forbidden`(금지 행동 — SPEC 6.1절 N번호, `ng-*`는 `rule`에 N번호). 138케이스 = normal 47 · boundary 69 · forbidden 22.
- **판정 규칙**(파일 머리말과 같음): `expected`에 적힌 키가 응답에 있고 조건을 만족하면 통과한다. 적히지 않은 키는 판정에 쓰지 않는다. `actor`를 생략하면 그 프로젝트 멤버로 로그인한 사용자다.

## 테스트 하니스 — 케이스 파일을 읽어 판정한다

백엔드가 Java(Spring)라 하니스도 JUnit이다(강의 지침의 `tests/test_<기능>_golden.py`에 해당). 실행은 `cd backend && ./gradlew test` 하나로 끝난다(MySQL 기동 등 준비는 [CONTRIBUTING.md "시작하기"](../CONTRIBUTING.md#시작하기)).

| 파일 (`backend/src/test/java/com/acjb/server/golden/`) | 하는 일 |
|---|---|
| [`GoldenApiHarnessTest.java`](../backend/src/test/java/com/acjb/server/golden/GoldenApiHarnessTest.java) | `harness: api`인 케이스(34개)를 케이스 파일에서 읽어, `given`대로 사전 조건을 만들고 `input`대로 실제 API를 호출해 `expected`로 판정한다. 대상: QA 이슈 등록 필수값·StyleMismatch 근거·담당 팀/등록자 알림·상태 전이·재현 절차 보존·재검증·인증/권한(AGENTS.md 절대 규칙 1~7)과 공개 엔드포인트. 처리할 수 없는 판정 키가 있으면 실패로 본다 |
| [`GoldenCatalogTest.java`](../backend/src/test/java/com/acjb/server/golden/GoldenCatalogTest.java) | 케이스 파일 자체 검사 — id 형식·중복, `ac`가 SPEC에 있는가, `category`, 금지 케이스의 N번호, SPEC의 모든 AC에 케이스가 있는가, **모든 케이스가 실행되는 테스트에 연결되는가** |
| `GoldenCases.java`, `GoldenCase.java` | 케이스·SPEC 읽기, 테스트 메서드에 케이스 id를 다는 `@GoldenCase` |

`given`(하니스가 읽는 사전 조건): 기본 세계는 member-1 소유자(planner), member-2 frontend, member-3 designer, member-9 다른 프로젝트 멤버. `as`(요청자, `anonymous`는 미인증), `members`(역할 바꾸기·더하기), `issue`(`status`·`assigned_team`·`reported_by`·`source`), `removed`(이슈를 만든 뒤 프로젝트에서 뺀 멤버). 사람이 읽는 `precondition`·`actor` 글은 그대로 둔다.

하니스가 실행하지 않는 케이스(104개 — 외부 API 가짜 응답·비동기 작업·스트림처럼 사전 조건을 코드로 만들어야 하는 것)는 그 동작을 검증하는 백엔드 테스트 메서드에 연결된다.

- **이름으로 연결**: 메서드 이름 앞에 케이스 id — 예: `qi-001` → `QaIssueApiTest.qi001_…`, `ng-003` → `StyleScanApiTest.ng_003_…`
- **표시로 연결**: 메서드에 `@GoldenCase("iv-001")` — 케이스를 교체했거나 나중에 만든 경우
- 연결되지 않은 케이스가 하나라도 있으면 `GoldenCatalogTest`가 실패한다(2026-11-18부터 0개).

결함 주입 기록(하니스가 구현 결함을 실제로 잡는지): [`docs/prompts/delegation_golden.md`](../docs/prompts/delegation_golden.md) — 결함 9건 모두 잡음.

## 바꾸는 규칙

- `tests/` 아래 파일과 `golden_cases.yaml`의 변경은 **사람이 승인**한다([AGENTS.md 4장 1항](../AGENTS.md)). 에이전트는 테스트가 실패하면 테스트가 아니라 구현을 고친다.
- skip·xfail·케이스 삭제로 통과시키지 않는다(4장 2항).
- 케이스를 바꾼 이유는 [`docs/decision_log.md`](../docs/decision_log.md)에 남겨 왔다(예: 2026-10-28 `ng-003` 갱신, 설만수 승인). 바꿀 때는 대응하는 테스트 메서드 이름이나 Javadoc의 id도 함께 맞춘다.
