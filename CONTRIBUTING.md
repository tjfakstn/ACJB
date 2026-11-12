# CONTRIBUTING

'안캡잘부' 팀 기여 가이드입니다. AI 에이전트를 위한 상세 규칙은 [AGENTS.md](AGENTS.md), 프로젝트 배경은 [README.md](README.md) · [docs/ssot.md](docs/ssot.md)를 참고하세요.

## 시작하기

로컬 개발 명령의 정본은 이 절이다. [AGENTS.md 7.4](AGENTS.md)는 여기를 가리키기만 한다. 아래 명령은 2026-11-12에 실제로 실행해 확인했다(백엔드 테스트 398개 통과, `bootRun` 헬스체크 200, 프론트 `npm ci`·`npm run build`·`npm run dev` 200).

준비물: Git, Docker Desktop, JDK, Node.js(버전 조건은 [README](README.md) 맨 아래 "개발자용" 참고)

**백엔드** (`backend/`에서)

```bash
cp .env.example .env    # DB_PASSWORD·DB_ROOT_PASSWORD는 로컬용 임의 값. 나머지 키 설명은 .env.example 주석
docker compose up -d    # MySQL 8.4 — 개발 스키마(qating)와 테스트 스키마(qating_test)를 만든다
./gradlew test          # qating_test를 매번 비우고 Flyway로 다시 만든다 — 개발 데이터는 지우지 않는다
./gradlew bootRun       # http://localhost:8080 (헬스체크: GET /api/v1/health)
```

**프론트엔드** (`frontend/`에서)

```bash
npm ci                  # package-lock.json 그대로 설치
npm run dev             # http://localhost:5173 — /api 요청은 vite 프록시가 :8080으로 넘긴다
npm run build           # tsc -b + vite build (타입 검사 포함)
npm run lint            # oxlint
```

- DB 스키마 규칙(Flyway): [backend/README.md](backend/README.md)
- 운영 배포 구성과 절차: [deploy/README.md](deploy/README.md)

## 브랜치 & 커밋

전체 규칙은 [AGENTS.md #7 Git & 협업 규칙](AGENTS.md)에 정리되어 있습니다. 요약:

- 브랜치: `main`(절대 직접 작업 금지) / `dev`(통합 + 공통 문서) / `server`(백엔드) / `client`(프론트엔드)
- 기능/수정 단위 작업은 `server/feat-<설명>` `server/fix-<설명>` `client/feat-<설명>`처럼 타입 접두사(feat/fix/docs/refactor/chore)를 유지한 하위 브랜치로 분기 → 완료되면 `server`/`client`로 병합 → 어느 정도 쌓이면 `dev`로 통합, 배포 시점에만 `dev` → `main`
- `docs/decision_log.md`, `docs/ssot.md`, `AGENTS.md`, `CONTRIBUTING.md` 등 모든 브랜치에 공통 적용되는 문서는 `server`/`client`가 아니라 **`dev`에서 직접** 수정
- 커밋 메시지: Conventional Commits (`feat:` `fix:` `docs:` `refactor:` `test:` `chore:` `style:` `perf:`)

## 이슈 등록

`.github/ISSUE_TEMPLATE/`의 템플릿 중 하나를 사용해 주세요.

- 🐞 버그 리포트 — 실제 동작이 의도와 다를 때
- ✨ 기능 요청 — 새로운 기능/개선 제안
- 🔍 QA 이슈 — Figma 디자인과 실제 구현이 다를 때 (우리 서비스의 핵심 도메인)

## PR 절차

1. 이슈를 먼저 만들고 (없으면 작업하며 함께 생성), 관련 브랜치에서 작업
2. PR은 하나의 목적만 다루기
3. `.github/PULL_REQUEST_TEMPLATE.md`에 맞춰 **무엇을 / 왜 / 어떻게 검증했는지** 작성
4. UI 변경 시 Figma 화면과의 비교 스크린샷 첨부
5. 리뷰어 최소 1명 승인 후 머지

## 담당 영역

| 이름 | 역할 |
|---|---|
| 오단비 | 기획 · 개발 보조 |
| 설만수 | 개발 |
| 최유정 | 디자인 |

- 제품 스펙/우선순위 결정 → 오단비
- 코드·개발 전반 → 설만수 (오단비가 개발 보조)
- UI 컴포넌트·디자인 토큰 변경 → 최유정

담당 영역에 영향을 주는 변경은 PR 설명에 담당자를 멘션해 주세요.

## 하지 말아야 하는 것

- 시크릿/토큰을 코드나 로그에 남기기
- 요청하지 않은 범위까지 임의로 수정하기
- `main` 브랜치에 직접 커밋

모호한 점이 있으면 추측하지 말고 이슈나 팀 채널에 먼저 확인해 주세요.
