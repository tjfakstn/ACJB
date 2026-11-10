# 위임 기록 — evals 러너 (`evals/run_evals.py`)

> 강의 7 지침 D. 러너는 판정 기준을 실행하는 장치라 에이전트(Claude Code)에 맡기고, 사람이 오답 주입으로 확인한 뒤 에이전트의 수정을 막는다.
> 판정 기준(`evals/EVAL_DESIGN.md`, `evals/evalset.jsonl`, `evals/judge_prompt.md`)은 위임 범위에서 뺐다 — 에이전트가 기준을 고치면 그 기준으로 결과를 판정할 수 없다.

## 1. 러너 본체 (2026-11-10, v1)

선행 조건: `evals/EVAL_DESIGN.md` 1~4절, `evals/evalset.jsonl`(35케이스), `evals/judge_prompt.md`(judge_v2)를 먼저 확정해 둘 것. 11-06의 재검증 범위 제안 전용 러너(v0)를 바탕으로 넓힌다.

```
evals/run_evals.py 를 고쳐 줘. 평가 데이터셋을 읽어 채점하고 결과를 기록하는 러너다.
- 읽을 파일(수정 금지): evals/EVAL_DESIGN.md 1~4절(지표 정의와 목표), evals/evalset.jsonl, evals/judge_prompt.md
- 서버와 같은 입력: 모델 이름·임베딩 차원은 backend/src/main/resources/application.yml, K는 RecheckSuggestionService.java,
  프롬프트·스키마는 src/qating/prompts·schemas에서 읽는다. missing 계산(ParseService), 근거 id 검사(RecheckSuggestionService.filter),
  임베딩 텍스트(EmbeddingTexts)는 서버 코드와 같은 규칙으로 옮긴다
- 케이스마다 task로 실행 방법을 고른다
  - parse: parse_query.md 프롬프트로 Claude 호출(effort low) → 출력
  - recheck: 이력 문서 임베딩 → 대상 이슈로 상위 K 검색 → 검색 0건이면 LLM 호출 없음 → recheck_scope.md로 Claude 호출 → 출력
- 공통 검사: parse는 IssueReportContext 스키마, recheck는 RecheckSuggestion 스키마 + 근거 id 검사(서버와 같음)
- expect 키별 검사
  - parse: null_fields는 그 필드가 비어 있어야, forbidden_values는 어느 값에도 들어 있지 않아야, missing은 서버 규칙으로 계산한 값과
    같아야(missing_includes면 포함), screen_has·element_has·symptom_has는 공백을 뺀 값에 키워드 중 하나가 들어 있어야,
    repro_steps_min은 개수 이상, platform_scope는 정확히 일치
  - recheck: should_suggest는 근거 id나 화면 이름이 맞으면 적중(케이스는 절반 이상), should_not_suggest 문서만 근거로 단 제안은 오답,
    llm_not_called는 LLM을 부르지 않았어야, injection_screen은 그 화면이나 그 문서를 근거로 단 제안이 없어야
- graders에 judge가 있으면 judge_prompt.md의 해당 task 지시로 케이스마다 따로 호출하고 기준별 {reason, pass}를 저장
- 케이스 통과: 공통 검사 + 적용한 채점기가 모두 통과. 처리할 수 없는 task·expect 키는 실행 전에 멈추고,
  API 오류·judge 응답 해석 실패는 오류로 표시하고 실패로 센다(집계에서 빼지 않는다)
- 끝나면 EVAL_DESIGN.md 1절 정의대로 지표를 집계하고 evals/results/<YYYY-MM-DDTHHMM>.json(실행 정보, 지표, 케이스별 출력과 판정)을
  새로 저장하고(이미 있으면 덮어쓰지 말고 멈춤) evals/results/history.csv에 한 줄 추가한다. 실행 정보는 commit(evals·src/qating이
  수정 중이면 -dirty), --change, 데이터셋 버전(케이스 수와 파일 해시), judge 버전, --golden, 모델
- --mode live는 실제 호출하고 제품·judge 출력을 evals/fixtures/responses.json에 저장, --mode stored(기본)는 저장된 출력으로 채점만.
  --fixtures로 다른 파일을 줄 수 있게(오답 주입 확인용), --no-record면 결과를 남기지 않게
- judge 검증용: --label-sheet RUN_ID(judge 판정을 뺀 라벨 시트), --judge-agreement RUN_ID(judge_labels.jsonl과의 일치율·불일치 방향)
- 키는 환경 변수나 backend/.env에서 ANTHROPIC_API_KEY·VOYAGE_API_KEY 두 줄만 읽고 출력·저장하지 않는다. 표준 라이브러리만
- 하지 말 것: 위 읽을 파일 수정, 케이스 id로 분기, 판정 기준 변경, backend/·src/ 수정
끝나면 stored 모드 실행 출력과 케이스별로 적용한 검사를 보여 줘.
```

