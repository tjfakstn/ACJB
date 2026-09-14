# ACJB Backend

Spring Boot 4.1 / Java 21 / Gradle. 현재는 구글 소셜로그인(OAuth2 Login) 스켈레톤만 있는 상태입니다.

## 로컬 실행

1. `.env.example`을 `.env`로 복사하고 Google OAuth Client ID/Secret 채우기

   ```bash
   cp .env.example .env
   ```

   Client ID/Secret은 [Google Cloud Console](https://console.cloud.google.com/apis/credentials)에서 발급 (승인된 리디렉션 URI: `http://localhost:8080/login/oauth2/code/google`)

2. 실행

   ```bash
   ./gradlew bootRun
   ```

3. 확인
   - `http://localhost:8080/api/v1/health` — 서버 동작 확인 (인증 불필요)
   - `http://localhost:8080/oauth2/authorization/google` — 구글 로그인 시작
   - `http://localhost:8080/api/v1/auth/me` — 로그인 상태 확인 (로그인 성공하면 여기로 리다이렉트)

## 아직 안 된 것 (TODO)

- DB 연동 (유저 저장 — 지금은 세션에만 유지, 서버 재시작하면 로그인 풀림)
- React(client)와의 연동 (CORS는 `localhost:5173` 허용해뒀지만, 로그인 성공 후 리다이렉트를 프론트 주소로 보내는 처리 안 됨)
- 세션 쿠키 방식 유지할지, 로그인 성공 후 자체 JWT 발급할지 결정 ([../docs/api_spec.md](../docs/api_spec.md) 6장)
- 에러 처리 (로그인 실패 시 응답 등)

전체 API 설계는 [../docs/api_spec.md](../docs/api_spec.md) 참고.
