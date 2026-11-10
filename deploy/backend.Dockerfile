# QAting 백엔드(Spring Boot) — 빌드 컨텍스트는 저장소 루트(processResources가 src/qating 프롬프트·스키마를 읽는다)
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY backend/ backend/
COPY src/qating/ src/qating/
WORKDIR /src/backend
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon -q

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=build /src/backend/build/libs/*.jar /app/app.jar
# 단일 컴포넌트 scan·배포 화면 캡처용 헤드리스 크로미움(Playwright)과 OS 라이브러리
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN java -Dloader.main=com.microsoft.playwright.CLI -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
      install --with-deps chromium \
    && rm -rf /var/lib/apt/lists/*
ENV TZ=Asia/Seoul
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
