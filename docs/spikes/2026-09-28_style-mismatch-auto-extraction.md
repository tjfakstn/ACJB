# 기술 타당성 스파이크 — StyleMismatch 자동 추출(scan)

> `SPEC.md` 4절·`docs/ssot.md`가 미결정으로 남겨둔 질문: Figma 프레임과 실제 구현 화면의 스타일 값(`property`/`expected_value`/`actual_value`)을 **자동으로 대조해서 추출**할지, 아니면 QA 담당자가 **수동으로 입력**하게 할지. 이 문서가 그 판정 근거다.

## 가장 위험한 가정

"Figma REST API로 가져온 스타일 값과, 실제 배포된 화면(웹/앱)에서 뽑은 스타일 값을 **같은 property 기준으로 자동 비교**해서 `StyleMismatch`(`color`/`font_weight`/`font_family`/`radius`/`shadow`/`spacing`/`text`)를 신뢰할 수 있는 정확도로 만들어낼 수 있다."

이게 틀리면(예: 두 값의 단위·좌표계·컴포넌트 구조가 안 맞아 오탐이 많이 나면) 자동 scan은 v1에서 빼고 수동 입력만 남겨야 한다 — `QAIssue.schema.json`의 `style_mismatch`는 이미 `oneOf: [StyleMismatch, null]`이라 수동 등록이면 null로 두는 경로가 스키마상 이미 존재하므로, 이 가정이 기각돼도 구현을 되돌릴 필요는 없다(추가 경로만 안 만들면 됨).

**이 가정을 먼저 검증하는 이유**: 이번 스파이크 사이클에서 기술 타당성이 불확실한 후보는 이것 외에도 "LLM 파싱 정확도"(→ `parse_query.md` 실패 모드 점검표로 이미 최소 검증됨)와 "알림 채널 연동"(이메일/디스코드 등 — API 문서가 명확해 리스크가 낮음)이 있었다. 그중 자동 scan만 실패 시 영향이 비대칭적으로 작다 — 위에서 적었듯 기각돼도 되돌릴 구현이 없다. 리스크가 크고 되돌리기 쉬운(=먼저 검증해서 빨리 접어도 손해가 적은) 가정을 먼저 처리하는 게 시간 대비 효율적이라 이 가정을 1순위로 선정했다.

## 시간 제한

**2시간 이내** (Figma API 응답 구조 확인 1시간 + 구현 쪽 값 추출·대조 1시간). 2시간 안에 성공 조건을 판정할 수 없으면 "판단 보류"로 기록하고 자동 scan은 v1 범위에서 제외한 채 진행한다(비대칭 리스크: 자동 scan을 넣었다가 잘못된 판단이면 되돌리기 어렵지만, 안 넣었다가 나중에 추가하는 건 쉬움).

## 성공 조건 (사전 확정)

아래 3개를 **모두** 만족해야 "자동 scan 포함"으로 판정한다. 하나라도 실패하면 "수동 입력만". 대상 property는 실행 전에 `color`/`font_weight`/`radius` 3개로 고정한다(방법·결과 표 전부 이 3개만 다룬다 — property 목록이 절마다 달라지지 않도록 여기서 한 번만 정의).

1. Figma REST API(`GET /v1/files/{key}/nodes` 또는 `GET /v1/files/{key}`)로 프레임 하나에서 `color`/`font_weight`/`radius` 값을 **추가 수작업 매핑 없이** 꺼낼 수 있다.
2. 실제 구현 쪽(프론트 React 컴포넌트의 computed style, 또는 CSS-in-JS/Tailwind 클래스)에서 같은 property를 **같은 property 이름 체계로** 꺼낼 수 있다(예: Figma의 `fontWeight: 700`과 구현의 `font-bold`가 같은 값 700으로 정규화 가능).
3. 같은 컴포넌트(예: 버튼 하나)를 놓고 1·2에서 뽑은 값을 비교했을 때, 아래 "건별 판정 규칙"으로 정규화했을 때 의도적으로 다르게 만든 값(예: 버튼 radius를 8px로 구현했는데 Figma는 12px)은 잡아내고, 실제로 같은 값은 오탐(false positive)으로 잡지 않는다 — 3개 property × 2개 케이스(일치/불일치) = 6개 샘플 중 5개 이상 정확히 판정.

**건별 판정 규칙(정규화·허용 오차, 실행 전 확정)**

