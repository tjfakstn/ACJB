# 재검증 범위 제안 (2차 LLM + RAG) 상세 설계

> 상태: **확정 (2026-11-06, 설만수)**. SPEC(4절·5절·AC20·N16), openapi, db-schema, decision_log에 나눠 반영했다. 이 파일은 설계 근거이고, 어긋나면 SPEC이 정본이다.
> 전제(2026-11-05 확정, `docs/decision_log.md`): 호출 지점은 재검증 범위 제안 하나, 요청 시에만 호출, 검색은 Voyage AI 임베딩 + MySQL 저장 + 프로젝트 단위 서버 계산, 응답 확인은 근거 id 검사 + evals 채점.

## 1. 흐름

```
재검증 화면에서 "같이 볼 곳 제안" 클릭
  → POST /projects/{projectId}/qa-issues/{issueId}/recheck-suggestions
  → ① 권한·상태 검사 (재검증 등록과 같은 규칙, fixed만)
  → ② 그 프로젝트의 임베딩 누락분 보충 (Voyage, document)
  → ③ 대상 이슈 문장으로 질의 벡터 생성 (Voyage, query)
  → ④ 같은 프로젝트 문서만 코사인 유사도 상위 K개 검색 (대상 이슈 자신은 제외)
  → ⑤ 검색 결과가 0건이면 LLM을 부르지 않고 빈 제안 반환
  → ⑥ Claude 호출 (구조화 출력, 검색 문서를 id 붙여 전달)
  → ⑦ JSON Schema 검증 → 근거 id 검사 → 통과한 제안만 반환 (저장 안 함)
```

## 2. API

`POST /projects/{projectId}/qa-issues/{issueId}/recheck-suggestions` — 본문 없음.
GET이 아니라 POST인 이유: 외부 유료 호출(LLM·임베딩)을 일으키고 같은 요청에도 결과가 달라질 수 있다.

**검사 순서** (재검증 등록과 동일): 이슈 존재(404) → 권한(403, AC13과 같은 사람) → 상태 `fixed`(아니면 409 `INVALID_TRANSITION`) → 외부 호출.

**응답 200**

```json
{
  "success": true,
  "data": {
    "issue_id": "12",
    "suggestions": [
      {
        "screen": "결제 완료 화면",
        "reason": "같은 결제 버튼 컴포넌트를 쓰는 화면으로, 이전에 같은 색 문제로 재검증이 실패한 기록이 있다",
        "evidence": [
          { "type": "qa_issue", "id": "7" },
          { "type": "verification", "id": "3" }
        ]
      }
    ],
    "searched_count": 8,
    "dropped_count": 1
  }
}
```

- `suggestions`는 최대 5개, LLM이 낸 순서(관련 높은 순)를 유지
- `evidence`는 1개 이상 — 프론트가 근거 이슈로 바로 이동할 수 있게 type·id 그대로
- `searched_count`(검색해 LLM에 준 문서 수)·`dropped_count`(근거 id 검사에서 버린 제안 수)는 품질 관찰용
- 실패: 키 없음·Voyage/Claude 호출 실패·스키마 위반 → 502 `SUGGESTION_FAILED`(새 코드, 13번째)

## 3. 임베딩 대상 문서

| type | 원본 | 임베딩 텍스트 | 대상 |
|---|---|---|---|
| `qa_issue` | QAIssue | `화면: {screen_location}\n증상: {description}\n재현: {repro_steps를 " → "로 이음}\n플랫폼: {platform_scope}` | 전부 |
| `style_mismatch` | StyleMismatch | `화면: {screen}\n요소: {selector}\n{property}: 기대 {expected} / 실제 {actual}` (screen·selector가 비면 그 줄은 뺀다) | 전부 |
| `verification` | Verification | `화면: {이슈 screen_location}\n증상: {이슈 description}\n결과: 재검증 실패\n범위: {scope}` (scope가 비면 그 줄은 뺀다) | **fail만** |

