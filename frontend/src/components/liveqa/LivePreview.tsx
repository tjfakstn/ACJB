import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { captureImageUrl, type DeployTarget, type FramePreview, type LiveProxy, type QaCapture, type QaIssue, manualQaId } from '../../lib/api'
import { Icon } from '../common/Icon'
import { LIVE_VIEWPORT } from './draft'
import { EMBED_SNIPPET_PATH, useEmbed, type EmbedStatus, type PickedElement } from './embed'

// 위쪽 배포 화면(시안 W-01 Live Preview, M-01~M-03·M-09B·C).
//  - 사이트는 1280×720 기준 iframe으로 띄우고 패널 너비에 맞춰 축소한다. 좌표는 언제나 1280×720 기준 픽셀이다
//  - QA 항목 목록의 [수동 QA 등록]을 누르면 투명한 레이어가 클릭을 받는다(툴바 버튼은 잘 보이지 않아 2026-11-10 뺐다) — 그동안 사이트의 버튼은 눌리지 않는다(M-02)
//  - 교차 출처 iframe은 경로·스크롤을 읽을 수 없다. 그래서 실시간 화면 위에는 핀을 그리지 않는다(화면을 조작하면 핀이 따라가지 못했다).
//    [수동 QA 등록]을 누르면 서버가 지금 경로를 전체 페이지로 찍고, 그 캡처 위에서(스크롤 가능) 위치를 고른다 — 핀은 언제나 그 캡처 위에 그린다(2026-11-20)
//  - 배포 사이트에 QAting 스니펫(embed.js)이 있으면 Figma 댓글처럼 실시간 화면의 요소를 직접 고르고, 핀이 그 요소를 따라간다(2026-11-20).
//    스니펫이 알려 준 요소 위치로 iframe 위에 핀을 그린다. 스니펫이 없는 사이트는 위의 캡처 방식으로 등록한다
//  - 기본은 연결 화면(프록시): 서버가 배포 사이트를 다른 호스트로 대신 띄우며 스니펫을 넣어 준다 — 사이트를 고치지 않아도 핀이 붙는다.
//    스니펫이 응답하지 않으면(사이트가 깨짐) 원래 화면으로 자동으로 바꾸고, 사람도 언제든 바꿀 수 있다
//  - 서버가 화면 삽입이 막혔다고 알려 주면(embeddable false) 사이트 대신 제한 안내와 [스크린샷 첨부]를 보여 준다(M-09B)
/** 자동 QA 항목을 고르면 보여 주는 Figma 프레임(AC32) — 첫 QA 이슈에는 배포 경로가 없어 배포 화면으로 옮길 수 없다 */
export type FigmaView =
  | { state: 'loading'; title: string }
  | { state: 'ok'; title: string; preview: FramePreview }
  | { state: 'error'; title: string; message: string }

export interface PreviewSurface {
  /** live: 배포 화면 iframe, image: 첨부 이미지 위에서 위치 지정(M-09C), figma: 자동 QA 항목의 Figma 기준 디자인 */
  kind: 'live' | 'image' | 'figma'
  attachment: QaCapture | null
  figma?: FigmaView
}

