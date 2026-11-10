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

## 주의

- `deploy/.env`는 서버에만 둔다. 커밋·로그 출력 금지
- MySQL 데이터는 도커 볼륨(`mysql-data`)에 있다 — Lightsail 자동 스냅샷으로 서버째 백업한다
- 도메인이 아직 없으면 `DOMAIN=:80`, `APP_BASE_URL=http://{IP}`, `SESSION_COOKIE_SECURE=false`로 HTTP만 띄울 수 있다(구글 로그인은 HTTPS 도메인이 있어야 된다)
