-- 2026-11-11 우리 서버 계정이 읽지 못하는 Figma 파일은 프로젝트 소유자 본인 토큰으로 읽는다(AC21).
-- 토큰은 AES-GCM으로 암호화해 저장하고(키는 서버 환경 변수 FIGMA_TOKEN_KEY), 화면에는 끝 4자리만 보여 준다
ALTER TABLE projects
    ADD COLUMN figma_token_enc  VARCHAR(1024) NULL,
    ADD COLUMN figma_token_hint VARCHAR(16)   NULL;
