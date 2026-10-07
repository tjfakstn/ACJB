# SSOT (Single Source of Truth)

지금 시점의 프로젝트 상태를 한눈에 보기 위한 요약 문서입니다. 세부 내용은 각 문서가 원본(source)이고, 이 문서는 그 값들의 스냅샷 + 링크입니다. **여기와 다른 문서의 내용이 어긋나면, 방금 바뀐 쪽이 맞을 확률이 높으니 이 문서를 먼저 갱신하세요.**

최종 수정: 2026-09-28

---

## 프로젝트

- **팀명**: 안캡잘부 (아주대학교 소프트웨어학과 26-2 캡스톤디자인)
- **프로젝트명**: QAting
- **한 줄 정의**: QA 이슈를 재현 정보와 함께 등록하고, 조치가 끝나면 등록자에게 바로 알리고, 등록 때의 재현 절차를 그대로 불러와 재검증하는 QA 자동화 서비스
- 상세: [docs/PROBLEM.md](PROBLEM.md) — 인터뷰 12건 기반 문제 정의 완료 (2026-09-28 오단비가 4건 → 12건으로 재검증·재작성, 배경: [decision_log.md](decision_log.md))

## 팀 & 역할

| 이름 | 역할 | 담당 |
|---|---|---|
| 오단비 | 기획 · 개발 보조 | 제품 스펙/우선순위 결정 |
| 설만수 | 개발 | 코드·개발 전반 |
| 최유정 | 디자인 | UI 컴포넌트·디자인 토큰 |

## 핵심 목표

1. QA 이슈를 재현 정보(화면 위치·재현 절차·플랫폼 범위)와 함께 등록하게 한다 — 정보가 비면 등록을 거부한다
2. 조치가 끝나면 등록자에게, 이슈가 새로 등록되면 담당자에게 바로 알린다
3. 재검증 시 최초 등록된 재현 절차를 그대로 불러와 재입력 없이 확인하게 한다

## 현재 MVP 방향 (v0.2, 2026-09-28)

인터뷰 조사 12건 + 관찰 1건 기반 우선순위 (배경: [decision_log.md](decision_log.md)):

1. **재현 정보 필수 QA 이슈 등록** — description/screen_location/repro_steps/platform_scope 중 하나라도 비면 거부
2. **상태 변경 알림(Notification)** — 등록 시 담당자에게, `fixed` 전환 시 등록자에게 정확히 1건
3. **재검증(Verification)** — 최초 재현 절차 재사용, pass/fail에 따라 상태 자동 전환

범위 밖(v1 비포함, v2 후보): **GitHub Issues 연동**(팀마다 트래커가 제각각), 테스트 데이터셋 세팅, E2E 회귀 자동화, 디자인 토큰 자동 동기화. Figma-구현 스타일 자동 대조(`StyleMismatch` scan)는 포함 여부를 스파이크 결과로 결정.

> 이전 버전(2026-09-09~09-23, 설만수 초안): Figma-구현 자동 대조 + GitHub 연동을 MVP 핵심으로 잡았었음. 응답자 12명 기준 재검증 결과 "전달·확인 왕복"이 더 우선순위 높은 문제로 확인되어 위 방향으로 대체됨.

## 기술 스택

| 영역 | 스택 |
|---|---|
| Frontend | React + Vite + TypeScript (Tailwind CSS 예정) |
| Backend | Spring (Spring Boot) |
| DB | TODO |
| AI | TODO — `src/qating/`에 QAIssue · StyleMismatch · Verification · Notification · IssueReportContext 스키마 있음 |
| 외부 연동 | Figma API, GitHub API |
| 인프라/배포 | TODO |

원본: [AGENTS.md #3](../AGENTS.md)

## 저장소 구조 (2026-09-28 기준)

```
.
├── AGENTS.md          # AI 에이전트용 가이드
├── CLAUDE.md          # Claude Code 진입점 (@AGENTS.md 임포트만)
├── CONTRIBUTING.md    # 사람용 기여 가이드
├── README.md          # 팀/제품 소개
├── .github/           # PR/이슈 템플릿
├── backend/           # Spring Boot 프로젝트 (구글 로그인 구현됨)
├── frontend/          # React + Vite 프로젝트 (랜딩/로그인 화면 구현됨)
├── config/            # 설정 (예: rag.yaml)
├── data/
├── docs/              # PROBLEM/SPEC/ontology/openapi/decision_log/ssot/research
├── evals/             # AI 파이프라인 평가 (judge_prompt, evalset 등)
├── src/qating/        # 스키마(QAIssue/StyleMismatch/Verification/Notification/IssueReportContext), prompts
└── tests/harness/     # 골든 케이스
```

## 협업 규칙 원본 위치

- Git/브랜치/커밋/PR 규칙: [AGENTS.md #7](../AGENTS.md)
- 이슈/PR 템플릿: [.github/](../.github/)
- 결정 이력: [decision_log.md](decision_log.md)
- API 명세: [SPEC.md](SPEC.md)(정본) / [openapi.yaml](openapi.yaml)(기계가 읽는 버전), 데이터 모델: [src/qating/schemas/](../src/qating/schemas/)

## 아직 안 정해진 것 (TODO)

- DB / AI 모델 / 인프라·배포 스택
- 라벨 체계, 브랜치 보호 규칙 (보류 중, 이슈 쌓이면 재논의)
- `docs/ARCHITECTURE.md` — 골격만 있고 내용 비어있음
- API 인증 방식, 에러 코드 표준 (구글 로그인은 구현됨. 그 외 QA 도메인 API의 인증 방식은 미정)
- `StyleMismatch` 자동 추출(scan)의 포함 여부 — 스파이크(`docs/spikes/`) 결과로 결정 예정. 스파이크는 2026-10-07 실행 완료(웹·버튼 1종·color/font_weight/radius 한정, 6/6 통과 — 가정 기각 안 됨). v1 포함 여부 결정은 오단비 대기
