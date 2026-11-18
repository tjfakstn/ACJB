# 위임 기록 — 골든 테스트 하니스 (`backend/src/test/java/com/acjb/server/golden/`)

> 강의 지침의 `tests/test_<기능>_golden.py`에 해당한다. 백엔드가 Java(Spring)라 JUnit으로 만들었다.
> 하니스는 판정 기준(케이스 파일)을 실행하는 장치라 에이전트(Claude Code)에 맡기고, 사람이 결함 주입으로 확인한 뒤 에이전트의 임의 수정을 막는다.
> 판정 기준인 케이스의 `input`·`expected`는 위임 범위에서 뺐다 — 에이전트가 기대값을 고치면 그 기대값으로 구현을 판정할 수 없다.

## 1. 위임 (2026-11-18)

배경: 그때까지 골든 케이스(`tests/harness/golden_cases.yaml`)를 직접 읽는 러너가 없고, 백엔드 테스트가 케이스를 코드로 옮겨 메서드 이름 앞 id로만 연결하고 있었다. 이 방식으로는 케이스 파일의 기대값을 바꿔도 테스트 결과가 바뀌지 않고, 106개 중 16개는 연결된 테스트가 없었으며, AC29~AC37은 케이스가 없었다.

```
tests/harness/golden_cases.yaml을 직접 읽어 판정하는 골든 하니스를 backend 테스트로 만들어 줘.
- 하니스(GoldenApiHarnessTest): harness: api인 케이스마다 동적 테스트 1개. 케이스의 input.endpoint·body로 실제 API(MockMvc)를 부르고
  expected의 status·body(적힌 키만 부분 일치)·body_has·body_not_has·error_code·error_message_contains·status_history_last_entry_has·
  repro_steps_match_original과 followup(알림 수·이슈 상태·이력·재검증 수의 변화)을 케이스 파일 값으로 판정한다.
  처리할 수 없는 판정 키가 있으면 통과가 아니라 실패. skip·assume 금지. 케이스마다 트랜잭션을 되돌린다.
- 대상: AGENTS.md 절대 규칙 1~7(AC1~AC7·AC11~AC13) 케이스와 fq-004·health-001·auth-001.
  사람이 읽는 precondition 글은 그대로 두고, 기계가 읽는 given(요청자·멤버 역할·이슈 상태·담당 팀·등록자·제거된 멤버)을 케이스에 더한다.
- 목록 검사(GoldenCatalogTest): id 형식·중복, ac가 SPEC.md에 있는가, category(normal·boundary·forbidden), ng-*는 forbidden과 N번호,
  SPEC의 모든 AC에 케이스가 있는가, 모든 케이스가 하니스나 테스트 메서드(이름 앞 id 또는 @GoldenCase)에 연결되는가.
- 케이스 파일: 모든 케이스에 category를 더하고, 두 번 쓰인 nt-007(AC14 쪽)을 nt-009로, AC29~AC37 케이스를 기존 테스트와 같은 내용으로 더한다.
  연결이 없던 16개는 같은 동작을 검증하는 기존 테스트에 @GoldenCase를 달고, 없으면(ng-013·ng-018) 테스트를 새로 쓴다.
- 하지 말 것: 기존 케이스의 input·expected 수정, 기존 테스트의 단언문 수정, 구현(backend/src/main) 수정, 케이스 id로 분기하는 판정.
끝나면 ./gradlew test 결과와 결함 주입 결과를 보여 줘.
```

에이전트가 한 일

| 파일 | 바꾼 내용 |
|---|---|
| `backend/src/test/java/com/acjb/server/golden/GoldenApiHarnessTest.java` (새 파일) | 케이스 파일을 읽어 34케이스를 실행·판정하는 하니스 |
| `backend/src/test/java/com/acjb/server/golden/GoldenCatalogTest.java` (새 파일) | 케이스 파일 형식·AC 커버리지·테스트 연결 검사 5개 |
| `backend/src/test/java/com/acjb/server/golden/GoldenCases.java`, `GoldenCase.java` (새 파일) | 케이스·SPEC 읽기, 테스트 메서드를 케이스에 잇는 표시 |
| `tests/harness/golden_cases.yaml` | 모든 케이스에 `category`, 하니스 대상 34케이스에 `harness: api`·`given`, `nt-007`(AC14) → `nt-009`, AC29~AC37 케이스 32개 추가(106 → 138). 기존 `input`·`expected`는 그대로 |
| 기존 `*ApiTest` 8개 파일 | 같은 동작을 검증하던 메서드 51개에 `@GoldenCase` 표시만 더함(단언문 변경 없음) |
| `ProjectManagementApiTest`, `ClaudeQaComparatorRulesTest` | 테스트가 없던 `ng-013`(제거된 멤버의 이슈·이력·재검증·알림 보존), `ng-018`(저장소에 없는 파일·매핑에 없는 노드 인용은 버림) 새 메서드 |

