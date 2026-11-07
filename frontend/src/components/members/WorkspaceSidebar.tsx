import { NavLink } from 'react-router-dom'
import { Icon } from '../common/Icon'

// 프로젝트 작업공간 왼쪽 메뉴(시안 WS-01 "작업 메뉴" — 240px, 항목 207×40, 아이콘 20, 활성 primary 10% 배경).
// 지금 만들어진 화면은 프로젝트 홈뿐이라 나머지는 시안 모양 그대로 두되 누를 수 없게 한다.
// 멤버 관리는 시안 메뉴에 없다 — 작업공간 홈의 "프로젝트 멤버" 카드 [멤버 관리]로 들어간다.
const ITEMS: { label: string; icon: string; path: string | null }[] = [
  { label: '프로젝트 홈', icon: 'home', path: '' },
  { label: '디자인 QA', icon: 'shield-check', path: null },
  { label: 'QA 결과', icon: 'list-checks', path: null },
  { label: 'QA Queue', icon: 'inbox', path: null },
  { label: '연결 관리', icon: 'link', path: null },
]

const ITEM_CLASS = 'flex h-10 w-[207px] items-center gap-3 rounded-lg px-3 text-sm'

export function WorkspaceSidebar({ projectId }: { projectId: string }) {
  return (
    <aside className="w-60 flex-none border-r border-qa-border bg-white px-4 pt-6">
      <div className="text-xs font-medium leading-4 text-qa-text-sub">작업 메뉴</div>
      <nav className="mt-4 flex flex-col gap-1">
        {ITEMS.map((item) =>
          item.path !== null ? (
            <NavLink
              key={item.label}
              to={`/app/projects/${projectId}${item.path ? `/${item.path}` : ''}`}
              end
              className={({ isActive }) =>
                `${ITEM_CLASS} ${isActive ? 'bg-qa-primary/10 font-medium text-qa-primary-hover' : 'text-qa-text-sub hover:bg-qa-surface'}`
              }
            >
              <Icon name={item.icon} className="h-5 w-5 flex-none" />
              {item.label}
            </NavLink>
          ) : (
            <span key={item.label} aria-disabled="true" title="준비 중인 화면이에요"
              className={`${ITEM_CLASS} cursor-default text-qa-text-sub`}>
              <Icon name={item.icon} className="h-5 w-5 flex-none" />
              {item.label}
            </span>
          ),
        )}
      </nav>
    </aside>
  )
}
