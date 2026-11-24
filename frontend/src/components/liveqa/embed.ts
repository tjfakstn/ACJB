import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'

// 배포 사이트에 넣은 QAting 스니펫(public/embed.js)과 주고받는 메시지(프로토콜 v1, 2026-11-20).
// 배포 화면은 다른 도메인 iframe이라 QAting이 직접 스크롤·요소를 읽을 수 없다 — 스니펫이 안에서 알려 준다.
// 받는 메시지는 그 iframe 창이 보내고, 출처가 배포 주소와 같은 것만 믿는다.

export const EMBED_SNIPPET_PATH = '/embed.js'

export type EmbedStatus = 'waiting' | 'connected' | 'absent'

export interface PinPosition {
  key: string
  found: boolean
  /** iframe 화면 기준 CSS 픽셀 */
  x?: number
  y?: number
  visible?: boolean
}

export interface PickedElement {
  path: string
  /** 페이지 맨 위 기준 */
  point: { x: number; y: number }
  /** 지금 화면 기준 */
  client: { x: number; y: number }
  element: { selector: string; text: string; box: { x: number; y: number; width: number; height: number } }
  viewport: { width: number; height: number }
}

export interface TrackedPin {
  key: string
  selector: string
  /** 요소 상자 안의 점 비율 */
  rx: number
  ry: number
}

/** 스니펫이 없다고 보기까지 기다리는 시간 — 느린 SPA도 이 안에 스크립트가 돈다 */
const ABSENT_AFTER_MS = 5000

export function useEmbed(frameRef: RefObject<HTMLIFrameElement | null>, pageUrl: string | null, handlers: {
  onPicked: (p: PickedElement) => void
  onPickCancel: () => void
}) {
  const [status, setStatus] = useState<EmbedStatus>('waiting')
  const [path, setPath] = useState<string | null>(null)
  const [positions, setPositions] = useState<Record<string, PinPosition>>({})
  // 연결된 뒤 iframe 안에서 다른 페이지로 갔는데 스니펫이 답하지 않으면 — 로그인·외부 주소로 나간 것
  const [left, setLeft] = useState(false)
  const leaveTimer = useRef<number | null>(null)
  const handlersRef = useRef(handlers)
  handlersRef.current = handlers
  const origin = originOf(pageUrl)

  const post = useCallback((type: string, data: Record<string, unknown> = {}) => {
    const win = frameRef.current?.contentWindow
    if (win && origin) win.postMessage({ source: 'qating', v: 1, type, ...data }, origin)
  }, [frameRef, origin])

  // 화면(iframe 주소)이 바뀌면 처음부터 다시 연결한다
  useEffect(() => {
    setStatus('waiting')
    setPath(null)
    setPositions({})
    setLeft(false)
    if (!pageUrl) return
    const timer = window.setTimeout(() => setStatus((s) => (s === 'connected' ? s : 'absent')), ABSENT_AFTER_MS)
    return () => window.clearTimeout(timer)
  }, [pageUrl])

  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      if (!origin || e.origin !== origin || e.source !== frameRef.current?.contentWindow) return
      const m = e.data as { source?: string; type?: string; [k: string]: unknown }
      if (!m || m.source !== 'qating-embed' || typeof m.type !== 'string') return
      switch (m.type) {
        case 'announce':
          post('hello')
          break
        case 'ready':
          if (leaveTimer.current !== null) window.clearTimeout(leaveTimer.current)
          leaveTimer.current = null
          setLeft(false)
          setStatus('connected')
          if (typeof m.path === 'string') setPath(m.path)
          break
        case 'path':
          if (typeof m.path === 'string') setPath(m.path)
          break
        case 'positions': {
          if (typeof m.path === 'string') setPath(m.path)
          const next: Record<string, PinPosition> = {}
          for (const p of (Array.isArray(m.pins) ? m.pins : []) as PinPosition[]) {
            if (p && typeof p.key === 'string') next[p.key] = p
          }
          setPositions(next)
          break
        }
        case 'picked':
          handlersRef.current.onPicked(m as unknown as PickedElement)
          break
        case 'pickcancel':
          handlersRef.current.onPickCancel()
          break
      }
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [origin, frameRef, post])

  /** iframe이 다 열리면 먼저 인사한다 — 스니펫의 announce를 놓쳤어도 연결된다 */
  const onFrameLoad = useCallback(() => {
    post('hello')
    if (leaveTimer.current !== null) window.clearTimeout(leaveTimer.current)
    leaveTimer.current = window.setTimeout(() => { leaveTimer.current = null; setLeft(true) }, ABSENT_AFTER_MS)
  }, [post])
  useEffect(() => () => { if (leaveTimer.current !== null) window.clearTimeout(leaveTimer.current) }, [])
  const track = useCallback((pins: TrackedPin[]) => post('track', { pins }), [post])
  const pick = useCallback((on: boolean) => post('pick', { on }), [post])
  const scrollTo = useCallback((selector: string) => post('scrollTo', { selector }), [post])

  return { status, path, positions, left, onFrameLoad, track, pick, scrollTo }
}

function originOf(url: string | null): string | null {
  if (!url) return null
  try {
    return new URL(url).origin
  } catch {
    return null
  }
}