export function LivePreview({
  projectId, target, path, onPathChange, reloadKey, onReload, surface, registerMode, onPick, pins, draftPin,
  selectedId, onSelectPin, onAttachForImage, attachBusy, attachError, isOwner, onEditUrl, runLabel, onBackToLive, pageShot, onRetryPageShot,
  pickElement, onElementPicked, onPickCancel, onEmbedChange, liveDraft, proxy, viewMode, onViewMode, proxyFailed, children,
}: {
  projectId: string
  target: DeployTarget
  path: string
  onPathChange: (path: string) => void
  reloadKey: number
  onReload: () => void
  surface: PreviewSurface
  registerMode: boolean
  onPick: (x: number, y: number, viewport: { width: number; height: number }) => void
  pins: QaIssue[]
  draftPin: { x: number; y: number; viewport: { width: number; height: number }; number: number } | null
  selectedId: string | null
  onSelectPin: (id: string) => void
  onAttachForImage: (file: File) => void
  attachBusy: boolean
  attachError: string | null
  isOwner: boolean
  onEditUrl: () => void
  runLabel: ReactNode
  /** Figma 프레임·캡처를 보다가 배포 화면으로 돌아간다 */
  onBackToLive: () => void
  /** 등록용 전체 페이지 캡처 진행 상태 — 실시간 화면 위에 안내로 보여 준다 */
  pageShot: { state: 'loading' } | { state: 'error'; message: string } | null
  onRetryPageShot: () => void
  /** 스니펫으로 실시간 화면의 요소를 고르는 중 */
  pickElement: boolean
  onElementPicked: (p: PickedElement) => void
  onPickCancel: () => void
  /** 스니펫 연결 상태와 스니펫이 알려 준 지금 경로 */
  onEmbedChange: (status: EmbedStatus, path: string | null) => void
  /** 실시간 화면에서 작성 중인 핀(화면 기준 좌표) */
  liveDraft: { x: number; y: number; number: number } | null
  /** 연결 화면(프록시) 입장 정보와 지금 보기 방식 */
  proxy: LiveProxy | null
  viewMode: 'proxy' | 'direct'
  onViewMode: (mode: 'proxy' | 'direct', failed?: boolean) => void
  /** 연결 화면으로 열지 못해 원래 화면으로 바꿨는지 */
  proxyFailed: boolean
  /** 작성 Popover — 캔버스 위에 겹친다 */
  children?: ReactNode
}) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const [width, setWidth] = useState(0)
  const [pathInput, setPathInput] = useState(path)
  const [cursor, setCursor] = useState<{ x: number; y: number } | null>(null)

  useEffect(() => setPathInput(path), [path])

  useLayoutEffect(() => {
    const el = wrapRef.current
    if (!el) return
    const ro = new ResizeObserver(() => setWidth(el.clientWidth))
    ro.observe(el)
    setWidth(el.clientWidth)
    return () => ro.disconnect()
  }, [])

  const viaProxy = viewMode === 'proxy' && !!proxy?.enabled && !!proxy.enter_url
  // 연결 화면은 삽입 차단 헤더를 빼고 보여 주므로, 원래 사이트가 삽입을 막아도 볼 수 있다
  const blocked = target.embeddable === false && !viaProxy
  const image = surface.kind === 'image' ? surface.attachment : null
  // 서버가 찍은 배포 화면(전체 페이지) — 첨부 이미지와 같은 캔버스에 그리되 안내를 달리한다
  const shot = image?.kind === 'auto' ? image : null
  const figma = surface.kind === 'figma' ? surface.figma ?? null : null
  const figmaOk = figma?.state === 'ok' ? figma.preview : null
  const base = figmaOk ? { width: figmaOk.width, height: figmaOk.height }
    : { width: image?.width ?? LIVE_VIEWPORT.width, height: image?.height ?? LIVE_VIEWPORT.height }
  const scale = width > 0 ? width / base.width : 0
  // 첨부 이미지가 아주 길면(전체 페이지 스크린샷) 캔버스 높이를 기준 화면의 1.6배까지만 두고 안에서 스크롤한다
  const maxHeight = (LIVE_VIEWPORT.height / LIVE_VIEWPORT.width) * width * 1.6
  const canvasHeight = Math.min(base.height * scale, maxHeight)
  const pageUrl = target.url ? joinUrl(target.url, path) : null
  const frameSrc = !pageUrl ? null : viaProxy ? `${proxy!.enter_url}&path=${encodeURIComponent(pathOf(pageUrl))}` : pageUrl

  // 스니펫 — 실시간 화면을 보고 있을 때만 연결한다
  const frameRef = useRef<HTMLIFrameElement>(null)
  const liveUrl = surface.kind === 'live' && !blocked && frameSrc ? `${reloadKey}|${frameSrc}` : null
  const embed = useEmbed(frameRef, liveUrl ? frameSrc : null, { onPicked: onElementPicked, onPickCancel })
  // 연결 화면인데 스니펫이 응답하지 않으면 사이트가 프록시에서 깨진 것 — 원래 화면으로 바꾼다
  useEffect(() => {
    if (viaProxy && embed.status === 'absent') onViewMode('direct', true)
  }, [viaProxy, embed.status, onViewMode])
  const embedConnected = embed.status === 'connected'
  useEffect(() => onEmbedChange(embed.status, embed.path), [embed.status, embed.path, onEmbedChange])
  // 요소가 남은 핀(스니펫 등록·서버 캡처 등록) 중 지금 경로의 것 — 스니펫이 그 요소를 찾아 위치를 알려 준다
  const livePins = useMemo(() => pins.filter((i) => {
    const m = i.manual_qa
    return !!m?.element && !!m.anchor && m.page_path === embed.path
  }), [pins, embed.path])
  const trackKey = livePins.map((i) => i.id).join(',')
  const { track, pick, scrollTo } = embed
  useEffect(() => {
    if (!embedConnected) return
    track(livePins.map((i) => {
      const m = i.manual_qa!
      const box = m.element!.box
      return {
        key: i.id, selector: m.element!.selector,
        rx: box.width > 0 ? Math.min(1, Math.max(0, (m.anchor!.x_px - box.x) / box.width)) : 0.5,
        ry: box.height > 0 ? Math.min(1, Math.max(0, (m.anchor!.y_px - box.y) / box.height)) : 0.5,
      }
    }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [embedConnected, trackKey, track])
  useEffect(() => {
    if (embedConnected) pick(pickElement)
  }, [embedConnected, pickElement, pick])
  // 목록에서 고른 핀이 지금 화면에 있으면 그 요소로 스크롤한다
  const selectedSelector = livePins.find((i) => i.id === selectedId)?.manual_qa?.element?.selector ?? null
  useEffect(() => {
    if (embedConnected && selectedSelector) scrollTo(selectedSelector)
  }, [embedConnected, selectedSelector, scrollTo])
  const missing = embedConnected ? livePins.filter((i) => embed.positions[i.id] && !embed.positions[i.id]!.found).length : 0

  // 레이어는 축소된 캔버스와 같은 크기라 화면 좌표를 축척으로 나누면 기준 크기 좌표가 된다(스크롤해도 rect가 함께 움직인다)
  const toLocal = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect()
    const x = Math.floor((e.clientX - rect.left) / scale)
    const y = Math.floor((e.clientY - rect.top) / scale)
    return { x: Math.max(0, Math.min(base.width - 1, x)), y: Math.max(0, Math.min(base.height - 1, y)) }
  }

  const status = surface.kind === 'figma'
    ? { tone: 'bg-qa-primary/10 text-qa-primary-hover', dot: 'bg-qa-primary', text: 'Figma 기준 디자인 · 배포 화면 아님' }
    : surface.kind === 'image' && shot
      ? { tone: 'bg-[#7c3aed]/10 text-[#7c3aed]', dot: 'bg-[#7c3aed]', text: `배포 화면 캡처 · ${formatShotTime(shot.created_at)} · 실시간 화면 아님` }
    : surface.kind === 'image' && image
      ? { tone: 'bg-[#7c3aed]/10 text-[#7c3aed]', dot: 'bg-[#7c3aed]', text: '첨부 이미지 · 실시간 화면 아님' }
      : blocked
    ? { tone: 'bg-qa-warning/10 text-[#c8161a]', dot: 'bg-qa-warning', text: '화면 삽입 제한 · 실시간 표시 불가' }
    : !target.url
      ? { tone: 'bg-qa-surface text-qa-text-sub', dot: 'bg-qa-sub', text: '배포 주소 없음' }
      : target.embeddable === null
        // 서버가 주소에 연결하지 못했다 — 화면은 그대로 띄우되 정상처럼 보이지 않게 알린다(M-09B "정상 표시처럼 보이지 않게")
        ? { tone: 'bg-[#fff4e5] text-[#a85a00]', dot: 'bg-[#f59e0b]', text: '연결 확인 안 됨' }
        : { tone: 'bg-qa-success/10 text-qa-success', dot: 'bg-qa-success', text: viaProxy ? 'Live Preview · 연결 화면' : 'Live Preview · 현재 배포 화면' }

  return (
    <section className={`overflow-hidden rounded-xl border bg-white ${registerMode ? 'border-[#7c3aed] ring-2 ring-[#7c3aed]/20' : 'border-qa-border'}`}
      aria-label="배포 화면">
      {/* 툴바 */}
      <div className="flex flex-wrap items-center gap-2 border-b border-qa-border px-3 py-2">
        <span title={target.embeddable === null && target.url ? target.embed_blocked_reason ?? undefined : undefined}
          className={`inline-flex h-6 flex-none items-center gap-1.5 rounded-full px-2.5 text-xs font-medium ${status.tone}`}>
          <span className={`h-1.5 w-1.5 rounded-full ${status.dot}`} />{status.text}
        </span>
        <form className="flex h-8 min-w-[220px] flex-1 items-center gap-2 rounded-lg border border-qa-border bg-qa-surface px-2.5 text-sm"
          onSubmit={(e) => {
            e.preventDefault()
            const p = pathInput.trim() || '/'
            onPathChange(p.startsWith('/') ? p : `/${p}`)
          }}>
          <Icon name="globe" className="h-4 w-4 flex-none text-qa-text-sub" />
          {target.url ? (
            <>
              <span className="truncate text-qa-text-sub" title={target.url}>{originOf(target.url)}</span>
              <input value={pathInput} onChange={(e) => setPathInput(e.target.value)} aria-label="페이지 경로"
                className="min-w-[60px] flex-1 bg-transparent text-black outline-none" />
            </>
          ) : <span className="flex-1 text-qa-text-sub">배포 주소가 없어요</span>}
          <span className="flex-none text-xs text-qa-text-sub">
            {target.source === 'github_deployment' ? 'GitHub 배포' : target.source === 'manual' ? '배포 URL' : ''}
          </span>
          {isOwner && (
            <button type="button" onClick={onEditUrl} aria-label="배포 주소 바꾸기" title="배포 주소 바꾸기"
              className="flex-none rounded p-0.5 text-qa-text-sub hover:bg-white hover:text-black">
              <Icon name="pencil" className="h-3.5 w-3.5" />
            </button>
          )}
        </form>
        <button type="button" onClick={onReload} aria-label="새로고침" title="새로고침" disabled={!target.url}
          className="flex h-8 w-8 flex-none items-center justify-center rounded-lg border border-qa-border text-black hover:bg-qa-surface disabled:opacity-40">
          <Icon name="refresh" className="h-4 w-4" />
        </button>
        {pageUrl && (
          <a href={pageUrl} target="_blank" rel="noreferrer noopener" aria-label="새 탭에서 열기" title="새 탭에서 열기"
            className="flex h-8 w-8 flex-none items-center justify-center rounded-lg border border-qa-border text-black hover:bg-qa-surface">
            <Icon name="external-link" className="h-4 w-4" />
          </a>
        )}
        <span className="flex flex-none items-center gap-1.5 text-xs text-qa-text-sub">QA 실행 상태 {runLabel}</span>
        <span className="flex flex-none items-center gap-1 text-xs text-qa-text-sub"><Icon name="monitor" className="h-4 w-4" />{base.width} × {base.height}</span>
      </div>

      {surface.kind === 'figma' && figma && (
        <div className="flex flex-wrap items-center gap-2 bg-qa-primary/10 px-4 py-1.5 text-xs text-qa-primary-hover" role="status">
          <Icon name="figma" className="h-3.5 w-3.5" />
          <span className="min-w-0 flex-1">자동 QA 항목 · {figma.title} — 배포 경로가 없는 항목이라 Figma 기준 디자인을 보여 줘요{figmaOk?.node_box ? ' · 표시한 곳이 비교한 요소예요' : ''}</span>
          <button type="button" onClick={onBackToLive} className="flex-none font-medium underline-offset-2 hover:underline">배포 화면으로 돌아가기</button>
        </div>
      )}
      {target.url && target.embeddable === null && surface.kind === 'live' && (
        <div className="flex items-center gap-2 bg-[#fff4e5] px-4 py-1.5 text-xs text-[#a85a00]" role="status">
          <Icon name="alert-triangle" className="h-3.5 w-3.5" />
          {target.embed_blocked_reason ?? '서버가 배포 주소에 연결하지 못했어요.'} 화면이 비어 보이면 주소를 확인해 주세요. 위치를 찍으면 캡처는 실패할 수 있어요.
        </div>
      )}
      {surface.kind === 'image' && image && (
        <div className="flex flex-wrap items-center gap-2 bg-[#7c3aed]/10 px-4 py-1.5 text-xs text-[#7c3aed]" role="status">
          <Icon name="camera" className="h-3.5 w-3.5" />
          <span className="min-w-0 flex-1">
            {shot ? `서버가 찍은 ${image.full_page ? '전체 페이지' : '첫 화면'}이에요 — 핀은 이 캡처 위에 고정돼 있어 화면을 조작해도 어긋나지 않아요`
              : '첨부한 이미지예요 — 핀은 이 이미지 위에 고정돼 있어요'}
          </span>
          {target.url && !blocked && (
            <button type="button" onClick={onBackToLive} className="flex-none font-medium underline-offset-2 hover:underline">실시간 배포 화면으로 돌아가기</button>
          )}
        </div>
      )}
      {surface.kind === 'live' && pageShot && (
        pageShot.state === 'loading' ? (
          <div className="flex items-center gap-2 bg-[#7c3aed] px-4 py-1.5 text-xs text-white" role="status">
            <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white border-t-transparent" />
            지금 화면({path})을 서버에서 전체 페이지로 찍는 중이에요(최대 30초) — 찍은 화면 위에서 위치를 골라요
          </div>
        ) : (
          <div className="flex flex-wrap items-center gap-2 bg-qa-warning/10 px-4 py-1.5 text-xs text-[#c8161a]" role="alert">
            <Icon name="alert-triangle" className="h-3.5 w-3.5" />
            <span className="min-w-0 flex-1">화면을 찍지 못했어요 — {pageShot.message} 스크린샷을 첨부해 그 위에서 찍을 수도 있어요.</span>
            <button type="button" onClick={onRetryPageShot} className="flex-none font-medium underline-offset-2 hover:underline">다시 찍기</button>
            <button type="button" onClick={() => fileRef.current?.click()} disabled={attachBusy} className="flex-none font-medium underline-offset-2 hover:underline">스크린샷 첨부</button>
          </div>
        )
      )}
      {surface.kind === 'live' && !pageShot && target.url && !blocked && !registerMode && embedConnected && !(viaProxy && embed.left) && (
        <div className="flex flex-wrap items-center gap-2 bg-qa-success/10 px-4 py-1.5 text-xs text-qa-success" role="status">
          <Icon name="link" className="h-3.5 w-3.5" />
          <span className="min-w-0 flex-1">보기 전용 · 핀 번호가 화면 요소를 따라가요{livePins.length > 0 ? ` · 이 화면 핀 ${livePins.length}개` : ''}</span>
          {missing > 0 && <span className="flex-none text-[#a85a00]">화면에서 요소를 찾지 못한 항목 {missing}개 — 항목을 고르면 등록 당시 캡처로 볼 수 있어요</span>}
        </div>
      )}
      {surface.kind === 'live' && !pageShot && target.url && !blocked && !registerMode && viaProxy && embed.left && (
        <div className="flex flex-wrap items-center gap-2 bg-[#f08c00]/10 px-4 py-1.5 text-xs text-[#a85a00]" role="status">
          <Icon name="alert-triangle" className="h-3.5 w-3.5" />
          <span className="min-w-0 flex-1">연결 화면을 벗어났어요(로그인·외부 주소). 연결 화면은 보기 전용 미리보기라 로그인이 필요한 화면은 원래 화면(스니펫)이나 이미지로 등록해 주세요</span>
          {pageUrl && <a href={pageUrl} target="_blank" rel="noreferrer" className="flex-none font-medium underline-offset-2 hover:underline">원래 사이트에서 열기</a>}
          <button type="button" onClick={() => onViewMode('direct')} className="flex-none font-medium underline-offset-2 hover:underline">원래 화면으로 보기</button>
          <button type="button" onClick={() => fileRef.current?.click()} disabled={attachBusy} className="flex-none font-medium underline-offset-2 hover:underline">이미지로 등록</button>
          <button type="button" onClick={onReload} className="flex-none font-medium underline-offset-2 hover:underline">처음 화면으로</button>
        </div>
      )}
      {surface.kind === 'live' && !pageShot && target.url && !blocked && !registerMode && !viaProxy
        && (embed.status === 'absent' || (proxyFailed && embed.status !== 'connected')) && (
        <SnippetGuide hasPins={pins.some((i) => i.manual_qa?.anchor)} proxyFailed={proxyFailed}
          onUseProxy={proxy?.enabled && !viaProxy ? () => onViewMode('proxy') : null} />
      )}
      {registerMode && surface.kind === 'live' && (
        <div className="flex items-center gap-2 bg-[#7c3aed] px-4 py-1.5 text-xs text-white" role="status">
          <Icon name="map-pin" className="h-3.5 w-3.5" />
          QA 등록 모드 · 배포 화면에서 문제가 있는 요소를 클릭하세요(스크롤·이동해도 돼요). 이 동안 사이트의 버튼은 눌리지 않아요 · Esc로 종료
        </div>
      )}
      {registerMode && surface.kind !== 'live' && (
        <div className="flex items-center gap-2 bg-[#7c3aed] px-4 py-1.5 text-xs text-white" role="status">
          <Icon name="map-pin" className="h-3.5 w-3.5" />
          QA 등록 모드 · {shot ? '찍은 화면에서 문제가 있는 위치를 클릭하세요(아래로 스크롤할 수 있어요)' : '이미지에서 문제가 있는 위치를 클릭하세요'} · Esc로 종료
        </div>
      )}

      {/* 스크린샷 첨부(삽입 제한 안내·캡처 실패 안내에서 함께 쓴다) */}
      <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/webp" className="hidden"
        onChange={(e) => { const f = e.target.files?.[0]; if (f) onAttachForImage(f); e.target.value = '' }} />

      {/* 캔버스 */}
      <div ref={wrapRef} className="relative w-full overflow-auto bg-qa-surface" style={{ height: (surface.kind === 'figma' ? figmaOk : surface.kind === 'image' ? image : target.url && !blocked) && canvasHeight > 0 ? canvasHeight : 'auto' }}>
        {surface.kind === 'figma' ? (
          figmaOk ? (
            <div className="relative" style={{ width: base.width * scale, height: base.height * scale }}>
              <img src={figmaOk.image_url} alt={`${figma!.title} Figma 디자인`} className="block h-full w-full select-none bg-white" draggable={false} />
              {figmaOk.node_box && (
                <span className="pointer-events-none absolute rounded-sm border-2 border-qa-warning bg-qa-warning/10 shadow-[0_0_0_4px_rgba(239,68,68,0.15)]"
                  style={{
                    left: `${figmaOk.node_box.x_ratio * 100}%`, top: `${figmaOk.node_box.y_ratio * 100}%`,
                    width: `${figmaOk.node_box.w_ratio * 100}%`, height: `${figmaOk.node_box.h_ratio * 100}%`,
                  }} />
              )}
            </div>
          ) : figma?.state === 'loading' ? (
            <div className="flex items-center justify-center gap-2 py-24 text-sm text-qa-text-sub" role="status">
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-qa-primary border-t-transparent" />Figma 디자인을 불러오는 중이에요
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center px-6 py-16 text-center">
              <Icon name="figma" className="h-7 w-7 text-gray-900" />
              <div className="mt-3 text-sm font-bold text-black">Figma 디자인을 불러오지 못했어요</div>
              <p className="mt-1 max-w-[520px] text-xs text-qa-text-sub">{figma?.state === 'error' ? figma.message : ''}</p>
              <button type="button" onClick={onBackToLive} className="mt-4 inline-flex h-9 items-center rounded-lg border border-qa-primary px-3 text-xs font-medium text-qa-primary-hover hover:bg-qa-primary/5">배포 화면으로 돌아가기</button>
            </div>
          )
        ) : surface.kind === 'image' && image ? (
          <div className="relative" style={{ width: base.width * scale, height: base.height * scale }}>
            <img src={captureImageUrl(projectId, image.id)} alt={shot ? '서버가 찍은 배포 화면' : '첨부한 화면 이미지'} className="block h-full w-full select-none" draggable={false} />
            <Overlay {...{ registerMode, scale, base, cursor, setCursor, toLocal, onPick, pins, draftPin, selectedId, onSelectPin, attachmentId: image.id }} />
            {children}
          </div>
        ) : blocked ? (
          <div className="flex flex-col items-center justify-center px-6 py-16 text-center">
            <Icon name="wifi-off" className="h-7 w-7 text-gray-900" />
            <div className="mt-3 text-base font-bold text-black">이 배포 사이트는 화면 삽입을 허용하지 않아요</div>
            <p className="mt-1 max-w-[560px] text-xs text-qa-text-sub">
              {target.embed_blocked_reason ?? '사이트 보안 정책으로 여기서는 화면을 바로 보여 줄 수 없어요.'} 스크린샷을 첨부하면 그 이미지 위에서 위치를 지정해 등록할 수 있어요.
            </p>
            <button type="button" onClick={() => fileRef.current?.click()} disabled={attachBusy}
              className="mt-4 inline-flex h-10 items-center gap-1.5 rounded-lg bg-qa-primary px-4 text-sm font-medium text-white hover:bg-qa-primary-hover disabled:opacity-40">
              <Icon name="upload" className="h-4 w-4" />{attachBusy ? '올리는 중…' : '스크린샷 첨부'}
            </button>
            {attachError && <p role="alert" className="mt-2 text-xs text-qa-warning">{attachError}</p>}
          </div>
        ) : target.url && scale > 0 ? (
          <div className="relative" style={{ width: base.width * scale, height: base.height * scale }}>
            <iframe ref={frameRef} key={`${reloadKey}:${frameSrc}`} src={frameSrc ?? undefined} title="배포 화면" onLoad={embed.onFrameLoad}
              referrerPolicy="no-referrer" sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
              className="absolute left-0 top-0 origin-top-left border-0 bg-white"
              style={{ width: base.width, height: base.height, transform: `scale(${scale})` }} />
            {/* 핀은 스니펫이 알려 준 요소 위치에만 그린다 — 스니펫이 없으면 실시간 화면에는 핀이 없다(캡처 위에서 보여 준다) */}
            {embedConnected && livePins.map((i) => {
              const pos = embed.positions[i.id]
              if (!pos?.found || !pos.visible || pos.x == null || pos.y == null) return null
              const m = i.manual_qa!
              const active = i.id === selectedId
              return (
                <button key={i.id} type="button" onClick={() => onSelectPin(i.id)} aria-label={`${manualQaId(m.pin_number)} ${m.title}`}
                  title={`${manualQaId(m.pin_number)} · ${m.title}`}
                  className={`absolute z-20 flex h-6 w-6 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border-2 border-white text-[11px] font-bold text-white shadow ${
                    active ? 'bg-qa-primary-hover ring-4 ring-qa-primary/30' : 'bg-[#7c3aed]'} ${registerMode ? 'pointer-events-none opacity-40' : ''}`}
                  style={{ left: pos.x * scale, top: pos.y * scale }}>
                  {m.pin_number}
                </button>
              )
            })}
            {liveDraft && (
              <span className="pointer-events-none absolute z-20 flex h-6 w-6 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border-2 border-dashed border-[#7c3aed] bg-white text-[11px] font-bold text-[#7c3aed]"
                style={{ left: liveDraft.x * scale, top: liveDraft.y * scale }}>
                {liveDraft.number}
              </span>
            )}
            {children}
          </div>
        ) : !target.url ? (
          <div className="flex flex-col items-center justify-center px-6 py-14 text-center">
            <Icon name="globe" className="h-7 w-7 text-gray-900" />
            <div className="mt-3 text-base font-bold text-black">배포 주소가 없어요</div>
            <p className="mt-1 max-w-[520px] text-xs text-qa-text-sub">
              연결한 GitHub 저장소에 성공한 배포 기록(Vercel·Netlify·GitHub Pages 등)이 있으면 그 주소를 자동으로 띄워요.
              {isOwner ? ' 배포 기록이 없으면 배포 주소를 직접 입력해 주세요.' : ' 소유자가 배포 주소를 입력하면 여기에 배포 화면이 보여요.'}
            </p>
            {isOwner && (
              <button type="button" onClick={onEditUrl} className="mt-4 inline-flex h-10 items-center gap-1.5 rounded-lg bg-qa-primary px-4 text-sm font-medium text-white hover:bg-qa-primary-hover">
                배포 주소 입력
              </button>
            )}
          </div>
        ) : null}
      </div>
    </section>
  )
}

function Overlay({ registerMode, scale, base, cursor, setCursor, toLocal, onPick, pins, draftPin, selectedId, onSelectPin, attachmentId }: {
  registerMode: boolean
  scale: number
  base: { width: number; height: number }
  cursor: { x: number; y: number } | null
  setCursor: (c: { x: number; y: number } | null) => void
  toLocal: (e: React.MouseEvent<HTMLDivElement>) => { x: number; y: number }
  onPick: (x: number, y: number, viewport: { width: number; height: number }) => void
  pins: QaIssue[]
  draftPin: { x: number; y: number; viewport: { width: number; height: number }; number: number } | null
  selectedId: string | null
  onSelectPin: (id: string) => void
  attachmentId: string
}) {
  // 이 캡처·첨부 이미지 위에서 찍은 핀만 보여 준다. 위치 없이 등록한 항목(none)은 좌표가 없어 그리지 않는다
  const visible = pins.filter((i) => {
    const m = i.manual_qa!
    return !!m.anchor && m.capture_id === attachmentId
  })
  return (
    <>
      {registerMode && (
        <div className="absolute inset-0 z-10 cursor-crosshair bg-[#7c3aed]/5"
          onMouseMove={(e) => setCursor(toLocal(e))} onMouseLeave={() => setCursor(null)}
          onClick={(e) => { const p = toLocal(e); onPick(p.x, p.y, base) }}>
          {cursor && (
            <span className="pointer-events-none absolute rounded bg-gray-900/80 px-1.5 py-0.5 text-[11px] text-white"
              style={{ left: cursor.x * scale + 12, top: cursor.y * scale + 12 }}>x {cursor.x}, y {cursor.y}</span>
          )}
        </div>
      )}
      {visible.map((i) => {
        const m = i.manual_qa!
        const active = i.id === selectedId
        return (
          <button key={i.id} type="button" onClick={() => onSelectPin(i.id)} aria-label={`${manualQaId(m.pin_number)} ${m.title}`}
            title={`${manualQaId(m.pin_number)} · ${m.title}`}
            className={`absolute z-20 flex h-6 w-6 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border-2 border-white text-[11px] font-bold text-white shadow ${
              active ? 'bg-qa-primary-hover ring-4 ring-qa-primary/30' : 'bg-[#7c3aed]'} ${registerMode ? 'opacity-40' : ''}`}
            style={{ left: (m.anchor?.x_ratio ?? 0) * base.width * scale, top: (m.anchor?.y_ratio ?? 0) * base.height * scale }}>
            {m.pin_number}
          </button>
        )
      })}
      {draftPin && (
        <span className="pointer-events-none absolute z-20 flex h-6 w-6 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border-2 border-dashed border-[#7c3aed] bg-white text-[11px] font-bold text-[#7c3aed]"
          style={{ left: (draftPin.x / draftPin.viewport.width) * base.width * scale, top: (draftPin.y / draftPin.viewport.height) * base.height * scale }}>
          {draftPin.number}
        </span>
      )}
    </>
  )
}

export function originOf(url: string): string {
  try {
    const u = new URL(url)
    return `${u.origin}${u.pathname.replace(/\/+$/, '')}`
  } catch {
    return url
  }
}

/** 서버(LiveQaValidation.join)와 같은 규칙 — 배포 주소 뒤에 경로를 붙이고 쿼리는 버린다 */
export function joinUrl(base: string, path: string): string {
  try {
    const u = new URL(base)
    return `${u.origin}${u.pathname.replace(/\/+$/, '')}${path}`
  } catch {
    return base
  }
}

/** 캡처를 찍은 시각(같은 날이면 시:분) */
function formatShotTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  return d.toDateString() === now.toDateString() ? `${hm} 찍음` : `${d.getMonth() + 1}/${d.getDate()} ${hm} 찍음`
}