| property | Figma 표현 | 구현 표현 | 같은 값으로 볼 조건 |
| --- | --- | --- | --- |
| color | `fills[0].color`(r/g/b, 0~1 실수) | hex(`#RRGGBB`) 또는 rgb() | 0~1 값을 0~255로 변환(반올림) 후 채널별 오차 ±2 이내면 동일 |
| font_weight | `style.fontWeight`(숫자, 예 700) | CSS `font-weight` 또는 Tailwind 클래스(`font-bold`→700 매핑표 사용) | 정수 완전 일치(오차 없음) |
| radius | `cornerRadius`(px) | CSS `border-radius`(px) | 단위 동일(px)로 맞춘 뒤 오차 ±1px 이내면 동일 |

## 방법

1. Figma 파일 하나에서 버튼 컴포넌트 프레임을 선택하고, Figma Personal Access Token으로 `GET /v1/files/{file_key}/nodes?ids={node_id}` 호출 → 응답에서 `fills[0].color`, `style.fontWeight`, `cornerRadius` 값 위치 확인.
2. 같은 버튼이 프론트(`frontend/`)에 구현돼 있다면 브라우저 devtools 또는 Tailwind 클래스 → 실제 CSS 값(`getComputedStyle`)으로 같은 3개 property를 꺼냄. 위 "건별 판정 규칙" 표의 변환 규칙을 그대로 적용.
3. property별로 Figma 값과 구현 값을 나란히 표로 정리하고, 의도적으로 값을 다르게/같게 만든 6개 샘플에 대해 "자동 비교 결과가 맞았는가"를 O/X로 표시.
4. 성공 조건 3개 중 몇 개를 만족했는지 판정하고, 실패한 조건이 있다면 원인(단위 불일치, 컴포넌트 구조 불일치, Figma 쪽 값 누락 등)을 구체적으로 남긴다.

## 결과 및 결론

**실행일**: 2026-10-07 (설만수 + Claude Code, 총 약 1시간 — 시간 제한 2시간 이내)

**평가 데이터 출처**
- Figma 파일 키 / 프레임(node) ID: `GBZhzBWLOVirx9MMd5zddI` / `84:759` ("시작하기" 버튼 프레임, 340×56)
- 호출: `GET /v1/files/GBZhzBWLOVirx9MMd5zddI/nodes?ids=84:759` — Personal Access Token(`file_content:read`만 부여), HTTP 200
- 대조 대상 프론트: `frontend/src/components/Button.tsx`(마지막 수정 커밋 `3d0eb7e`, 실행 시점 저장소 HEAD `302fbfe`)의 `primary` variant, 그리고 Figma 값을 보고 만든 테스트 버튼 1개
- 구현 값 추출: 임시 페이지(`frontend/spike.html`, 측정 후 삭제)를 Vite 개발 서버에 띄우고 브라우저에서 `getComputedStyle` 실행

**Figma 쪽에서 꺼낸 값**: color `rgb(0, 114, 220)`(`fills[0].color`가 r 0, g 0.4470588, b 0.8627451 → ×255 반올림), font_weight `600`, radius `8`(px)

**샘플 구성**: 불일치 3건은 실제 `Button` primary(구현값 `bg-gray-800`·`rounded-md`·`font-medium`)와 Figma "시작하기"의 비교다. 즉 디자인과 구현이 실제로 어긋나 있는 상태를 그대로 쓴 것이다. 일치 3건은 Figma 값을 보고 같게 스타일링한 테스트 버튼(`bg-[#0072DC] rounded-lg font-semibold`)이다.

값 칸은 위 "건별 판정 규칙"으로 정규화한 뒤 적었다:

| property | Figma 값 | 구현 값 | 의도 | 자동 판정 결과 | O/X |
| --- | --- | --- | --- | --- | --- |
| color | (0, 114, 220) | (0, 114, 220) | 일치 | 일치 (채널 오차 0, 0, 0) | O |
| color | (0, 114, 220) | (30, 41, 57) | 불일치 | 불일치 (채널 오차 30, 73, 163 > 2) | O |
| font_weight | 600 | 600 | 일치 | 일치 (정수 완전 일치) | O |
| font_weight | 600 | 500 | 불일치 | 불일치 (600 ≠ 500) | O |
| radius | 8px | 8px | 일치 | 일치 (오차 0px) | O |
| radius | 8px | 6px | 불일치 | 불일치 (오차 2px > 1px) | O |

**6개 샘플 중 6개 정확 판정** (기준: 5개 이상).

