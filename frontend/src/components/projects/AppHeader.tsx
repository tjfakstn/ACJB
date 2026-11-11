import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { fetchMe, logoutUrl, type Me } from '../../lib/api'
import { Icon } from '../common/Icon'
import { NotificationBell } from '../notifications/NotificationBell'

// 로그인 이후 공통 상단 헤더 (로고 + 알림/계정).
// 알림(AC14, 시안 NTF)은 프로젝트 안에서만 — 알림이 프로젝트별이라 프로젝트 목록 화면에는 종 아이콘을 두지 않는다.
// 계정 버튼은 이름·이메일과 로그아웃만 보여준다.
export function AppHeader() {
  const { projectId } = useParams()
  const [me, setMe] = useState<Me | null>(null)
  const [open, setOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    fetchMe().then(setMe)
  }, [])

  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  return (
    <header className="sticky top-0 z-20 flex h-16 items-center justify-between border-b border-qa-border bg-white px-8">
      <Link to="/app/projects" className="text-xl font-bold leading-7 text-qa-logo">
        QAting
      </Link>
      <div className="flex items-center gap-2">
        {projectId && <NotificationBell projectId={projectId} />}
        <div className="relative" ref={menuRef}>
          <button
            type="button"
            aria-label="내 계정"
            aria-expanded={open}
            onClick={() => setOpen((v) => !v)}
            className="flex h-10 w-10 items-center justify-center overflow-hidden rounded-lg text-gray-900 transition-colors hover:bg-qa-surface"
          >
            {/* 시안 Header는 프로필 사진 대신 user 아이콘(24)을 쓴다 */}
            <Icon name="circle-user" className="h-6 w-6" />
          </button>
          {open && (
            <div className="absolute right-0 top-12 w-60 rounded-xl border border-qa-border bg-white p-2 shadow-[0_8px_24px_rgba(15,23,41,0.12)]">
              <div className="px-3 py-2">
                <div className="truncate text-sm font-semibold text-gray-900">{me?.name ?? '-'}</div>
                <div className="truncate text-xs text-qa-sub">{me?.email ?? ''}</div>
              </div>
              <a href={logoutUrl()} className="block rounded-lg px-3 py-2 text-sm text-gray-700 hover:bg-gray-50">
                로그아웃
              </a>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}
