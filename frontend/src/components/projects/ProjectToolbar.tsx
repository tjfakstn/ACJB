import { Icon } from '../common/Icon'

// 목록 툴바(시안 PRJ-01 Toolbar) — 검색 280px, 상태·정렬 160px.
// 목록은 서버가 name asc로 주고 걸러내기·정렬은 화면에서 한다(decision_log 2026-11-07 "프로젝트 만들기·목록을 시안 PRJ에 맞춤").
export type StatusFilter = 'all' | 'fail' | 'ok' | 'incomplete'
export type SortKey = 'name' | 'recent' | 'fail'

const STATUS_FILTER_LABELS: Record<StatusFilter, string> = {
  all: '전체',
  fail: 'FAIL 이슈 보유',
  ok: '이상 없음',
  incomplete: '분석 미완료',
}

const SORT_LABELS: Record<SortKey, string> = {
  name: '이름순',
  recent: '최근 분석순',
  fail: 'FAIL 많은 순',
}

interface ProjectToolbarProps {
  query: string
  onQueryChange: (value: string) => void
  status: StatusFilter
  onStatusChange: (value: StatusFilter) => void
  sort: SortKey
  onSortChange: (value: SortKey) => void
}

export function ProjectToolbar({ query, onQueryChange, status, onStatusChange, sort, onSortChange }: ProjectToolbarProps) {
  return (
    <div className="flex gap-3">
      <div className="relative w-[280px]">
        <Icon name="search" className="pointer-events-none absolute left-[13px] top-1/2 h-4 w-4 -translate-y-1/2 text-black" />
        <input
          type="search"
          value={query}
          onChange={(e) => onQueryChange(e.target.value)}
          placeholder="프로젝트명 입력"
          aria-label="프로젝트 검색"
          className="h-10 w-full rounded-lg border border-qa-sub bg-white pl-9 pr-3 text-sm text-black outline-none placeholder:text-qa-sub focus:border-qa-primary"
        />
      </div>
      <Select label="상태" value={status} onChange={(v) => onStatusChange(v as StatusFilter)} options={STATUS_FILTER_LABELS} placeholderFor="all" />
      <Select label="정렬" value={sort} onChange={(v) => onSortChange(v as SortKey)} options={SORT_LABELS} placeholderFor="name" />
    </div>
  )
}

/** 시안처럼 기본값일 때는 회색 "상태"·"정렬" 글자를 보여준다 */
function Select({ label, value, onChange, options, placeholderFor }: {
  label: string; value: string; onChange: (v: string) => void; options: Record<string, string>; placeholderFor: string
}) {
  const isDefault = value === placeholderFor
  return (
    <div className="relative w-40">
      <select
        aria-label={label}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={`h-10 w-full appearance-none rounded-lg border border-qa-sub bg-white pl-[13px] pr-9 text-sm outline-none focus:border-qa-primary ${isDefault ? 'text-transparent' : 'text-black'}`}
      >
        {Object.entries(options).map(([k, v]) => <option key={k} value={k} className="text-black">{v}</option>)}
      </select>
      {isDefault && <span className="pointer-events-none absolute left-[13px] top-1/2 -translate-y-1/2 text-sm text-qa-sub">{label}</span>}
      <Icon name="chevron-down" className="pointer-events-none absolute right-[13px] top-1/2 h-4 w-4 -translate-y-1/2 text-black" />
    </div>
  )
}
