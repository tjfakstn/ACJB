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

**QAting**은 이 왕복을 줄이는 QA 이슈 관리 서비스입니다.

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
