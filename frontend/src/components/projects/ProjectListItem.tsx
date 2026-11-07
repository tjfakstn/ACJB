import { Link } from 'react-router-dom'
import type { Project } from '../../lib/api'

// 프로젝트 목록의 카드 한 줄(시안 U01-03 P-01).
// 시안의 QA 결과·FAIL 건수·마지막 분석 시각은 내려주는 API가 없어 빼고, 내가 소유자인지만 표시한다.
// 작업공간(U04-01)이 아직 없어 카드를 누르면 멤버 관리로 간다.
interface ProjectListItemProps {
  project: Project
  isOwner: boolean
}

export function ProjectListItem({ project, isOwner }: ProjectListItemProps) {
  return (
    <Link
      to={`/app/projects/${project.id}/members`}
      className="flex h-[88px] items-center gap-5 border-b border-[#eef1f5] px-7 transition-colors last:border-b-0 hover:bg-[#fafbfd]"
    >
      <div className="min-w-0 flex-1">
        <div className="truncate text-base font-bold leading-6 text-gray-900">{project.name}</div>
        <div className="mt-1 text-[13px] text-qa-sub">{isOwner ? '내가 만든 프로젝트' : '참여 중인 프로젝트'}</div>
      </div>
      {isOwner && (
        <span className="inline-flex h-7 items-center rounded-full bg-[#eef4ff] px-3 text-xs font-semibold text-qa-primary">
          소유자
        </span>
      )}
      <svg className="text-gray-400" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="m9 6 6 6-6 6" />
      </svg>
    </Link>
  )
}
