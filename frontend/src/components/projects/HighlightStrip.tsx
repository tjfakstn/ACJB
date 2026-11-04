// 목록 상단 하이라이트 스트립 — 전체 프로젝트 수 + FAIL/이상없음 분포.
// WHY: 분포 바는 정렬/기준이 흔들리면 안 되므로, failCount>0 을 'FAIL 이슈 보유'로 고정 집계한다.
interface HighlightStripProps {
  total: number
  failProjects: number
  passProjects: number
}

export function HighlightStrip({ total, failProjects, passProjects }: HighlightStripProps) {
  const failPct = total > 0 ? (failProjects / total) * 100 : 0
  return (
    <section className="mb-6 flex items-center overflow-hidden rounded-2xl border border-[#dce6f5] bg-gradient-to-r from-[#eef4ff] via-[#f4f9ff] to-white px-7 py-5">
      <div className="flex min-w-[88px] flex-col gap-1">
        <span className="text-xs font-medium text-gray-600">전체 프로젝트</span>
        <span className="text-[28px] font-extrabold leading-9 tracking-tight text-gray-900">{total}</span>
        <span className="text-xs text-qa-sub">모두 분석 완료</span>
      </div>

      <div className="mx-8 h-[76px] w-px bg-[#d4deec]" />

      <div className="flex-1">
        <div className="flex h-2 gap-0.5 overflow-hidden rounded-full bg-[#e6ecf4]">
          <span className="h-full rounded-full bg-qa-warning" style={{ width: `${failPct}%` }} />
          <span className="h-full rounded-full bg-qa-success" style={{ width: `${100 - failPct}%` }} />
        </div>
        <div className="mt-5 flex gap-6 text-xs font-medium text-gray-600">
          <span className="flex items-center gap-2">
            <span className="h-2 w-2 rounded-full bg-qa-warning" />
            FAIL 이슈 보유 <b className="font-bold text-gray-900">{failProjects}</b>
          </span>
          <span className="flex items-center gap-2">
            <span className="h-2 w-2 rounded-full bg-qa-success" />
            이상 없음 <b className="font-bold text-gray-900">{passProjects}</b>
          </span>
        </div>
      </div>
    </section>
  )
}
