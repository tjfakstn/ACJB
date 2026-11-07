// 목록 검색 툴바.
// 시안의 상태 필터(FAIL/이상 없음)와 정렬은 뺐다 — 프로젝트별 QA 집계를 주는 API가 아직 없고,
// 목록 순서는 서버가 name asc, id asc로 고정해 돌려준다(SPEC AC15). 검색은 받은 목록에서 이름으로만 거른다.
interface ProjectToolbarProps {
  query: string
  onQueryChange: (value: string) => void
}

export function ProjectToolbar({ query, onQueryChange }: ProjectToolbarProps) {
  return (
    <div className="mb-5 flex gap-3">
      <div className="relative w-[280px]">
        <svg
          className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-qa-sub"
          width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"
        >
          <circle cx="11" cy="11" r="7" />
          <path d="m21 21-4.3-4.3" />
        </svg>
        <input
          type="search"
          value={query}
          onChange={(e) => onQueryChange(e.target.value)}
          placeholder="프로젝트명 입력"
          aria-label="프로젝트 검색"
          className="h-10 w-full rounded-lg border border-qa-border bg-white pl-10 pr-3.5 text-sm text-gray-900 outline-none placeholder:text-qa-sub focus:border-qa-primary"
        />
      </div>
    </div>
  )
}