결과: `./gradlew test` 전체 통과(아래 3절). 하니스 34/34, 목록 검사 5/5.

## 2. 결함 주입 — 하니스가 실제로 잡는가

구현(`backend/src/main`)에 결함을 하나씩 넣고 `./gradlew test --tests com.acjb.server.golden.GoldenApiHarnessTest`를 돌린 뒤 되돌렸다(스크립트로 넣고 되돌려 원본과 같음을 확인). F9는 구현이 아니라 케이스 파일의 기대값을 바꿔, 하니스가 케이스 파일을 읽어 판정하는지 본 것이다.

| # | 파일 | 바꾼 내용 | 실패한 케이스 (34개 중) | 결과 |
|---|---|---|---|---|
| F1 | `service/QaIssueService.java` | `screen_location` 필수 검사 줄 삭제 | qi-001 | 잡음 |
| F2 | `service/QaIssueService.java` | StyleMismatch `actual_value` 필수 검사 줄 삭제 | sm-001 | 잡음 |
| F3 | `service/TeamNotifier.java` | `fixed` 알림을 등록자에게 2건 저장 | nt-002, st-005 | 잡음 |
| F4 | `service/QaIssuePatchService.java` | 상태 변경 권한 검사 `if (team == null \|\| team != actorRole)` → `if (false)` | au-003, au-004, au-006, au-010 | 잡음 |
| F5 | `service/QaIssuePatchService.java` | `in_progress` 전이 허용 조건을 항상 참으로 | st-006, st-007 | 잡음 |
| F6 | `service/VerificationService.java` | 재검증 결과와 상관없이 `resolved`로 | vf-003, nt-005 | 잡음 |
| F7 | `service/VerificationService.java` | `fixed`가 아닌 이슈의 재검증 거부(409) 검사 삭제 | st-001 | 잡음 |
| F8 | `service/QaIssuePatchService.java` | PATCH로 담당 팀을 지정할 때 알림 생성 줄 삭제 | nt-003, nt-007, fq-004 | 잡음 |
| F9 | `tests/harness/golden_cases.yaml` | `qi-002`의 기대 `status: 201` → `200` | qi-002 | 잡음 — 판정이 케이스 파일에서 온다 |

**확인 결과: 넣은 결함 9건을 모두 실패로 판정했다.** 결함마다 실패한 케이스가 그 결함이 어기는 AC의 케이스였고, 다른 케이스는 통과했다(결함과 무관한 케이스가 같이 깨지지 않음).
이 확인 뒤로 하니스와 케이스의 `input`·`expected`는 에이전트가 임의로 고치지 않는다 — 판정 키를 더하거나 대상 케이스를 넓힐 때만 사람이 범위를 정해 다시 위임하고, 그때 이 표를 다시 돌린다.

## 3. 실행 결과 (2026-11-18, 브랜치 `dev-feat-golden-harness`)

- `cd backend && ./gradlew test` — **439개 전체 통과, skip 0, 실패 0** (이 작업 전 398개 + 하니스 동적 테스트 34개 + 목록 검사 5개 + ng-013·ng-018 2개)
- 하니스 34케이스: 등록 필수값(qi-001·qi-002·sm-001), 담당 팀 알림(nt-001·003·004·007·008·fq-004), 수정 완료·재검증 알림(nt-002·005·006·st-005), 상태 전이(st-001~007), 재현 절차 보존(vf-001), 재검증 결과(vf-002·003), 인증·권한(au-001~010), 공개 엔드포인트(health-001·auth-001)

확인한 사람: ____ (팀원이 2절 표의 결함 하나 이상을 직접 넣어 재현하고 서명한다 — 지금은 에이전트가 실행한 결과)
