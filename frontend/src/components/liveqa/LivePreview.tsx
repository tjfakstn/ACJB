import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { captureImageUrl, type DeployTarget, type QaCapture, type QaIssue, manualQaId } from '../../lib/api'
import { Icon } from '../common/Icon'
import { LIVE_VIEWPORT } from './draft'

// 위쪽 배포 화면(시안 W-01 Live Preview, M-01~M-03·M-09B·C).
//  - 사이트는 1280×720 기준 iframe으로 띄우고 패널 너비에 맞춰 축소한다. 좌표는 언제나 1280×720 기준 픽셀이다
//  - [QA 등록]을 누르면 투명한 레이어가 클릭을 받는다 — 그동안 사이트의 버튼은 눌리지 않는다(M-02)
//  - 교차 출처 iframe은 경로·스크롤을 읽을 수 없다. 경로는 주소창에서 사람이 바꾼 값이고, 핀은 그 경로·첫 화면 기준이다
//  - 서버가 화면 삽입이 막혔다고 알려 주면(embeddable false) 사이트 대신 제한 안내와 [스크린샷 첨부]를 보여 준다(M-09B)
export interface PreviewSurface {
  /** live: 배포 화면 iframe, image: 첨부 이미지 위에서 위치 지정(M-09C) */
  kind: 'live' | 'image'
  attachment: QaCapture | null
}