- 세 원본 모두 생성 뒤 텍스트가 바뀌지 않는다(QAIssue PATCH는 status·assigned_to만, StyleMismatch·Verification은 updatable=false) → **생성 시 한 번만** 만들면 된다
- verification pass는 "문제 없음"이라 회귀 근거가 되지 않아 제외
- verification 텍스트에 원래 이슈의 증상을 넣는다(2026-11-06 구현 때 추가) — 화면·결과·범위만으로는 무엇이 실패했는지 알 수 없어 검색에 거의 걸리지 않는다
- 정본은 서버 코드 `EmbeddingTexts`(`backend/.../recheck/EmbeddingTexts.java`)이고, `evals/run_evals.py`도 같은 규칙을 따른다
- 상태·담당자·작성자 이름은 넣지 않는다(의미 검색에 잡음, 개인정보)

## 4. 저장 (Flyway V2)

```sql
CREATE TABLE embeddings (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id   BIGINT       NOT NULL,
  source_type  VARCHAR(20)  NOT NULL,  -- qa_issue / style_mismatch / verification (CHECK)
  source_id    BIGINT       NOT NULL,
  model        VARCHAR(40)  NOT NULL,  -- voyage-4
  vector       BLOB         NOT NULL,  -- float32 1024개, little-endian (4KB)
  created_at   DATETIME(6)  NOT NULL,
  CONSTRAINT uk_embeddings_source UNIQUE (source_type, source_id, model),
  CONSTRAINT fk_embeddings_project_id FOREIGN KEY (project_id) REFERENCES projects(id)
);
CREATE INDEX idx_embeddings_project ON embeddings(project_id, model);
```

- source는 FK를 걸지 않는다(세 테이블을 가리키는 다형 참조). 원본은 v1에서 삭제되지 않는다
- 모델을 바꾸면 `model`이 달라 새 행이 생기고 옛 행은 검색에서 빠진다(재생성은 누락분 보충이 처리)
- 같은 프로젝트의 보충은 서버 안에서 한 번에 하나만 돈다(2026-11-06 구현 때 추가). 이슈를 연달아 등록하면 보충이 동시에 떠서 같은 문서를 중복으로 보내 Voyage 무료 등급의 분당 한도(429)에 걸렸다. 서버가 여러 대가 되면 `uk_embeddings_source`가 중복 저장만 막는다

**갱신 시점**: 원본 저장 트랜잭션이 커밋된 뒤 비동기로 생성(`@TransactionalEventListener(AFTER_COMMIT)` + 기존 AsyncConfig). 실패해도 이슈 등록 등 본 요청은 영향 없음. 빠진 행은 제안 요청 때 ②에서 보충한다 — 그래서 키가 없던 기간의 데이터도 나중에 검색된다.

## 5. 검색

- 같은 `project_id`·같은 `model` 행만 메모리로 읽어 코사인 유사도 계산(N16 — 다른 프로젝트 데이터 사용 금지를 쿼리 조건으로 보장)
- 대상 이슈 자신의 `qa_issue` 문서는 제외, 대상 이슈의 fail 재검증 기록은 포함(같은 이슈가 반복 실패한 이력이 근거가 될 수 있음)
- 상위 **K=8**. 유사도 하한은 두지 않고 evals로 정한다
- 모델: `voyage-4`(다국어, 1024차원). 프로젝트당 수천 건 × 4KB = 수 MB라 요청마다 읽어도 충분

## 6. LLM 호출

- 프롬프트: `src/qating/prompts/recheck_scope.md` (parse_query.md와 같은 형식, 실패 모드 점검표 포함)
- 출력 스키마: `src/qating/schemas/RecheckSuggestion.schema.json` — `suggestions[]{screen, reason, evidence_ids[]}`, `additionalProperties: false`, 최대 5개
- 입력: 대상 이슈(설명·화면·재현 절차·이번 수정 내용은 없음) + 검색 문서 K개를 `[qa_issue:7]` 형식의 id와 함께 전달
- 지시: 근거 문서에 있는 사실만 쓰고, 근거가 약하면 제안하지 말 것(빈 배열 허용). 대상 이슈의 화면 자체는 제안하지 말 것(어차피 재검증함)
- 모델·타임아웃은 parse와 같은 설정 방식(`app.recheck.model`, 기본 parse와 같은 모델)

