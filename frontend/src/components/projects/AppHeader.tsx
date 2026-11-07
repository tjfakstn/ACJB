import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchMe, logoutUrl, type Me } from '../../lib/api'

// 로그인 이후 공통 상단 헤더 (로고 + 알림/계정).
// 계정 버튼은 이름·이메일과 로그아웃만 보여준다 — 알림 목록(AC14)은 시안이 정해지면 붙인다.
export function AppHeader() {
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
    <header className="sticky top-0 z-20 flex h-16 items-center justify-between border-b border-qa-border bg-white px-12">
      <Link to="/app/projects" className="text-[22px] font-extrabold tracking-tight text-qa-logo">
        QAting
      </Link>
      <div className="flex items-center gap-1">
        <button
          type="button"
          aria-label="알림"
          className="flex h-10 w-10 items-center justify-center rounded-full text-gray-600 transition-colors hover:bg-gray-100"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
            <path d="M13.73 21a2 2 0 0 1-3.46 0" />
          </svg>
        </button>
        <div className="relative" ref={menuRef}>
          <button
            type="button"
            aria-label="내 계정"
            aria-expanded={open}
            onClick={() => setOpen((v) => !v)}
            className="flex h-10 w-10 items-center justify-center overflow-hidden rounded-full text-gray-600 transition-colors hover:bg-gray-100"
          >
            {me?.picture ? (
              <img src={me.picture} alt="" className="h-8 w-8 rounded-full" referrerPolicy="no-referrer" />
            ) : (
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="9" />
                <circle cx="12" cy="10" r="3.2" />
                <path d="M6.2 18.6a6 6 0 0 1 11.6 0" />
              </svg>
            )}
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
