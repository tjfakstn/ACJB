import type { Project } from './types'

// 02-1 프로젝트 목록의 카드 한 줄.
interface ProjectListItemProps {
  project: Project
}

export function ProjectListItem({ project }: ProjectListItemProps) {
  const failZero = project.failCount === 0
  return (
    <div className="flex h-[105px] cursor-pointer items-center gap-5 border-b border-[#eef1f5] px-7 transition-colors last:border-b-0 hover:bg-[#fafbfd]">
      <div className="min-w-[220px]">
        <div className="text-base font-bold leading-6 text-gray-900">{project.name}</div>
        <div className="mt-1 text-[13px] text-qa-sub">Figma · GitHub 연결됨</div>
      </div>

      <div className="flex flex-1 justify-center gap-2.5">
        <span className="inline-flex h-7 items-center gap-1.5 rounded-full bg-gray-100 px-3 text-xs font-semibold text-gray-600">
          <span className="h-1.5 w-1.5 rounded-full bg-qa-primary" />
          QA 결과 {project.qaCount}건
        </span>
        <span
          className={`inline-flex h-7 items-center gap-1.5 rounded-full px-3 text-xs font-semibold ${
            failZero ? 'bg-gray-100 text-gray-600' : 'bg-[#fdecec] text-qa-warning'
          }`}
        >
          <span className={`h-1.5 w-1.5 rounded-full ${failZero ? 'bg-gray-300' : 'bg-qa-warning'}`} />
          FAIL {project.failCount}건
        </span>
      </div>

      <div className="flex min-w-[230px] items-center justify-end gap-[18px]">
        <div className="text-right">
          <div className="text-[13px] font-bold text-qa-success">분석 완료</div>
          <div className="mt-1 text-xs text-qa-sub">마지막 분석: {project.lastAnalyzedLabel}</div>
        </div>
        <div className="flex">
          <span className="h-[26px] w-[26px] rounded-full border-2 border-white bg-gray-300" />
          <span className="-ml-2 h-[26px] w-[26px] rounded-full border-2 border-white bg-gray-300" />
          <span className="-ml-2 h-[26px] w-[26px] rounded-full border-2 border-white bg-gray-300" />
        </div>
        <svg className="text-gray-400" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="m9 6 6 6-6 6" />
        </svg>
      </div>
    </div>
  )
}
