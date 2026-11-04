# ACJB Backend

Spring Boot 4.1 / Java 21 / Gradle. 현재는 구글 소셜로그인(OAuth2 Login) 스켈레톤만 있는 상태입니다.

## 로컬 실행

1. `.env.example`을 `.env`로 복사하고 Google OAuth Client ID/Secret과 DB 비밀번호(`DB_PASSWORD`, `DB_ROOT_PASSWORD`, 로컬 전용 임의 값) 채우기

   ```bash
   cp .env.example .env
   ```

   Client ID/Secret은 [Google Cloud Console](https://console.cloud.google.com/apis/credentials)에서 발급 (승인된 리디렉션 URI: `http://localhost:8080/api/v1/auth/callback/google`)

2. MySQL 실행 (Docker 필요). 개발 DB `qating`과 테스트 DB `qating_test`가 함께 만들어진다

   ```bash
   docker compose up -d
   ```

3. 프로젝트 멤버 시드: 기본은 `../data/seed/members.csv`의 예시 멤버다. 실제 구글 로그인 이메일은 같은 형식의 `../data/seed/members.local.csv`에 적는다 (커밋되지 않고, 있으면 이 파일을 대신 읽는다). 서버가 뜰 때 자동 반영된다

4. 실행

   ```bash
   ./gradlew bootRun
   ```

5. 확인
   - `http://localhost:8080/api/v1/health` — 서버 동작 확인 (인증 불필요)
   - `http://localhost:8080/api/v1/auth/login/google` — 구글 로그인 시작
   - `http://localhost:8080/api/v1/auth/me` — 로그인 상태 확인 (로그인 성공하면 여기로 리다이렉트)
   - `http://localhost:8080/api/v1/auth/logout` — 로그아웃

인증 엔드포인트 전체 명세는 [../docs/openapi.yaml](../docs/openapi.yaml)의 `Auth` 태그 참고.

## 아직 안 된 것 (TODO)

- 로그인 사용자를 `Member`와 연결 (엔티티와 시드는 있음, 지금 로그인은 세션에만 유지돼 서버 재시작하면 풀림)
- QA 도메인 API (등록·PATCH·재검증 등) — 엔티티·리포지토리만 있고 서비스·컨트롤러는 아직 없음
- 스키마는 개발 중 `ddl-auto: update`로 맞춘다. 배포 전에 마이그레이션 도구로 전환
- 세션 쿠키 방식 유지할지, 로그인 성공 후 자체 JWT 발급할지 결정
- 에러 처리 (로그인 실패 시 응답 등)

전체 API 설계는 [../docs/SPEC.md](../docs/SPEC.md) / [../docs/openapi.yaml](../docs/openapi.yaml) 참고.
