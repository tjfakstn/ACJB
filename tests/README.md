# tests — 골든 케이스(결정 층)

QAting은 품질을 두 층으로 본다.

| 층 | 묻는 것 | 어디에 |
|---|---|---|
| **결정 층** | 항상 지켜야 하는 규칙을 지키는가 — 한 번이라도 어기면 실패 | 이 폴더의 [`harness/golden_cases.yaml`](harness/golden_cases.yaml) + 백엔드 테스트 |
| 통계 층 | LLM 기능이 같은 AC를 얼마나 자주 지키는가 — 비율·합격선 | [`evals/`](../evals/) — 설계는 [`evals/EVAL_DESIGN.md`](../evals/EVAL_DESIGN.md) |

통계 층의 지표·데이터셋·러너는 EVAL_DESIGN.md에만 적는다. 이 문서는 결정 층만 다룬다.

## 골든 케이스란

- [`harness/golden_cases.yaml`](harness/golden_cases.yaml)의 각 케이스는 [`docs/SPEC.md`](../docs/SPEC.md)의 수용 기준(AC) 하나, 또는 6.1절 금지 기준(N번호, `ng-*` 케이스)에 대응한다.
- 케이스는 `input`(엔드포인트·본문·요청자 `actor`)과 `expected`(상태 코드, 응답에 있어야 할 키, 조건)로 적는다.
- **판정 규칙**(파일 머리말과 같음): `expected`에 적힌 키가 응답에 있고 조건을 만족하면 통과한다. 적히지 않은 키는 판정에 쓰지 않는다. `actor`를 생략하면 그 프로젝트 멤버로 로그인한 사용자다.

## 백엔드 테스트와 연결되는 방식

YAML을 직접 읽어 실행하는 러너는 없다. 대신 백엔드 테스트 메서드가 케이스를 하나씩 코드로 옮겨 단언한다.

- **이름으로 연결**: 메서드 이름 앞에 케이스 id를 붙인다 — 예: `qi-001` → `QaIssueApiTest.qi001_screen_location이_없으면_400이고_빠진_항목_이름을_담는다`, `ng-003` → `StyleScanApiTest.ng_003_유효한_요청은_202_queued`
- **주석으로 연결**: 케이스를 교체한 뒤 메서드 이름을 그대로 둔 곳은 메서드 위 Javadoc에 id를 적는다 — 예: `ProjectManagementApiTest`의 `/** pj-003(2026-11-07 교체) … */`
- 실행은 `./gradlew test` 하나로 끝난다(MySQL 기동 등 준비는 [CONTRIBUTING.md "시작하기"](../CONTRIBUTING.md#시작하기)).

### 대응 현황 (2026-11-12, grep 기준)

케이스 106개(id `nt-007`이 두 번 쓰여 고유 id 105개) 중 **89개가 테스트와 연결**되고(이름 83, 주석 6), **16개는 id로 연결된 테스트가 없다**.

| 테스트 클래스 | 연결된 케이스 |
|---|---|
| `controller/AnalysisApiTest` | an-001 ~ an-006 |
| `controller/ConnectionApiTest` | cn-001 ~ cn-008 |
| `controller/InvitationApiTest` | iv-003, iv-008, iv-009 (주석) |
| `controller/ParseApiTest` | pc-001, ng-009 |
| `auth/ProjectAccessTest` | au-001, au-002 |
| `controller/ProjectDeletionApiTest` | pj-009, pj-010, pj-011 |
| `controller/ProjectManagementApiTest` | pj-003·pj-004·pj-008(주석), pj-005, pj-007, rl-001 ~ rl-003, ws-001, ws-002 |
| `controller/QaIssueApiTest` | qi-001, qi-002, sm-001, nt-001, nt-004, ng-001, ng-002, ng-008, ng-010 |
| `controller/QaIssuePatchApiTest` | st-002 ~ st-007, vf-001, nt-002, nt-003, nt-007, nt-008, au-003, au-004, au-006, au-007, au-010, ng-004, ng-005, ng-007 |
| `controller/QaRunApiTest` | fq-001 ~ fq-003, fq-005, rq-001 ~ rq-004, ng-017, ng-019 |
| `controller/RecheckSuggestionApiTest` | rs-001 ~ rs-004, ng-014 ~ ng-016 |
| `controller/StyleScanApiTest` | ng-003 |
| `controller/VerificationApiTest` | st-001, vf-002, vf-003, nt-005, nt-006, au-005, au-008, au-009, ng-006 |

**id로 연결된 테스트가 없는 케이스(16)**: health-001, auth-001, pj-001, pj-002, pj-006, iv-001, iv-002, iv-004, iv-005, iv-006, iv-007, ng-011, ng-012, ng-013, ng-018, fq-004.
같은 동작을 id 없이 검증하는 테스트가 있을 수 있다. 여기서는 "id로 추적되지 않는다"는 사실만 적는다.

## 바꾸는 규칙

- `tests/` 아래 파일과 `golden_cases.yaml`의 변경은 **사람이 승인**한다([AGENTS.md 4장 1항](../AGENTS.md)). 에이전트는 테스트가 실패하면 테스트가 아니라 구현을 고친다.
- skip·xfail·케이스 삭제로 통과시키지 않는다(4장 2항).
- 케이스를 바꾼 이유는 [`docs/decision_log.md`](../docs/decision_log.md)에 남겨 왔다(예: 2026-10-28 `ng-003` 갱신, 설만수 승인). 바꿀 때는 대응하는 테스트 메서드 이름이나 Javadoc의 id도 함께 맞춘다.
