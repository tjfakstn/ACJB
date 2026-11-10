# QAting 프론트(정적 SPA) + Caddy(HTTPS 자동 발급, /api는 백엔드로 넘김) — 빌드 컨텍스트는 저장소 루트
FROM node:22-alpine AS build
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM caddy:2-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/frontend/dist /srv
