# ACJB Backend

Spring Boot 4.1 / Java 21 / Gradle / MySQL 8.4. QAting의 API 서버입니다.

## 로컬 실행

설치·키 발급·실행 순서는 저장소 루트 [README의 실행 방법](../README.md#실행-방법)을 따릅니다.

```bash
cp .env.example .env   # 필수 키 채우기
docker compose up -d   # MySQL
./gradlew bootRun      # http://localhost:8080
./gradlew test
```

전체 API 명세는 [../docs/SPEC.md](../docs/SPEC.md)(정본) / [../docs/openapi.yaml](../docs/openapi.yaml) 참고.

## DB 스키마 (Flyway)

- 테이블은 서버가 켜질 때 Flyway가 `src/main/resources/db/migration/V*.sql`을 순서대로 적용해 만든다. Hibernate는 `ddl-auto: validate`로 엔티티와 테이블이 맞는지 검사만 한다
- 스키마를 바꾸려면 엔티티를 고치고 **새 파일** `V2__설명.sql`(다음 번호)을 추가한다. 이미 적용된 V 파일은 고치지 않는다 — 체크섬이 달라져 서버가 뜨지 않는다
- enum 값을 추가할 때도 마이그레이션이 필요하다 — 테이블에 허용 값 CHECK 제약이 걸려 있다(예: `qa_issues_chk_4`)
- 테스트(`qating_test`)는 컨텍스트가 뜰 때마다 비우고 V1부터 다시 적용한다(`TestFlywayConfig`)
- Flyway 도입(2026-11-05) 전에 만든 로컬 DB는 기록 테이블이 없어 서버가 뜨지 않는다 — 로컬 `qating` 스키마를 비우고 다시 띄우면 된다
