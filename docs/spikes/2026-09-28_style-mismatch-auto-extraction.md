# 기술 타당성 스파이크 — StyleMismatch 자동 추출(scan)

> `SPEC.md` 4절·`docs/ssot.md`가 미결정으로 남겨둔 질문: Figma 프레임과 실제 구현 화면의 스타일 값(`property`/`expected_value`/`actual_value`)을 **자동으로 대조해서 추출**할지, 아니면 QA 담당자가 **수동으로 입력**하게 할지. 이 문서가 그 판정 근거다.

## 가장 위험한 가정

"Figma REST API로 가져온 스타일 값과, 실제 배포된 화면(웹/앱)에서 뽑은 스타일 값을 **같은 property 기준으로 자동 비교**해서 `StyleMismatch`(`color`/`font_weight`/`font_family`/`radius`/`shadow`/`spacing`/`text`)를 신뢰할 수 있는 정확도로 만들어낼 수 있다."

이게 틀리면(예: 두 값의 단위·좌표계·컴포넌트 구조가 안 맞아 오탐이 많이 나면) 자동 scan은 v1에서 빼고 수동 입력만 남겨야 한다 — `QAIssue.schema.json`의 `style_mismatch`는 이미 `oneOf: [StyleMismatch, null]`이라 수동 등록이면 null로 두는 경로가 스키마상 이미 존재하므로, 이 가정이 기각돼도 구현을 되돌릴 필요는 없다(추가 경로만 안 만들면 됨).

## 시간 제한

**2시간 이내** (Figma API 응답 구조 확인 1시간 + 구현 쪽 값 추출·대조 1시간). 2시간 안에 성공 조건을 판정할 수 없으면 "판단 보류"로 기록하고 자동 scan은 v1 범위에서 제외한 채 진행한다(비대칭 리스크: 자동 scan을 넣었다가 잘못된 판단이면 되돌리기 어렵지만, 안 넣었다가 나중에 추가하는 건 쉬움).

## 성공 조건 (사전 확정)

아래 3개를 **모두** 만족해야 "자동 scan 포함"으로 판정한다. 하나라도 실패하면 "수동 입력만".

1. Figma REST API(`GET /v1/files/{key}/nodes` 또는 `GET /v1/files/{key}`)로 프레임 하나에서 `color`/`font_weight`/`font_family`/`spacing`(padding/gap) 값을 **추가 수작업 매핑 없이** 꺼낼 수 있다.
2. 실제 구현 쪽(프론트 React 컴포넌트의 computed style, 또는 CSS-in-JS/Tailwind 클래스)에서 같은 property를 **같은 property 이름 체계로** 꺼낼 수 있다(예: Figma의 `fontWeight: 700`과 구현의 `font-bold`가 같은 값 700으로 정규화 가능).
3. 같은 컴포넌트(예: 버튼 하나)를 놓고 1·2에서 뽑은 값을 비교했을 때, 의도적으로 다르게 만든 값(예: 버튼 radius를 8px로 구현했는데 Figma는 12px)은 잡아내고, 실제로 같은 값은 오탐(false positive)으로 잡지 않는다 — 최소 3개 property × 2개 케이스(일치/불일치) = 6개 샘플 중 5개 이상 정확히 판정.

## 방법

1. Figma 파일 하나에서 버튼 컴포넌트 프레임을 선택하고, Figma Personal Access Token으로 `GET /v1/files/{file_key}/nodes?ids={node_id}` 호출 → 응답에서 `fills.color`, `style.fontWeight`, `style.fontFamily`, `cornerRadius`, `itemSpacing`(auto layout인 경우) 값 위치 확인.
2. 같은 버튼이 프론트(`frontend/`)에 구현돼 있다면 브라우저 devtools 또는 Tailwind 클래스 → 실제 CSS 값(`getComputedStyle`)으로 같은 property를 꺼냄. Figma 단위(px, 0~1 RGBA)와 구현 단위(px, hex/rgb)가 다르면 변환 규칙을 같이 기록.
3. property별로 Figma 값과 구현 값을 나란히 표로 정리하고, 의도적으로 값을 다르게/같게 만든 6개 샘플에 대해 "자동 비교 결과가 맞았는가"를 O/X로 표시.
4. 성공 조건 3개 중 몇 개를 만족했는지 판정하고, 실패한 조건이 있다면 원인(단위 불일치, 컴포넌트 구조 불일치, Figma 쪽 값 누락 등)을 구체적으로 남긴다.

## 결과 및 결론

**TBD — 아직 실행 전.** (2026-09-28 시점, 이 세션에서는 방법만 설계하고 실제 실행은 하지 않기로 결정 — Figma 파일/토큰을 두고 라이브로 돌릴 다음 세션에서 채운다.)

실행 시 아래 표를 채운다:

| property | Figma 값 | 구현 값 | 의도 | 자동 판정 결과 | O/X |
| --- | --- | --- | --- | --- | --- |
| color | | | 일치 | | |
| color | | | 불일치 | | |
| font_weight | | | 일치 | | |
| font_weight | | | 불일치 | | |
| radius | | | 일치 | | |
| radius | | | 불일치 | | |

## 판단과 재도전 조건

**TBD — 결과 채운 뒤 판정.**

- 성공 조건 3개 모두 만족 → `SPEC.md`/`ssot.md`의 "포함 여부: 스파이크 결과로 결정" 문구를 "포함 확정"으로 갱신하고, `POST /projects/{projectId}/style-mismatches/{mismatchId}/issues` 자동 경로 구현을 백로그에 올린다.
- 하나라도 실패 → v1은 수동 입력만으로 확정하고, `ssot.md` TODO에서 이 항목을 "자동 scan: v1 제외, 재검토는 v2"로 갱신한다. 재도전 조건: 프론트 스타일 시스템이 디자인 토큰 기반으로 정리돼 Figma variable과 1:1 매핑이 가능해지는 시점.
- 2시간 내 판정 불가(도구 접근 문제 등) → "판단 보류"로 남기고 원인을 기록, 다음 세션에서 재시도.
