// 프로젝트 목록/연결 플로우에서 공유하는 타입.
// NOTE: 아직 백엔드 연동 전이라 UI 로컬 상태용 형태다.
// openapi.yaml에는 /projects 목록 조회·연결(생성) 엔드포인트가 아직 없으므로(= /projects/{projectId}/... 만 존재),
// 실제 API가 정해지면(설만수/오단비) 서버 응답 스키마에 맞춰 이 타입을 교체할 것.

export interface Project {
  id: string
  name: string
  description?: string
  githubLink?: string
  figmaLink?: string
  /** QA 결과 건수 — 백엔드 분석 결과에서 받는다(프론트에서 만들지 않음) */
  qaCount: number
  /** FAIL(디자인-구현 불일치) 건수 — 백엔드 분석 결과에서 받는다. 0이면 '이상 없음'으로 분류 */
  failCount: number
  /** 마지막 분석 시점 라벨 (예: '방금 전', '3일 전') */
  lastAnalyzedLabel: string
}

/** 새 프로젝트 연결 폼 입력값 */
export interface ConnectProjectInput {
  name: string
  description: string
  githubLink: string
  figmaLink: string
}

export type ConnectService = 'github' | 'figma'

/** 연결+분석 요청 결과. 성공 시에만 집계(qa/fail)를 돌려받고, 실패 시 어느 서비스가 문제인지 알려준다. */
export type ConnectResult =
  | { ok: true; qaCount: number; failCount: number }
  | { ok: false; service: ConnectService; message: string }