export function LivePreview({
  projectId, target, path, onPathChange, reloadKey, onReload, surface, registerMode, onToggleRegister, onPick, pins, draftPin,
  selectedId, onSelectPin, onAttachForImage, attachBusy, attachError, isOwner, onEditUrl, runLabel, children,
}: {
  projectId: string
  target: DeployTarget
  path: string
  onPathChange: (path: string) => void
  reloadKey: number
  onReload: () => void
  surface: PreviewSurface
  registerMode: boolean
  onToggleRegister: () => void
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

  const blocked = target.embeddable === false
  const image = surface.kind === 'image' ? surface.attachment : null
  const base = { width: image?.width ?? LIVE_VIEWPORT.width, height: image?.height ?? LIVE_VIEWPORT.height }
  const scale = width > 0 ? width / base.width : 0
  // 첨부 이미지가 아주 길면(전체 페이지 스크린샷) 캔버스 높이를 기준 화면의 1.6배까지만 두고 안에서 스크롤한다
  const maxHeight = (LIVE_VIEWPORT.height / LIVE_VIEWPORT.width) * width * 1.6
  const canvasHeight = Math.min(base.height * scale, maxHeight)
  const pageUrl = target.url ? joinUrl(target.url, path) : null
  const canPick = surface.kind === 'image' ? !!image : !blocked && !!target.url

  // 레이어는 축소된 캔버스와 같은 크기라 화면 좌표를 축척으로 나누면 기준 크기 좌표가 된다(스크롤해도 rect가 함께 움직인다)
  const toLocal = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect()
    const x = Math.floor((e.clientX - rect.left) / scale)
    const y = Math.floor((e.clientY - rect.top) / scale)
    return { x: Math.max(0, Math.min(base.width - 1, x)), y: Math.max(0, Math.min(base.height - 1, y)) }
  }

  const status = blocked
    ? { tone: 'bg-qa-warning/10 text-[#c8161a]', dot: 'bg-qa-warning', text: '화면 삽입 제한 · 실시간 표시 불가' }
    : !target.url
      ? { tone: 'bg-qa-surface text-qa-text-sub', dot: 'bg-qa-sub', text: '배포 주소 없음' }
      : target.embeddable === null
        // 서버가 주소에 연결하지 못했다 — 화면은 그대로 띄우되 정상처럼 보이지 않게 알린다(M-09B "정상 표시처럼 보이지 않게")
        ? { tone: 'bg-[#fff4e5] text-[#a85a00]', dot: 'bg-[#f59e0b]', text: '연결 확인 안 됨' }
        : { tone: 'bg-qa-success/10 text-qa-success', dot: 'bg-qa-success', text: 'Live Preview · 현재 배포 화면' }

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
        <button type="button" onClick={onToggleRegister} disabled={!canPick} aria-pressed={registerMode}
          title={canPick ? '배포 화면에서 문제 위치를 찍어 QA를 등록해요' : '배포 화면이나 첨부 이미지가 있어야 위치를 찍을 수 있어요'}
          className={`inline-flex h-8 flex-none items-center gap-1 rounded-lg border px-3 text-xs font-medium disabled:cursor-not-allowed disabled:opacity-40 ${
            registerMode ? 'border-[#7c3aed] bg-[#7c3aed] text-white' : 'border-qa-primary bg-white text-qa-primary-hover hover:bg-qa-primary/5'}`}>
          <Icon name="map-pin" className="h-3.5 w-3.5" />{registerMode ? '등록 모드 종료' : 'QA 등록'}
        </button>
      </div>

      {target.url && target.embeddable === null && surface.kind === 'live' && (
        <div className="flex items-center gap-2 bg-[#fff4e5] px-4 py-1.5 text-xs text-[#a85a00]" role="status">
          <Icon name="alert-triangle" className="h-3.5 w-3.5" />
          {target.embed_blocked_reason ?? '서버가 배포 주소에 연결하지 못했어요.'} 화면이 비어 보이면 주소를 확인해 주세요. 위치를 찍으면 캡처는 실패할 수 있어요.
        </div>
      )}
      {registerMode && (
        <div className="flex items-center gap-2 bg-[#7c3aed] px-4 py-1.5 text-xs text-white" role="status">
          <Icon name="map-pin" className="h-3.5 w-3.5" />
          QA 등록 모드 · 문제가 있는 위치를 클릭하세요. 이 동안 사이트의 버튼은 눌리지 않아요 · Esc로 종료
        </div>
      )}

      {/* 캔버스 */}
      <div ref={wrapRef} className="relative w-full overflow-auto bg-qa-surface" style={{ height: (surface.kind === 'image' ? image : target.url && !blocked) && canvasHeight > 0 ? canvasHeight : 'auto' }}>
        {surface.kind === 'image' && image ? (
          <div className="relative" style={{ width: base.width * scale, height: base.height * scale }}>
            <img src={captureImageUrl(projectId, image.id)} alt="첨부한 화면 이미지" className="block h-full w-full select-none" draggable={false} />
            <div className="pointer-events-none absolute left-3 top-3 rounded-full bg-gray-900/80 px-2.5 py-1 text-xs text-white">첨부 이미지 기준 · 실시간 화면과 동기화되지 않아요</div>
            <Overlay {...{ registerMode, scale, base, cursor, setCursor, toLocal, onPick, pins, draftPin, selectedId, onSelectPin, basis: 'capture_image', attachmentId: image.id, path }} />
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
            <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/webp" className="hidden"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) onAttachForImage(f); e.target.value = '' }} />
          </div>
        ) : target.url && scale > 0 ? (
          <div className="relative" style={{ width: base.width * scale, height: base.height * scale }}>
            <iframe key={`${reloadKey}:${pageUrl}`} src={pageUrl ?? undefined} title="배포 화면"
              referrerPolicy="no-referrer" sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
              className="absolute left-0 top-0 origin-top-left border-0 bg-white"
              style={{ width: base.width, height: base.height, transform: `scale(${scale})` }} />
            <Overlay {...{ registerMode, scale, base, cursor, setCursor, toLocal, onPick, pins, draftPin, selectedId, onSelectPin, basis: 'live_overlay', attachmentId: null, path }} />
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

function Overlay({ registerMode, scale, base, cursor, setCursor, toLocal, onPick, pins, draftPin, selectedId, onSelectPin, basis, attachmentId, path }: {
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
  basis: 'live_overlay' | 'capture_image'
  attachmentId: string | null
  path: string
}) {
  // 이 화면(경로·첨부 이미지)에 찍힌 핀만 보여 준다
  const visible = pins.filter((i) => {
    const m = i.manual_qa!
    return m.location_basis === basis && (basis === 'capture_image' ? m.capture_id === attachmentId : m.page_path === path)
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
            style={{ left: m.anchor.x_ratio * base.width * scale, top: m.anchor.y_ratio * base.height * scale }}>
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
