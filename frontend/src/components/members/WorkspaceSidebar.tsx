import { NavLink } from 'react-router-dom'

// 프로젝트 작업공간 왼쪽 메뉴(시안 U02-C1·U04-01의 "작업 메뉴").
// 지금 만들어진 화면은 멤버 관리뿐이라 나머지는 비활성으로 둔다 — QA 흐름은 시안과 스펙을 맞춘 뒤 붙인다.
const ITEMS = [
  { label: '프로젝트 홈', path: null },
  { label: 'QA 이슈', path: null },
  { label: '스타일 차이', path: null },
  { label: '멤버 관리', path: 'members' },
]

export function WorkspaceSidebar({ projectId }: { projectId: string }) {
  return (
    <aside className="w-[220px] flex-none border-r border-qa-border bg-white px-4 py-6">
      <div className="px-3 text-xs font-medium text-qa-sub">작업 메뉴</div>
      <nav className="mt-3 flex flex-col gap-1">
        {ITEMS.map((item) =>
          item.path ? (
            <NavLink
              key={item.label}
              to={`/app/projects/${projectId}/${item.path}`}
              className={({ isActive }) =>
                `rounded-lg px-3 py-2.5 text-sm ${isActive ? 'bg-[#eef4ff] font-semibold text-qa-primary' : 'text-gray-700 hover:bg-gray-50'}`
              }
            >
              {item.label}
            </NavLink>
          ) : (
            <span key={item.label} aria-disabled="true" title="준비 중"
              className="flex items-center justify-between rounded-lg px-3 py-2.5 text-sm text-gray-400">
              {item.label}
              <span className="text-[10px]">준비 중</span>
            </span>
          ),
        )}
      </nav>
    </aside>
  )
}
