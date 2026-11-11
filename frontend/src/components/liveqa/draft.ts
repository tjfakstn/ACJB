import type { AppPlatform, LocationBasis, QaCapture, Severity, Viewport } from '../../lib/api'

/** 서버 캡처와 같은 기준 화면 크기(시안 1280 × 720). 핀 좌표는 이 크기 기준 픽셀이다 */
export const LIVE_VIEWPORT: Viewport = { width: 1280, height: 720 }

export type DraftCapture =
  | { state: 'loading' }
  | { state: 'ok'; capture: QaCapture }
  | { state: 'failed'; message: string }
  | { state: 'none' }

/** 작성 중인 수동 QA(시안 M-03~M-04D). 저장 실패 때 같은 requestId로 다시 보내 중복 저장을 막는다(M-08B) */
export interface Draft {
  requestId: string
  basis: LocationBasis
  x: number
  y: number
  viewport: Viewport
  pagePath: string
  title: string
  description: string
  severity: Severity | null
  expected: string
  actual: string
  /** 기준 Figma 프레임(AC33) — 찍은 경로에 연결된 프레임이 하나면 미리 고른다. 사람이 바꾸거나 비울 수 있다 */
  frameId: string | null
  /** 발생 환경과 "이 플랫폼에서만"(앱 지원, 2026-11-11) */
  platform: AppPlatform
  platformOnly: boolean
  capture: DraftCapture
  expanded: boolean
  saving: boolean
  errors: { title?: string; description?: string }
  saveError: string | null
}

export function newRequestId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export function hasContent(d: Draft): boolean {
  return [d.title, d.description, d.expected, d.actual].some((v) => v.trim() !== '')
}
