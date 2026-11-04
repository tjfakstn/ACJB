// 목록 검색 + 필터 툴바.
// NOTE: 지금은 레이아웃/표시만 — 실제 검색·필터·정렬은 목록 API(미정)와 함께 붙인다.
// AGENTS.md 규칙: 목록 반환 API는 정렬 기준을 명시적으로 고정(created_at desc 등)해야 하므로,
// 정렬 옵션을 서버에 넘길 때 그 기준을 함께 고정할 것.
export function ProjectToolbar() {
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
          type="text"
          placeholder="프로젝트명 입력"
          className="h-10 w-full rounded-lg border border-qa-border bg-white pl-10 pr-3.5 text-sm text-gray-900 outline-none placeholder:text-qa-sub focus:border-qa-primary"
        />
      </div>
      <select
        aria-label="상태 필터"
        className="h-10 w-40 rounded-lg border border-qa-border bg-white px-3.5 text-sm text-gray-600 outline-none focus:border-qa-primary"
        defaultValue=""
      >
        <option value="">상태</option>
        <option value="pass">이상 없음</option>
        <option value="fail">FAIL 이슈 보유</option>
      </select>
      <select
        aria-label="정렬"
        className="h-10 w-40 rounded-lg border border-qa-border bg-white px-3.5 text-sm text-gray-600 outline-none focus:border-qa-primary"
        defaultValue=""
      >
        <option value="">정렬</option>
        <option value="recent">최신순</option>
        <option value="name">이름순</option>
      </select>
    </div>
  )
}
