# SSOT (Single Source of Truth)

지금 시점의 프로젝트 상태를 한눈에 보기 위한 요약 문서입니다. 세부 내용은 각 문서가 원본(source)이고, 이 문서는 그 값들의 스냅샷 + 링크입니다. **여기와 다른 문서의 내용이 어긋나면, 방금 바뀐 쪽이 맞을 확률이 높으니 이 문서를 먼저 갱신하세요.**

최종 수정: 2026-09-09

---

## 프로젝트

- **팀명**: 안캡잘부 (아주대학교 소프트웨어학과 26-2 캡스톤디자인)
- **프로젝트명**: TODO — 서비스 이름 확정 전
- **한 줄 정의**: Figma와 GitHub를 연결해 디자인과 실제 구현의 차이를 AI가 자동으로 검증하고, QA 이슈 발견부터 수정 후 재검증까지 지원하는 개발 QA 자동화 서비스
- 상세: [docs/PROBLEM.md](PROBLEM.md) (아직 비어있음, 채우기 필요)

## 팀 & 역할

| 이름 | 역할 | 담당 |
|---|---|---|
| 오단비 | 기획 · 개발 보조 | 제품 스펙/우선순위 결정 |
| 설만수 | 개발 | 코드·개발 전반 |
| 최유정 | 디자인 | UI 컴포넌트·디자인 토큰 |

## 핵심 목표

1. Figma 디자인과 배포된 실제 구현의 차이를 확인할 수 있게 한다
2. 발견된 이슈를 구조화된 형태로 정리한다
3. 수정이 필요한 지점을 빠르게 찾아 GitHub 흐름(이슈/PR)으로 연결한다

## 현재 MVP 방향 (검증 중)

인터뷰 조사(2026-09-09) 기반 우선순위:

1. **Figma-실제 구현 스타일 값 자동 대조** — 색상/폰트/간격 등 디자인 토큰과 실제 배포 화면의 차이를 자동 검출
2. **구조화된 QA 이슈 → GitHub 연결** — 발견된 diff를 재현정보(스크린샷·환경) 포함해 바로 GitHub Issue로 전환

범위 밖(당장 안 함): 회귀 테스트 자동화(E2E) — 페인포인트는 크지만 스코프 과다

근거/전체 맥락: [decision_log.md](decision_log.md)

## 기술 스택

| 영역 | 스택 |
|---|---|
| Frontend | React + Vite + TypeScript (Tailwind CSS 예정) |
| Backend | Spring (Spring Boot) |
| DB | TODO |
| AI | TODO — `src/qating/`에 QAIssue · DesignDiff · VerificationResult 스키마 골격 있음 |
| 외부 연동 | Figma API, GitHub API |
| 인프라/배포 | TODO |

원본: [AGENTS.md #3](../AGENTS.md)

## 저장소 구조 (2026-09-09 기준)

```
.
├── AGENTS.md          # AI 에이전트용 가이드
├── CONTRIBUTING.md    # 사람용 기여 가이드
├── README.md          # 팀/제품 소개
├── .github/           # PR/이슈 템플릿
├── config/            # 설정 (예: rag.yaml)
├── data/
├── docs/              # 기획·설계 문서, decision_log, ssot
├── evals/             # AI 파이프라인 평가 (judge_prompt, evalset 등)
├── src/qating/        # 핵심 로직 — schemas(QAIssue/DesignDiff/VerificationResult), prompts, tools
└── tests/
```

프론트엔드(React) 앱 디렉터리는 아직 생성 전 — 추가되면 이 구조도 갱신 필요.

## 협업 규칙 원본 위치

- Git/브랜치/커밋/PR 규칙: [AGENTS.md #7](../AGENTS.md)
- 이슈/PR 템플릿: [.github/](../.github/)
- 결정 이력: [decision_log.md](decision_log.md)

## 아직 안 정해진 것 (TODO)

- 서비스 이름
- DB / AI 모델 / 인프라·배포 스택
- 라벨 체계, 브랜치 보호 규칙 (보류 중, 이슈 쌓이면 재논의)
- `docs/PROBLEM.md`, `docs/SPEC.md`, `docs/ARCHITECTURE.md` — 골격만 있고 내용 비어있음
