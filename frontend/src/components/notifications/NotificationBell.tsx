import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  listNotifications, markAllNotificationsRead, markNotificationRead, type AppNotification,
} from '../../lib/api'
import { Icon } from '../common/Icon'
import { KIND_STYLE, describe, whenText } from './notificationText'

// 헤더 종 아이콘 + 알림 패널(시안 NTF-00~05, 2026-11-11).
// 지금 프로젝트에서 내게 온 알림만 보여 준다(AC14). 실시간 푸시는 없어 30초마다, 그리고 창으로 돌아올 때 다시 읽는다.
// 알림을 누르면 읽음 처리하고 관련 화면으로 간다

const POLL_MS = 30_000

type Filter = 'all' | 'unread'
type Load = 'loading' | 'ready' | 'error'

export function NotificationBell({ projectId }: { projectId: string }) {
  const navigate = useNavigate()
  const [items, setItems] = useState<AppNotification[] | null>(null)
  const [load, setLoad] = useState<Load>('loading')
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState<Filter>('all')
  /** [모두 읽음] 직후 안내(시안 NTF-03) — 패널을 닫으면 지운다 */
  const [readAllCount, setReadAllCount] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)

  const refresh = useCallback(async () => {
    try {
      const inbox = await listNotifications(projectId)
      setItems(inbox.items)
      setLoad('ready')
    } catch {
      // 이미 보여 준 목록이 있으면 그대로 두고, 처음 읽기에 실패했을 때만 실패 화면(NTF-05)
      setLoad((prev) => (prev === 'ready' ? prev : 'error'))
    }
  }, [projectId])

  useEffect(() => {
    setItems(null)
    setLoad('loading')
    refresh()
    const timer = window.setInterval(refresh, POLL_MS)
    const onFocus = () => refresh()
    window.addEventListener('focus', onFocus)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', onFocus)
    }
  }, [refresh])

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) close()
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  function close() {
    setOpen(false)
    setReadAllCount(null)
  }

  function toggle() {
    if (open) {
      close()
      return
    }
    setOpen(true)
    refresh()
  }

  async function retry() {
    setLoad('loading')
    await refresh()
  }

  const unread = items?.filter((n) => !n.read_at).length ?? 0
  const shown = items?.filter((n) => filter === 'all' || !n.read_at) ?? []

  async function readAll() {
    if (unread === 0 || busy) return
    setBusy(true)
    try {
      const { updated } = await markAllNotificationsRead(projectId)
      const now = new Date().toISOString()
      setItems((prev) => prev?.map((n) => (n.read_at ? n : { ...n, read_at: now })) ?? prev)
      setReadAllCount(updated)
    } catch {
      // 읽음 상태는 서버 기준 — 실패하면 바꾸지 않는다
    } finally {
      setBusy(false)
    }
  }

  function openItem(n: AppNotification) {
    const view = describe(n, projectId)
    if (!n.read_at) {
      setItems((prev) => prev?.map((x) => (x.id === n.id ? { ...x, read_at: new Date().toISOString() } : x)) ?? prev)
      // 이동을 막지 않는다 — 실패하면 다음 새로고침에서 안읽음으로 돌아온다
      markNotificationRead(projectId, n.id).catch(() => undefined)
    }
    close()
    navigate(view.href)
  }

  return (
    <div className="relative" ref={boxRef}>
      <button
        type="button"
        aria-label={unread > 0 ? `알림 ${unread}건 읽지 않음` : '알림'}
        aria-expanded={open}
        onClick={toggle}
        className="relative flex h-10 w-10 items-center justify-center rounded-lg text-gray-900 transition-colors hover:bg-qa-surface"
      >
        <Icon name="bell" className="h-6 w-6" />
        {unread > 0 && <UnreadBadge count={unread} className="absolute right-0.5 top-0.5" />}
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="알림"
          className="absolute right-0 top-12 z-30 flex max-h-[min(680px,calc(100vh-88px))] w-[400px] max-w-[calc(100vw-32px)] flex-col overflow-hidden rounded-xl border border-qa-border bg-white shadow-[0_12px_32px_rgba(15,23,41,0.14)]"
        >
          <div className="flex items-center justify-between px-5 pb-3 pt-4">
            <div className="flex items-center gap-2">
              <h2 className="text-base font-bold leading-6 text-black">알림</h2>
              {unread > 0 && <UnreadBadge count={unread} />}
            </div>
            {load !== 'error' && (
              <button
                type="button"
                onClick={readAll}
                disabled={unread === 0 || busy}
                className="text-xs font-medium text-qa-primary hover:text-qa-primary-hover disabled:cursor-default disabled:text-qa-sub"
              >
                모두 읽음
              </button>
            )}
          </div>

          {load === 'error' ? (
            <StateBlock icon="wifi-off" title="알림을 불러오지 못했어요" body={'네트워크 연결을 확인한 뒤 다시 시도해 주세요.\n읽음 상태는 변경되지 않았어요.'}>
              <button type="button" onClick={retry} className="mt-4 rounded-lg border border-qa-border px-4 py-2 text-sm font-medium text-black hover:bg-qa-surface">
                다시 시도
              </button>
            </StateBlock>
          ) : load === 'loading' && !items ? (
            <StateBlock spinner title="알림을 불러오는 중이에요" />
          ) : (
            <>
              <div className="flex gap-2 border-b border-qa-border px-5 pb-3">
                <FilterChip label="전체" count={items?.length ?? 0} active={filter === 'all'} onClick={() => setFilter('all')} />
                <FilterChip label="읽지 않음" count={unread} active={filter === 'unread'} onClick={() => setFilter('unread')} />
              </div>
              {readAllCount != null && (
                <div className="flex items-center gap-1.5 border-b border-qa-border bg-qa-surface px-5 py-2.5 text-xs text-qa-text-sub">
                  <Icon name="check" className="h-3.5 w-3.5" />
                  {readAllCount > 0 ? `${readAllCount}개 알림을 모두 읽음 처리했어요` : '읽지 않은 알림이 없어요'}
                </div>
              )}
              {shown.length === 0 ? (
                filter === 'unread' && (items?.length ?? 0) > 0 ? (
                  <StateBlock icon="circle-check" title="읽지 않은 알림이 없어요" body={'새로운 소식이 생기면 여기에 표시돼요.\n지난 알림은 [전체]에서 다시 확인할 수 있어요.'} />
                ) : (
                  <StateBlock icon="bell" title="아직 받은 알림이 없어요" body={'QA 문제 등록, 담당 팀 배정, Re-QA 결과처럼\n확인이 필요한 소식이 생기면 여기에 표시돼요.'} />
                )
              ) : (
                <ul className="min-h-0 flex-1 overflow-y-auto">
                  {shown.map((n) => <Item key={n.id} n={n} projectId={projectId} onOpen={openItem} />)}
                </ul>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}

function Item({ n, projectId, onOpen }: { n: AppNotification; projectId: string; onOpen: (n: AppNotification) => void }) {
  const view = describe(n, projectId)
  const style = KIND_STYLE[view.kind]
  const isUnread = !n.read_at
  return (
    <li className="border-b border-qa-border last:border-b-0">
      <button
        type="button"
        onClick={() => onOpen(n)}
        className={`flex w-full items-start gap-3 px-5 py-4 text-left transition-colors ${isUnread ? 'bg-qa-primary/5 hover:bg-qa-primary/10' : 'bg-white hover:bg-qa-surface'}`}
      >
        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${style.box}`}>
          <Icon name={style.icon} className="h-5 w-5" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-1.5 text-xs leading-4">
            <span className={`font-medium ${style.text}`}>{view.label}</span>
            <span className="text-qa-sub">·</span>
            <time className="text-qa-sub" dateTime={n.created_at}>{whenText(n.created_at)}</time>
          </span>
          <span className={`mt-1 block text-sm leading-5 text-black ${isUnread ? 'font-medium' : 'font-normal'}`}>{view.title}</span>
          <span className="mt-1 line-clamp-2 block text-xs leading-4 text-qa-sub">{view.detail}</span>
        </span>
        <span className="flex h-5 w-2 shrink-0 items-center" aria-hidden>
          {isUnread && <span className="h-2 w-2 rounded-full bg-qa-primary" />}
        </span>
        {isUnread && <span className="sr-only">읽지 않음</span>}
      </button>
    </li>
  )
}

function UnreadBadge({ count, className = '' }: { count: number; className?: string }) {
  return (
    <span className={`flex h-[18px] min-w-[18px] items-center justify-center rounded-full border border-white bg-qa-warning px-1 text-[11px] font-medium leading-none text-white ${className}`}>
      {count > 99 ? '99+' : count}
    </span>
  )
}

function FilterChip({ label, count, active, onClick }: { label: string; count: number; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`flex h-7 items-center gap-1 rounded-full px-3 text-xs ${active ? 'bg-black text-white' : 'border border-qa-border bg-white text-black hover:bg-qa-surface'}`}
    >
      <span className="font-medium">{label}</span>
      <span className={active ? 'text-white' : 'text-qa-sub'}>{count}</span>
    </button>
  )
}

function StateBlock({ icon, spinner, title, body, children }: { icon?: string; spinner?: boolean; title: string; body?: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center px-6 py-12 text-center">
      {spinner ? (
        <span className="h-8 w-8 animate-spin rounded-full border-2 border-qa-border border-t-qa-primary" aria-hidden />
      ) : (
        <span className="flex h-10 w-10 items-center justify-center rounded-full bg-qa-surface text-qa-sub">
          <Icon name={icon ?? 'bell'} className="h-5 w-5" />
        </span>
      )}
      <p className="mt-3 text-sm font-medium text-black">{title}</p>
      {body && <p className="mt-1 whitespace-pre-line text-xs leading-5 text-qa-sub">{body}</p>}
      {children}
    </div>
  )
}
