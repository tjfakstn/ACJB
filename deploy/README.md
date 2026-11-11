# 운영 배포

AWS Lightsail(서울, Ubuntu 24.04, 8GB) 한 대에 docker compose로 세 컨테이너를 띄운다.

| 서비스 | 하는 일 |
| --- | --- |
| `web` | Caddy — HTTPS 인증서 자동 발급(Let's Encrypt), 프론트 정적 파일(`frontend/dist`), `/api/*`를 백엔드로 전달 |
| `backend` | Spring Boot + 헤드리스 크로미움(scan·배포 화면 캡처) |
| `mysql` | MySQL 8.4 — 밖으로 포트를 열지 않는다 |

프론트와 API가 같은 도메인이라 세션 쿠키·CSRF 쿠키를 그대로 쓴다.

## 처음 한 번

1. 서버: Lightsail 방화벽에 22·80·443, 고정 IP, 도메인 A 레코드 → 고정 IP
2. Google Cloud 콘솔 OAuth 클라이언트의 "승인된 리디렉션 URI"에 `https://{도메인}/api/v1/auth/callback/google`
3. 서버에 Docker 설치(공식 apt 저장소), `git clone https://github.com/tjfakstn/ACJB.git ~/ACJB`
4. `cp deploy/.env.example deploy/.env` 후 값 채우기(`chmod 600`). DB 비밀번호는 서버에서 새로 만든 긴 임의 값

## 배포·갱신

```bash
cd ~/ACJB && git checkout main && git pull
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.yml logs -f backend   # 기동 확인(Flyway 마이그레이션 포함)
```

## 여러 팀이 같이 쓸 때 조정하는 값

기본값으로 충분하면 비워 둔다. 바꾸려면 `deploy/.env`에서 주석을 풀고 값을 넣은 뒤 다시 띄운다(빈 값으로 두면 기동에 실패한다).

| 변수 | 기본값 | 뜻 |
| --- | --- | --- |
| `LIMIT_PROJECTS_PER_OWNER` | 3 | 한 사람이 소유할 수 있는 프로젝트 수 |
| `LIMIT_ANALYSIS_PER_DAY` · `LIMIT_FIRST_QA_PER_DAY` · `LIMIT_RE_QA_PER_DAY` | 5 · 5 · 10 | 프로젝트마다 최근 24시간 시작 횟수(실패 포함, DB 기준이라 재시작해도 유지) |
| `LIMIT_PARSE_PER_DAY` · `LIMIT_SUGGESTIONS_PER_DAY` · `LIMIT_INVITATIONS_PER_DAY` | 100 · 50 · 30 | 프로젝트마다 최근 24시간 호출 수(메모리 기준이라 재시작하면 초기화) |
| `JOBS_HEAVY_CONCURRENCY` | 2 | 비교 분석·첫 QA·Re-QA 동시 실행 수. 넘으면 "대기 중"으로 줄을 선다 |
| `BROWSER_MAX_CONCURRENT` | 2 | 서버 크로미움 동시 실행 수(scan·배포 화면 캡처). 넘으면 429 |
| `BACKEND_MEM_LIMIT` | 5g | 백엔드 컨테이너 메모리 상한 |

한도를 넘은 요청은 `429 RATE_LIMITED`와 "하루 n번까지" 안내로 거부된다. 컨테이너 로그는 서비스마다 10MB × 5개까지만 남는다.

## 주의

- **갱신 전에 실행 중인 작업이 없는지 확인한다.** 재시작하면 대기·실행 중이던 비교 분석·첫 QA·Re-QA·scan·Figma 동기화는 "서버가 다시 시작돼 중단됐어요"로 실패 처리되고(알림이 간다) 로그인 세션도 풀린다. 확인:
  ```bash
  sudo docker exec deploy-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "select count(*) from project_runs where status in (\"QUEUED\",\"RUNNING\")" "$MYSQL_DATABASE"'
  ```

- `deploy/.env`는 서버에만 둔다. 커밋·로그 출력 금지
- MySQL 데이터는 도커 볼륨(`mysql-data`)에 있다 — Lightsail 자동 스냅샷으로 서버째 백업한다
- 도메인이 아직 없으면 `DOMAIN=:80`, `APP_BASE_URL=http://{IP}`, `SESSION_COOKIE_SECURE=false`로 HTTP만 띄울 수 있다(구글 로그인은 HTTPS 도메인이 있어야 된다)
