# 파싱 프롬프트 (parse_query)

> `POST /projects/{projectId}/qa-issues/parse`가 호출하는 LLM 시스템 프롬프트의 정본. SPEC.md AC8, `src/qating/schemas/IssueReportContext.schema.json`, `docs/openapi.yaml`의 `ParseResponse`와 1:1로 대응한다.

## 요청/응답 형식

**요청**

```json
POST /projects/{projectId}/qa-issues/parse
{ "text": "아이콘이 너무 커요" }
```

**응답** (`ParseResponse`)

```json
{
  "success": true,
  "data": {
    "context": {
      "screen": null,
      "element": "아이콘",
      "symptom": "너무 큼",
      "repro_steps": [],
      "platform_scope": null
    },
    "missing": ["screen", "repro_steps"]
  },
  "error": null
}
```

`text` 하나만 받아 `context`(=IssueReportContext)와 `missing`(되물어야 할 필드 이름 배열)을 반환한다. `missing`은 AC8이 지정한 세 필드(`screen`/`element`/`repro_steps`)만 대상으로 판정한다 — `symptom`·`platform_scope`는 텍스트에 없으면 그냥 `null`로 두고 되묻지 않는다(아래 필드 매핑 표 참고).

## 프롬프트

<!-- prompt:start -->
당신은 QA 이슈 제보 텍스트를 구조화하는 파서입니다. 아래 규칙을 반드시 지키세요.

1. 입력은 QA 담당자가 자유롭게 쓴 한국어 텍스트입니다.
2. 아래 5개 필드만 채운 JSON 객체 하나만 출력합니다. 다른 설명, 인사말, 마크다운 코드펜스는 출력하지 않습니다.
   - screen: 문제가 발생한 화면/페이지 이름. 텍스트에 명시적으로 없으면 null.
   - element: 문제가 발생한 구체적 요소(버튼, 아이콘, 텍스트 등). 텍스트에 없으면 null.
   - symptom: 무엇이 어떻게 잘못됐는지에 대한 한 문장 요약. 텍스트에 없으면 null.
   - repro_steps: 재현 순서를 담은 문자열 배열. 사용자가 순서/행동을 설명하지 않았다면 빈 배열 [].
   - platform_scope: "common" | "aos" | "ios" | null. 안드로이드와 iOS 둘 다 언급되거나 플랫폼 구분 없이 말했으면 "common", 한쪽만 명시했으면 해당 값, 아예 언급이 없으면 null.
3. 텍스트에 없는 내용은 절대로 추측해서 채우지 않습니다. 애매하면 null 또는 빈 배열로 둡니다.
4. 출력 JSON의 키 이름과 순서는 위 5개 그대로 유지합니다. 여분의 키를 추가하지 않습니다.

입력: "{{text}}"

출력:
<!-- prompt:end -->

`{{text}}`는 요청 바디의 `text` 값으로 치환한다. 응답 조립 시 백엔드는 이 JSON을 `context`로 그대로 사용하고, `screen`/`element`/`repro_steps` 중 falsy(=null 또는 빈 배열)인 필드 이름만 모아 `missing`을 계산한다(프롬프트 자체는 `missing`을 만들지 않는다 — 판정은 백엔드 책임).

- `prompt_version`: `2026-09-28-v1`

## 필드 → 용도 매핑

| context 필드 | 용도 | AC8 되물음(missing) 대상 |
| --- | --- | --- |
| `screen` | QAIssue 등록 폼의 `screen_location` 앞부분 채움 | O |
| `element` | `screen_location` 뒷부분 채움 (예: "설정 화면 / 저장 버튼") | O |
| `symptom` | `description` 초안 채움 (사용자가 최종 검토·수정) | X (없으면 그냥 null) |
| `repro_steps` | `QAIssue.repro_steps` 그대로 | O |
| `platform_scope` | `QAIssue.platform_scope` 초안 — 값이 없으면 등록 폼에서 사용자가 직접 선택 | X (없으면 그냥 null) |

`screen`+`element`가 합쳐져 `QAIssue.screen_location`(자유 텍스트) 한 필드가 된다는 점에 주의 — 둘을 별도 컬럼으로 저장하지 않는다.

## 실패 모드 점검표

프롬프트 텍스트를 그대로 5개 입력에 적용해 실제로 얻은 출력을 기록한다.

| # | 입력 | 실제 출력 | 판정 |
| --- | --- | --- | --- |
| 1 | "설정 화면에서 저장 버튼을 눌러도 반응이 없어요. 설정 진입 → 저장 버튼 클릭 순서로 재현돼요. 아이폰에서만 그래요." | `{screen:"설정 화면", element:"저장 버튼", symptom:"버튼을 눌러도 반응 없음", repro_steps:["설정 진입","저장 버튼 클릭"], platform_scope:"ios"}` | missing 없음 — 통과 |
| 2 | "아이콘이 너무 커요" | `{screen:null, element:"아이콘", symptom:"너무 큼", repro_steps:[], platform_scope:null}` | missing=[screen, repro_steps] — golden case `pc-001`과 일치 |
| 3 | "로그인이 안 돼요" | `{screen:null, element:null, symptom:"로그인 실패", repro_steps:[], platform_scope:null}` | missing=[screen, element, repro_steps] — 정보 부족 케이스 정상 되물음 |
| 4 | "마이페이지 프로필 사진이 안드로이드랑 iOS 둘 다 안 보여요" | `{screen:"마이페이지", element:"프로필 사진", symptom:"보이지 않음", repro_steps:[], platform_scope:"common"}` | missing=[repro_steps] — 플랫폼 두 개 언급 시 "common" 판정 정상 |
| 5 | "오늘 날씨 좋네요" (QA와 무관한 텍스트) | `{screen:null, element:null, symptom:null, repro_steps:[], platform_scope:null}` | missing=[screen, element, repro_steps] — 무관한 입력도 지어내지 않고 전부 비움, 프롬프트 규칙 3 정상 동작 |

되묻기 UX(무엇을 되묻는 문구로 보여줄지)는 프론트 책임이며 이 문서 범위 밖이다.

## 변경 이력

| 날짜 | 버전 | 내용 |
| --- | --- | --- |
| 2026-09-28 | v1 | 초안 작성 (SPEC.md AC8, IssueReportContext.schema.json 기준) |
