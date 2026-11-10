# QAting — 안캡잘부
안녕하세요. 저희는 아주대학교 소프트웨어학과 26-2 캡스톤디자인 '안캡잘부' 팀입니다.

개발 과정에서 반복되는 QA 작업과 디자이너, 개발자, PM 사이의 커뮤니케이션 문제에 관심을 가지고 
이를 해결할 수 있는 서비스를 만들고 있습니다.
이 과정에서 완성된 결과물을 만드는 것보다 **애자일하게 문제를 올바르게 정의하고, 검증 가능한 형태로 해결해 나가는 과정**을 중요하게 생각합니다.

## Members

| 이름 | 역할 | 담당 |
|---|---|---|
| 오단비 | 기획 · 개발 보조 | 문제 정의, 스펙, 우선순위 |
| 설만수 | 개발 | 백엔드·프론트엔드 개발 전반 |
| 최유정 | 디자인 | Figma 디자인 시스템, UI |

## What We Do

> QA 전담 인력 없이 QA를 나눠 맡는 제품팀의 구성원이, 발견한 차이와 이슈를 재현 가능한 근거와 함께 전달하고, 조치 여부를 바로 알고, 같은 절차로 다시 확인하지 못해, QA 라운드마다 재질문·목록 확인·재현 절차 재입력의 왕복을 반복한다.
> — [문제 정의서](docs/PROBLEM.md)

**QAting**은 이 왕복을 줄이는 QA 이슈 관리 서비스입니다.
이슈를 **재현 정보와 함께 등록**하면, 조치가 끝났을 때 **등록자에게 바로 알림**이 가고, 재검증할 때는 처음 등록한 **재현 절차를 그대로 불러와** 다시 입력하지 않아도 됩니다.

## 실행 방법

운영 주소: **https://acjb-qating.com** (AWS Lightsail, `main` 기준 — 배포 절차는 [deploy/README.md](deploy/README.md)).

로컬에서는 백엔드(Spring Boot, :8080)·프론트(React, :5173)·MySQL(Docker)을 띄웁니다.

### 1. 준비물

| 도구 | 버전 | 확인 |
|---|---|---|
| Git | — | `git --version` |
| Docker Desktop | Docker Compose 포함 | `docker compose version` |
| JDK | 17 이상이면 됨 — Gradle이 Java 21을 자동으로 내려받아 빌드한다 | `java -version` |
| Node.js | 20.19 이상 또는 22.12 이상(Vite 8 요구 사항) | `node -v` |

### 2. 외부 서비스 키

키는 모두 `backend/.env`에 넣습니다(커밋되지 않음). 설명은 [backend/.env.example](backend/.env.example)에 있습니다.