/** 스니펫이 없을 때 — 설치 한 줄과 지금은 캡처 위에서 등록한다는 안내 */
function SnippetGuide({ hasPins, proxyFailed, onUseProxy }: { hasPins: boolean; proxyFailed: boolean; onUseProxy: (() => void) | null }) {
  const [open, setOpen] = useState(false)
  const [copied, setCopied] = useState(false)
  const code = `<script src="${window.location.origin}${EMBED_SNIPPET_PATH}" async></script>`
  const copy = () => {
    void navigator.clipboard?.writeText(code).then(() => { setCopied(true); window.setTimeout(() => setCopied(false), 1500) })
  }
  return (
    <div className="border-b border-qa-border bg-qa-surface px-4 py-1.5 text-xs text-qa-text-sub" role="note">
      <div className="flex flex-wrap items-center gap-2">
        <Icon name="map-pin" className="h-3.5 w-3.5" />
        <span className="min-w-0 flex-1">
          {proxyFailed ? '이 사이트는 연결 화면으로 열지 못해 원래 화면으로 보여요. ' : ''}
          {proxyFailed ? '스니펫 한 줄을 넣으면 핀이 화면 요소에 정확히 붙어요' : '배포 사이트에 QAting 스니펫이 없어 실시간 화면에 핀을 붙일 수 없어요'} — 지금은 서버가 찍은 화면 위에서 등록해요{hasPins ? ' · 항목을 고르면 등록 당시 캡처 위에 핀이 보여요' : ''}
        </span>
        {onUseProxy && (
          <button type="button" onClick={onUseProxy} className="flex-none font-medium text-qa-primary-hover hover:underline">연결 화면으로 다시 보기</button>
        )}
        <button type="button" onClick={() => setOpen((v) => !v)} aria-expanded={open} className="flex-none font-medium text-qa-primary-hover hover:underline">
          {open ? '설치 방법 닫기' : '스니펫 설치 방법'}
        </button>
      </div>
      {open && (
        <div className="mt-2 rounded-lg border border-qa-border bg-white p-3 text-black">
          <p>배포 사이트의 HTML <code>&lt;head&gt;</code>(React·Vite면 <code>index.html</code>, Next.js면 <code>_document</code>·<code>layout</code>)에 한 줄을 넣고 다시 배포하면, 핀이 Figma 댓글처럼 화면 요소에 붙어 스크롤·화면 이동을 따라가요.</p>
          <div className="mt-2 flex items-center gap-2 rounded bg-gray-900 px-3 py-2 font-mono text-[11px] text-white">
            <code className="min-w-0 flex-1 break-all">{code}</code>
            <button type="button" onClick={copy} className="flex-none rounded bg-white/10 px-2 py-0.5 hover:bg-white/20">{copied ? '복사됨' : '복사'}</button>
          </div>
          <p className="mt-2 text-qa-text-sub">QAting 화면 안에서 열렸을 때만 동작하고, 일반 방문자에게는 아무것도 하지 않아요. 고른 요소의 위치·글자 앞부분만 QAting으로 보내고 입력값·쿠키는 보내지 않아요. 사이트의 CSP가 외부 스크립트를 막으면 이 주소를 <code>script-src</code>에 더해 주세요.</p>
        </div>
      )}
    </div>
  )
}

/** 주소의 경로·쿼리(연결 화면 입장 뒤 갈 곳) */
function pathOf(url: string): string {
  try {
    const u = new URL(url)
    return `${u.pathname}${u.search}`
  } catch {
    return '/'
  }
}