## 7. 근거 id 검사 (N16)

1. 스키마 검증 실패 → 전체 실패(502), 일부만 쓰지 않음
2. 제안마다: `evidence_ids`가 비었거나, **하나라도** 이번 검색 결과 K개에 없는 id면 그 제안을 버린다(`dropped_count`++)
   - 일부 id만 빼고 살리지 않는 이유: 지어낸 근거를 섞은 제안은 나머지 설명도 믿기 어렵다
3. 남은 제안의 `screen`이 대상 이슈의 `screen_location`과 같으면 버린다(지시 위반)
4. 이슈·StyleMismatch·Verification·알림을 하나도 만들지 않는다(자동 저장 금지)

## 8. 결정 (설만수, 2026-11-06)

1. ~~누가 요청할 수 있나~~ → **재검증 등록 권한자만**(등록자, 등록자가 제거됐으면 소유자) — 2026-11-06 확정
2. ~~제안 결과를 저장하나~~ → **저장 안 함**(매번 새로 계산해 응답만 돌려준다) — 2026-11-06 확정
3. ~~에러 코드~~ → 409는 `INVALID_TRANSITION` 재사용, 502는 새 코드 `SUGGESTION_FAILED` 추가 — 2026-11-06 확정
4. ~~evals 방식~~ → **파이썬 단독 실행**(`run_evals.py`, 백엔드 없이) — 2026-11-06 확정, 아래 §9

## 9. evals (`evals/`)

- 데이터(`evalset.jsonl`): 케이스마다 가상 프로젝트 이력(이슈·스타일 차이·fail 재검증 10~30건, 인터뷰 로그 2·6·8의 연결 화면 회귀 패턴을 바탕으로 작성) + 대상 이슈 + **정답 화면 목록**(should_suggest)·**나오면 안 되는 화면**(should_not_suggest). 15~20케이스
- 결정적 지표(코드로 계산): 정답 화면 재현율, 근거 id 위반율(dropped/전체), 빈 이력에서 LLM 미호출
- 채점(`judge_prompt.md`): 제안마다 "이유가 근거 문서 내용에서 실제로 나오는가(0/1)", "재검증에 쓸모 있는가(1~3)"
- 실행: `run_evals.py`가 케이스 이력을 Voyage로 임베딩 → 같은 검색 규칙 → 같은 프롬프트로 Claude 호출 → 근거 id 검사 → 채점. 백엔드 없이 돈다(임베딩 텍스트 규칙·K·프롬프트는 백엔드와 같은 파일·값을 읽는다)
- 합격 기준(첫 목표): 근거 id 위반으로 버려지는 제안 10% 이하, 정답 화면 재현율 0.6 이상, 근거 일치 0.9 이상 — 첫 실행 후 조정

## 10. 골든 케이스 (2026-11-06 설만수 승인, `tests/harness/golden_cases.yaml`에 추가 — LLM·Voyage는 테스트 대역)

| id | 내용 |
|---|---|
| rs-001 | fixed 이슈 등록자가 요청하면 200, 근거 달린 제안 반환 |
| rs-002 | fixed가 아니면 409, 외부 호출 없음 |
| rs-003 | 권한 없는 멤버면 403, 외부 호출 없음 |
| rs-004 | 검색 결과 0건이면 LLM을 부르지 않고 빈 제안 |
| ng-014 (N16) | 검색에 없던 id를 근거로 단 제안은 버리고 dropped_count에 센다 |
| ng-015 (N16) | 다른 프로젝트 문서는 검색·근거에 들어가지 않는다 |
| ng-016 (N16) | 제안 요청 전후로 이슈·StyleMismatch·Verification·알림 수가 같다 |
