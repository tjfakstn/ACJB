// 시안에 쓰인 아이콘(Lucide 계열 선 아이콘)만 모아 둔다. 굵기·끝처리는 시안과 같은 2px·round.
const PATHS: Record<string, React.ReactNode> = {
  'alert': <><circle cx="12" cy="12" r="9" /><path d="M12 8v4M12 16h.01" /></>,
  'alert-circle': <><circle cx="12" cy="12" r="9" /><path d="M12 8v4M12 16h.01" /></>,
  'link': <><path d="M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.5 1.5" /><path d="M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.5-1.5" /></>,
  'clock': <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  'circle-check': <><circle cx="12" cy="12" r="9" /><path d="m8.5 12 2.5 2.5 4.5-5" /></>,
  'check': <path d="m5 12 5 5 9-10" />,
  'x': <path d="M6 6l12 12M18 6 6 18" />,
  'plus': <path d="M12 5v14M5 12h14" />,
  'user-plus': <><circle cx="9" cy="8" r="4" /><path d="M3 21v-1a6 6 0 0 1 12 0v1M19 8v6M16 11h6" /></>,
  'search': <><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></>,
  'chevron-down': <path d="m6 9 6 6 6-6" />,
  'chevron-right': <path d="m9 6 6 6-6 6" />,
  'trash': <><path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3" /></>,
  'github': <path d="M9 19c-4.5 1.4-4.5-2.5-6-3m12 5v-3.5c0-1 .1-1.4-.5-2 2.8-.3 5.5-1.4 5.5-6a4.6 4.6 0 0 0-1.3-3.2 4.2 4.2 0 0 0-.1-3.2s-1.1-.3-3.5 1.3a12.3 12.3 0 0 0-6.2 0C6.5 2.8 5.4 3.1 5.4 3.1a4.2 4.2 0 0 0-.1 3.2A4.6 4.6 0 0 0 4 9.5c0 4.6 2.7 5.7 5.5 6-.6.6-.6 1.2-.5 2V21" />,
  'figma': <><path d="M5 5.5A3.5 3.5 0 0 1 8.5 2H12v7H8.5A3.5 3.5 0 0 1 5 5.5zM12 2h3.5a3.5 3.5 0 1 1 0 7H12zM12 12.5a3.5 3.5 0 1 1 7 0 3.5 3.5 0 1 1-7 0zM5 19.5A3.5 3.5 0 0 1 8.5 16H12v3.5a3.5 3.5 0 1 1-7 0zM5 12.5A3.5 3.5 0 0 1 8.5 9H12v7H8.5A3.5 3.5 0 0 1 5 12.5z" /></>,
  'folder-plus': <><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /><path d="M12 10v6M9 13h6" /></>,
}

export function Icon({ name, className }: { name: keyof typeof PATHS | string; className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {PATHS[name]}
    </svg>
  )
}
