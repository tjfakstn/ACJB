// 로그인 이후 공통 상단 헤더 (로고 + 알림/계정).
// WHY: 02 화면군이 공유하는 헤더라 별도 컴포넌트로 분리 — 이후 다른 로그인 후 화면에서도 재사용.
export function AppHeader() {
  return (
    <header className="sticky top-0 z-20 flex h-16 items-center justify-between border-b border-qa-border bg-white px-12">
      <span className="text-[22px] font-extrabold tracking-tight text-qa-logo">QAting</span>
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
        <button
          type="button"
          aria-label="내 계정"
          className="flex h-10 w-10 items-center justify-center rounded-full text-gray-600 transition-colors hover:bg-gray-100"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="9" />
            <circle cx="12" cy="10" r="3.2" />
            <path d="M6.2 18.6a6 6 0 0 1 11.6 0" />
          </svg>
        </button>
      </div>
    </header>
  )
}
