# Evals 설계

> 대상: 재검증 범위 제안(SPEC AC20, 설계 `docs/design-recheck-suggestions.md` 9절). 2026-11-06 확정, **아직 구현 전**(`evalset.jsonl`·`judge_prompt.md`·`run_evals.py`는 비어 있음).

## 평가 목표

LLM 제안은 매번 조금씩 달라 골든 케이스처럼 정답 하나로 통과·실패를 가를 수 없다. 그래서 문제집으로 점수를 잰다.

- 같이 다시 볼 화면을 맞게 짚는가 — 정답 화면 재현율
- 근거를 지어내지 않는가 — 근거 id 위반으로 버려진 제안 비율
- 이유가 근거 문서에서 실제로 나오는가 — 채점 프롬프트(LLM judge)

첫 합격 기준(첫 실행 후 조정): 버려진 제안 10% 이하, 정답 화면 재현율 0.6 이상, 근거 일치 0.9 이상.

## 평가 데이터셋 구성

`evalset.jsonl` 한 줄이 한 케이스, 15~20케이스. 실제 사용자 데이터가 아니라 인터뷰 로그 2·6·8의 연결 화면 회귀 패턴을 바탕으로 만든 가상 이력이다.

- `history`: 가상 프로젝트의 이슈·스타일 차이·fail 재검증 10~30건(백엔드와 같은 필드)
- `target_issue`: 재검증할 `fixed` 이슈
- `should_suggest`: 제안해야 하는 화면 목록
- `should_not_suggest`: 나오면 안 되는 화면(관련 없는 화면, 대상 이슈 자신의 화면)
- 빈 이력 케이스 1개 이상(LLM을 부르지 않아야 함)

## 실행 방법

`run_evals.py`는 백엔드 없이 돈다.

1. 케이스 이력을 Voyage(`voyage-4`, document)로 임베딩
2. 대상 이슈를 query로 임베딩해 상위 8개 검색(백엔드와 같은 규칙)
3. `src/qating/prompts/recheck_scope.md`로 Claude 호출, `RecheckSuggestion.schema.json`으로 검증
4. 근거 id 검사(백엔드와 같은 규칙)
5. 결정적 지표 계산 + `judge_prompt.md`로 제안마다 채점
6. 결과를 `results/YYYY-MM-DD.json`에 저장

키는 `VOYAGE_API_KEY`·`ANTHROPIC_API_KEY` 환경 변수로 받고 결과 파일·로그에 남기지 않는다.
