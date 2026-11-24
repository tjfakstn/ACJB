import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, openOverlaySession, type OverlaySession } from '../../lib/api'

/**
 * [사이트에서 QA 시작] — 배포 사이트를 원래 주소 그대로 새 탭에서 열고, 그 탭의 스니펫이 인사하면 입장권을 넘긴다(2026-11-24).
 * 입장권은 주소에 담지 않는다. 우리가 연 그 창(e.source)이 배포 출처(e.origin)에서 보낸 인사에만 답한다.
 * 팝업 차단을 피하려고 클릭 순간 빈 탭을 먼저 열고, 입장권을 받은 뒤 그 탭의 주소를 바꾼다.
 */
export function useOverlayLauncher(projectId: string, onRegistered: () => void) {
  const [state, setState] = useState<'idle' | 'opening' | 'waiting' | 'connected' | 'nosnippet' | 'error'>('idle')
  const [error, setError] = useState<string | null>(null)
  const winRef = useRef<Window | null>(null)
  const waitTimer = useRef<number | null>(null)
  const sessionRef = useRef<OverlaySession | null>(null)
  const registeredRef = useRef(onRegistered)
  registeredRef.current = onRegistered

  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      const s = sessionRef.current
      if (!s || !winRef.current || e.source !== winRef.current || e.origin !== s.target_origin) return
      const m = e.data as { source?: string; type?: string }
      if (!m || m.source !== 'qating-overlay') return
      if (m.type === 'overlay-hello') {
        winRef.current.postMessage({ source: 'qating', v: 1, type: 'overlay-token', token: s.token, expires_at: s.expires_at, project_id: projectId }, s.target_origin)
        if (waitTimer.current !== null) window.clearTimeout(waitTimer.current)
        setState('connected')
      } else if (m.type === 'overlay-registered') {
        registeredRef.current()
      }
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [projectId])

  const start = useCallback((pagePath: string | null) => {
    setError(null)
    const win = window.open('about:blank', '_blank')
    if (!win) {
      setState('error')
      setError('브라우저가 새 탭을 막았어요. 팝업을 허용해 주세요')
      return
    }
    win.document.title = 'QAting — 배포 사이트를 여는 중'
    winRef.current = win
    setState('opening')
    openOverlaySession(projectId, pagePath)
      .then((s) => {
        sessionRef.current = s
        win.location.href = s.open_url
        setState('waiting')
        // 사이트가 열리고 10초 안에 스니펫이 인사하지 않으면 스니펫이 없는 사이트 — 설치 한 줄을 안내한다
        if (waitTimer.current !== null) window.clearTimeout(waitTimer.current)
        waitTimer.current = window.setTimeout(() => setState((st) => (st === 'waiting' ? 'nosnippet' : st)), 10000)
      })
      .catch((e: unknown) => {
        win.close()
        setState('error')
        setError(e instanceof ApiError ? e.message : '입장권을 받지 못했어요')
      })
  }, [projectId])

  return { state, error, start }
}
