# CLAUDE.md — QAting

> Claude Code가 이 저장소에서 세션을 시작할 때 자동으로 읽는 진입점 파일.
> 규칙, 용어집, 운영 정보는 전부 `AGENTS.md`에 있고, 이 파일은 그것을 끌어올리기만 한다 — 어느 도구를 쓰든 같은 문서를 읽게 하기 위해서다. 여기에 규칙을 복제하지 않는다.

@AGENTS.md

## Claude Code에서만 해당하는 것

- 로드 확인: 새 세션에서 `/context` — 목록에 `CLAUDE.md`와 `AGENTS.md`가 함께 있어야 정상. `docs/SPEC.md`, `docs/PROBLEM.md`, `docs/ontology.yaml`은 요청 시 읽는 파일이라 목록에 없는 것이 정상.
