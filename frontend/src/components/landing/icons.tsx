// 랜딩 페이지 아이콘 모음.
// Figma 디자인의 SVG 에셋 원본은 외부에서 받을 수 없어(에셋 URL 차단) 같은 형태로 재구성했다.
// nav/feature 아이콘은 lucide 계열 스트로크 아이콘, CheckCircle/Dot은 디자인의 상태 색상에 맞춘 커스텀.
// 색상 토큰: success #1E8782 / warning #FC1B1F / primary #007FFF / sub #9B9EAF / border #CBD5E1

type SvgProps = { className?: string; size?: number }

function Stroke({ size = 20, className, children }: SvgProps & { children: React.ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      {children}
    </svg>
  )
}

export function IconHome(p: SvgProps) {
  return (
    <Stroke {...p}>
      <path d="M3 9.5 12 3l9 6.5" />
      <path d="M5 9.2V20a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9.2" />
      <path d="M9 21v-6h6v6" />
    </Stroke>
  )
}

export function IconGitCompare(p: SvgProps) {
  return (
    <Stroke {...p}>
      <circle cx="6" cy="6" r="3" />
      <circle cx="18" cy="18" r="3" />
      <path d="M13 6h3a2 2 0 0 1 2 2v7" />
      <path d="M11 18H8a2 2 0 0 1-2-2V9" />
    </Stroke>
  )
}

export function IconShieldCheck(p: SvgProps) {
  return (
    <Stroke {...p}>
      <path d="M12 3 5 6v5c0 4 3 7 7 8 4-1 7-4 7-8V6z" />
      <path d="m9 12 2 2 4-4" />
    </Stroke>
  )
}

export function IconListChecks(p: SvgProps) {
  return (
    <Stroke {...p}>
      <path d="m3 7 2 2 4-4" />
      <path d="m3 17 2 2 4-4" />
      <path d="M13 6h8" />
      <path d="M13 12h8" />
      <path d="M13 18h8" />
    </Stroke>
  )
}

export function IconInbox(p: SvgProps) {
  return (
    <Stroke {...p}>
      <path d="M22 12h-6l-2 3h-4l-2-3H2" />
      <path d="M5.5 5.1 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.8 4H7.2a2 2 0 0 0-1.7 1.1z" />
    </Stroke>
  )
}

export function IconLink(p: SvgProps) {
  return (
    <Stroke {...p}>
      <path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7" />
      <path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" />
    </Stroke>
  )
}

export function IconColumns(p: SvgProps) {
  return (
    <Stroke {...p}>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M12 4v16" />
    </Stroke>
  )
}

export function IconChevronRight(p: SvgProps) {
  return (
    <Stroke {...p}>
      <path d="m9 18 6-6-6-6" />
    </Stroke>
  )
}

type CheckState = 'checked' | 'error' | 'unchecked' | 'current'

// 원형 상태 아이콘. PASS/완료=teal 체크, FAIL=red x, 대기=빈 회색 원, 진행 중=파란 링+점.
export function CheckCircle({ state = 'checked', size = 20, className }: { state?: CheckState; size?: number; className?: string }) {
  const common = { width: size, height: size, viewBox: '0 0 24 24', 'aria-hidden': true, className }
  if (state === 'checked') {
    return (
      <svg {...common}>
        <circle cx="12" cy="12" r="10" fill="#1E8782" />
        <path d="m8 12 2.5 2.5L16 9" fill="none" stroke="#fff" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  if (state === 'error') {
    return (
      <svg {...common}>
        <circle cx="12" cy="12" r="10" fill="#FC1B1F" />
        <path d="m9 9 6 6M15 9l-6 6" fill="none" stroke="#fff" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  if (state === 'current') {
    return (
      <svg {...common}>
        <circle cx="12" cy="12" r="9" fill="none" stroke="#007FFF" strokeWidth={2} />
        <circle cx="12" cy="12" r="3.5" fill="#007FFF" />
      </svg>
    )
  }
  // unchecked
  return (
    <svg {...common}>
      <circle cx="12" cy="12" r="9" fill="none" stroke="#CBD5E1" strokeWidth={2} />
    </svg>
  )
}

export function Dot({ color, size = 6 }: { color: string; size?: number }) {
  return (
    <span
      className="inline-block shrink-0 rounded-full"
      style={{ width: size, height: size, backgroundColor: color }}
      aria-hidden="true"
    />
  )
}