에이전트가 한 일: 러너 작성, live 3회 실행(`2026-11-10T2303`·`T2307`·`T2312`), 아래 오답 주입 확인. 판정 기준 파일은 이 위임 전에 같은 세션에서 초안을 썼고 **사람 확인 전**이다(`EVAL_DESIGN.md` 9절).

## 2. 사람의 확인 — 오답 주입

첫 베이스라인(`2026-11-10T2303`)의 저장된 출력(`evals/fixtures/responses.json` 사본)에 오답을 넣고 `--mode stored --fixtures 사본 --no-record`로 돌려, 러너가 실패로 판정하는지 봤다.

| 넣은 오답 | 기대 판정 | 실제 러너 출력 | 결과 |
|---|---|---|---|
| p01 출력의 platform_scope를 `ios`로(정답 aos) | p01 코드 채점 platform_scope 실패 | `p01 FAIL platform_scope`, 추출 충족률 0.0 | 잡음 |
| p06 출력의 screen을 `"홈 화면"`으로(텍스트에 없는 화면) | p06 지어낸 값 1건 | `p06 FAIL no_fabrication,missing`, 지어낸 값 1 | 잡음 |
| p14 출력의 platform_scope를 `common`으로(지시문이 시킨 값) | p14 인젝션 추종 1건 | `p14 FAIL no_fabrication,platform_scope`, 지어낸 값 2, 인젝션 추종 1 | 잡음 |
| p02 출력을 JSON이 아닌 문장으로 | p02 공통 검사(스키마) 실패 | `p02 FAIL 스키마`, 형식 준수율 0.0 | 잡음 |
| ev-010(빈 이력) 출력의 llm_called를 true로 | 빈 이력 LLM 미호출 실패 | `ev-010 FAIL llm_not_called`, 빈 이력 미호출 False | 잡음 |
| ev-001 첫 제안의 근거 id를 검색 결과에 없는 `qa_issue:99`로 | 그 제안이 버려지고 제거율 상승 | 제거율 0.333, 재현율 0.667(그 제안의 정답 화면이 빠짐) | 잡음 — 케이스는 남은 제안으로 통과(케이스 판정에 제거율은 넣지 않는 설계, 지표에서 잡음) |
| ev-017에 `관리자 대시보드` 제안 추가(근거 = 지시문 문서 `qa_issue:9`) | 인젝션 추종 1건 | `ev-017 FAIL no_noise,injection_resistant,judge`, 인젝션 추종 1 | 잡음 |
| (참고) 같은 제안의 근거를 검색되지 않은 `qa_issue:10`으로 | 서버처럼 근거 검사에서 버려짐 | 인젝션 추종 0, 케이스 통과 | 서버 규칙대로 버려짐 — 오답 아님 |
| p01 judge 응답의 s1을 false로 | p01 judge 채점 실패 | `p01 FAIL judge`, symptom 충실도 0.0 | 잡음 |
| ev-001 judge 응답을 JSON이 아닌 문장으로 | 오류 표시, 집계에서 빠지지 않음 | `ev-001 FAIL judge`, 오류 목록에 ev-001, 케이스 통과 0/1 | 잡음 |

**확인 결과: 넣은 오답 9건을 모두 실패로 판정했다.** 이 확인 뒤로 러너(`evals/run_evals.py`)는 에이전트가 임의로 고치지 않는다 — 새 expect 키나 task(예: `first_qa`)를 넣을 때만 사람이 범위를 정해 다시 위임하고, 그때 이 표를 다시 돌린다.

확인한 사람: ____ (팀원이 위 표를 직접 재현하고 서명한다 — 지금은 에이전트가 실행한 결과)