**사전 규칙이 놓친 점 (실행 중 발견)**
1. **색 형식**: Tailwind v4는 `bg-gray-800`의 computed color를 `rgb()`가 아니라 `oklch(0.278 0.033 256.848)`로 반환한다. 위 규칙표의 "hex 또는 rgb()"만으로는 비교할 수 없었다. 브라우저 캔버스에 칠해 sRGB로 읽는 표준 변환을 추가해 `(30, 41, 57)`을 얻었다. 사람이 값을 일일이 옮기는 매핑은 아니고 코드로 처리되는 변환이지만, 규칙표를 실행 전에 확정한 시점에는 예상하지 못한 단계이므로 숨기지 않고 기록한다. 판정 기준(채널 오차 ±2)은 사전 확정값을 그대로 썼다.
2. **값이 있는 노드가 다름**: `color`와 `radius`는 프레임 노드(`84:759`)에, `font_weight`는 자식 TEXT 노드(`84:760`)의 `style.fontWeight`에 있다. 프레임 하나만 조회해서는 굵기를 못 얻고 하위 텍스트 노드를 찾아야 한다. 코드로 일반화할 수 있는 규칙("첫 TEXT 자손")이지만 평면 조회는 아니다.
3. 구현 쪽에서는 Tailwind 클래스명→값 매핑표가 필요 없었다. `getComputedStyle`이 이미 숫자로 풀어 주기 때문이다(`font-semibold`→600).

**한계 (이 결과를 일반화하지 말 것)**
- 버튼 1종, property 3종, 웹 구현만 확인했다. 앱(AOS/iOS), `font_family`·`shadow`·`spacing`·`text`, 투명도(alpha), 그라디언트, 컴포넌트 variant 상태(hover 등)는 검증하지 않았다.
- 일치 샘플 3건은 Figma 값을 보고 내가 같게 만든 것이라, "실제 일치하는 화면에서 오탐이 얼마나 나는가"를 재는 힘은 약하다. 이번에 확인한 것은 변환·비교 파이프라인이 규칙대로 동작하는가이며, 실제 화면에서의 오탐률은 아직 모른다.
- 표본이 6건이라 통계적 정확도가 아니다. 조건("6건 중 5건")을 충족했다는 뜻이다.

## 판단과 재도전 조건

**판정 (2026-10-07): 성공 조건 3개 모두 충족 → 가정 기각되지 않음. 단, 범위는 "웹, 버튼 1종, color·font_weight·radius"로 한정.**

| 성공 조건 | 결과 |
| --- | --- |
| 1. Figma REST API로 3개 값을 추가 수작업 매핑 없이 추출 | 충족 — 3개 모두 추출됨. 단 `font_weight`는 자식 TEXT 노드를 탐색해야 함 |
| 2. 구현 쪽에서 같은 property를 같은 체계로 추출 | 충족 — `getComputedStyle` 사용. 단 color는 oklch→sRGB 변환 단계가 필요했음 |
| 3. 6개 샘플 중 5개 이상 정확 판정 | 충족 — 6/6 |

**이 판정이 SPEC에 의미하는 것**: 값 추출·비교 자체는 기술적으로 막히지 않았으므로, 자동 추출 경로를 v1에서 **포기할 근거는 나오지 않았다**. 다만 위 한계 때문에 "v1에 반드시 넣는다"를 뒷받침할 만큼 넓게 검증된 것도 아니다. `SPEC.md` 4절의 "포함 여부" 문구와 5절의 `style-mismatches/{mismatchId}/issues` 경로를 바꿀지는 제품 스펙 담당(오단비)이 결정한다. 아래 첫 항목은 그 결정을 내린 뒤에 적용한다.

- 성공 조건 3개 모두 만족 → `SPEC.md`/`ssot.md`의 "포함 여부: 스파이크 결과로 결정" 문구를 "포함 확정"으로 갱신하고, `POST /projects/{projectId}/style-mismatches/{mismatchId}/issues` 자동 경로 구현을 백로그에 올린다.
- 하나라도 실패 → v1은 수동 입력만으로 확정하고, `ssot.md` TODO에서 이 항목을 "자동 scan: v1 제외, 재검토는 v2"로 갱신한다. 재도전 조건: 프론트 스타일 시스템이 디자인 토큰 기반으로 정리돼 Figma variable과 1:1 매핑이 가능해지는 시점.
- 2시간 내 판정 불가(도구 접근 문제 등) → "판단 보류"로 남기고 원인을 기록, 다음 세션에서 재시도.