| 키 | 필수 | 없으면 | 발급처 |
|---|---|---|---|
| `GOOGLE_CLIENT_ID`·`GOOGLE_CLIENT_SECRET` | **필수** | 로그인할 수 없음 | [Google Cloud Console](https://console.cloud.google.com/apis/credentials) > OAuth 클라이언트 ID(웹 애플리케이션). 승인된 리디렉션 URI에 `http://localhost:8080/api/v1/auth/callback/google` 추가. 동의 화면이 "테스트" 상태면 테스트 사용자에 본인 구글 계정을 추가 |
| `DB_PASSWORD`·`DB_ROOT_PASSWORD` | **필수** | MySQL이 뜨지 않음 | 로컬에서만 쓸 임의의 값을 직접 정함 |
| `FIGMA_ACCESS_TOKEN` | **필수**(Figma 연결) | Figma 연결이 실패함 | Figma > Settings > Personal access tokens(파일 읽기 권한). 연결할 Figma 파일을 볼 수 있는 계정의 토큰이어야 함 |
| `ANTHROPIC_API_KEY` | **필수**(비교 분석·첫 QA·자유서술 파싱) | 비교 분석이 실패함 | [Claude Platform](https://platform.claude.com). **호출할 때마다 비용이 듭니다** |
| `QA_FAKE_COMPARATOR` | 선택 | — | `true`면 첫 QA·Re-QA가 Claude 대신 정해진 시연 값으로 돈다(이슈 앞에 `[시연용]`). 비교 분석은 여전히 Claude를 부른다 |
| `GITHUB_TOKEN` | 선택 | 익명으로 읽음(호출 한도가 낮음) | GitHub > Settings > Developer settings > Fine-grained token(공개 저장소 읽기만) |
| `VOYAGE_API_KEY` | 선택 | 재검증 범위 제안만 실패 | [Voyage AI](https://dashboard.voyageai.com) |
| `MAIL_USERNAME`·`MAIL_PASSWORD` | 선택 | 초대 메일을 보내지 않음 — 초대받은 사람은 로그인 후 앱 안에서 수락 | Gmail 주소와 앱 비밀번호 |

### 3. 실행

```bash
git clone https://github.com/tjfakstn/ACJB.git
cd ACJB/backend
cp .env.example .env        # 위 표의 필수 키를 채운다
docker compose up -d        # MySQL 8.4 (개발 DB qating + 테스트 DB qating_test)
./gradlew bootRun           # http://localhost:8080 — 처음엔 JDK·의존성 다운로드로 몇 분 걸림
```

다른 터미널에서:

```bash
cd ACJB/frontend
npm install
npm run dev                 # http://localhost:5173
```

- 테이블은 서버가 켜질 때 Flyway가 자동으로 만듭니다. 시드 데이터는 없습니다 — 로그인한 뒤 프로젝트를 직접 만듭니다
- 서버 확인: `http://localhost:8080/api/v1/health`
- 브라우저는 **http://localhost:5173** 으로 엽니다(로그인 후 이 주소로 돌아옵니다). 백엔드를 다시 띄우면 로그인이 풀립니다(세션을 메모리에 둠)
- 테스트: `cd backend && ./gradlew test`(MySQL이 떠 있어야 함, 테스트 DB는 개발 DB와 분리) / 프론트 빌드: `cd frontend && npm run build`

## 사용 방법

QAting의 기본 흐름은 **연결 → 비교 분석 → 첫 QA → 담당 팀 배정·수정 → Re-QA**입니다. 사람이 직접 찾은 문제는 **수동 QA**로 언제든 등록합니다.

1. **로그인** — `http://localhost:5173`에서 구글로 로그인합니다
2. **새 프로젝트 만들기** — [프로젝트 정보] → [멤버 초대](구글 이메일, 건너뛰어도 됨) → [Figma·Git 연결]
   - Figma: 최종 시안이 담긴 **페이지 또는 섹션 링크**(`figma.com/design/{key}/...?node-id=...`). 페이지는 [공유하기], 섹션은 우클릭 [링크 복사]로 얻습니다
   - GitHub: **공개 저장소** `https://github.com/{owner}/{repo}`와 분석할 브랜치. 프론트 코드(`.tsx`·`.jsx`·`.ts`·`.js`·`.vue`·`.svelte`)를 읽습니다(파일 300개·100만 자 이하)
3. **비교 분석** — Claude가 Figma 화면·요소와 그 화면을 그리는 코드 파일·컴포넌트를 짝짓습니다(수 분). 결과는 사이드바 [디자인-코드 매핑]에서 확인합니다
4. **첫 QA** — [디자인 QA]에서 검증 범위(간격·여백 / 색상·타이포그래피 / 모서리 / 고정 문구)를 고르고 [첫 QA 실행]. Figma 값과 코드 값이 **둘 다 확인된 차이만** 이슈가 됩니다(프로젝트당 성공 1번)
5. **담당 팀 배정과 수정** — [QA 결과]에서 이슈를 열어 담당 팀(디자인·프론트엔드·백엔드·기획)을 지정하면 그 역할 멤버에게 알림이 갑니다. 이슈 상세에서 문제 코드 위치와 그 코드가 들어온 커밋·PR을 보고 [수정 시작]·[수정 완료로 변경]으로 진행 상태를 바꿉니다(그 팀 역할의 멤버만 가능)(커밋 메시지에 `QA-{이슈 번호}`를 넣으면 고친 커밋으로 연결됩니다)
6. **Re-QA** — [디자인 QA]에서 [Re-QA 실행]. 수정 완료된 이슈를 같은 위치에서 다시 비교해 해결(PASS)·재실패(FAIL)를 판정합니다. 아직 검사하지 않은 범위를 더 고르면 그 범위도 새로 비교합니다
7. **수동 QA** — [QA 결과]의 [QA 등록하기]에서 배포 화면 위를 클릭해 위치를 찍거나, 스크린샷을 첨부하거나, 위치 없이 등록합니다. 등록자는 수정 완료된 이슈를 처음 적은 재현 절차 그대로 재검증합니다

> 시연·체험 시 비용 없이 첫 QA를 보려면 `QA_FAKE_COMPARATOR=true`로 둡니다. 이때 만들어진 이슈와 Re-QA 결과는 실제 비교가 아닌 **시연용 값**입니다.

## 발견 단계 산출물 (중간평가 ① 발견 게이트)

| 산출물 | 무엇을 담았나 | 위치 |
|---|---|---|
| 인터뷰 로그 | 개발자·디자이너 12명 응답, 워크플로 관찰 1건, Job Story와 전환의 네 힘, 가설 검토 | [docs/research/interviews.md](docs/research/interviews.md) |
| 미니 도메인 온톨로지 | 9개 클래스, 모든 항목에 인터뷰 근거(로그 번호), 범위 밖 5개 | [docs/ontology.yaml](docs/ontology.yaml) |
| 문제 정의서 | 한 문장 문제, 대상·비대상, 증거, 반증 조건(문제의 실재·해결 가설), 성공 지표 | [docs/PROBLEM.md](docs/PROBLEM.md) |
| 제품 스펙 v1 | 핵심 기능, 범위·비포함, 인터페이스, 수용 기준 AC1~AC10 (EARS 문형) | [docs/SPEC.md](docs/SPEC.md) |
| AGENTS.md | 도메인 용어집, 절대 규칙(AC 대응), 금지 사항, 완료의 정의 | [AGENTS.md](AGENTS.md) |
| 기술 타당성 스파이크 | 가장 위험한 가정: Figma 스타일 값 자동 추출·비교 | [docs/spikes/](docs/spikes/) |


### 산출물 연결 구조

```
인터뷰 로그 ──(근거: 로그 번호)──▶ 온톨로지 ──(대표어)──▶ AGENTS.md 용어집
     │                               │
     └──▶ 문제 정의서 ──▶ 스펙 v1 ───┴──▶ 수용 기준(AC) ──▶ 골든 케이스
                                               ▲
                        AGENTS.md 절대 규칙 ───┘
```

- 수용 기준의 판정 데이터: [tests/harness/golden_cases.yaml](tests/harness/golden_cases.yaml)
- 구조화 출력 스키마: [src/qating/schemas/](src/qating/schemas/) / 파싱 프롬프트와 실패 모드 점검표: [src/qating/prompts/parse_query.md](src/qating/prompts/parse_query.md)
- API 명세: [docs/openapi.yaml](docs/openapi.yaml)


## 저장소 구조

```
.
├── README.md
├── AGENTS.md               # AI 코딩 에이전트 상시 규칙 (용어집, 절대 규칙)
├── CLAUDE.md               # @AGENTS.md 임포트
├── docs/
│   ├── research/interviews.md
│   ├── ontology.yaml
│   ├── PROBLEM.md
│   ├── SPEC.md
│   ├── spikes/             # 기술 타당성 스파이크 (1건당 1파일)
│   ├── openapi.yaml
│   └── decision_log.md
├── src/qating/
│   ├── schemas/            # 출력 JSON Schema
│   ├── prompts/            # parse_query.md
│   └── tools/
├── tests/harness/          # 골든 케이스
├── evals/
├── config/
├── data/seed/
├── backend/                # Spring Boot (Java 21)
└── frontend/               # React + Vite + TypeScript
```
