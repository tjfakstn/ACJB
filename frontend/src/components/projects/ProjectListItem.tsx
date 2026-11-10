import { Link } from 'react-router-dom'
import type { ProjectSummary } from '../../lib/api'
import { Icon } from '../common/Icon'

// 프로젝트 목록 카드(시안 PRJ-01 Card/Project, 실패 상태는 PRJ-01A Card/Project · 비교 분석 미완료).
// QA 결과 n건=전체 이슈, FAIL n건=미해결 이슈(SPEC AC15). 카드를 누르면 작업공간 홈으로 간다.
// 시안의 실패 카드는 CON-06F(비교 분석 실패 화면)로 가지만 그 화면이 아직 없어 작업공간 홈으로 보낸다.

function connectionText(p: ProjectSummary): string {
  if (p.figma_connected && p.github_connected) return 'Figma · GitHub 연결됨'
  if (p.figma_connected) return 'Figma 연결됨 · GitHub 연결 전'
  if (p.github_connected) return 'GitHub 연결됨 · Figma 연결 전'
  return 'Figma · GitHub 연결 전'
}

/** "3일 전" 같은 상대 시각 — 시안 "마지막 분석: 3일 전" */
function timeAgo(iso: string, now = Date.now()): string {
  const diff = Math.max(0, now - new Date(iso).getTime())
  const min = Math.floor(diff / 60000)
  if (min < 1) return '방금 전'
  if (min < 60) return `${min}분 전`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour}시간 전`
  return `${Math.floor(hour / 24)}일 전`
}

export function ProjectListItem({ project }: { project: ProjectSummary }) {
  const failed = project.analysis_status === 'failed'
  return (
    <Link
      to={`/app/projects/${project.id}`}
      className={`flex min-h-[105px] flex-wrap items-center gap-x-6 gap-y-3 border-b px-6 py-5 transition-colors last:border-b-0 hover:bg-[#fafbfd] ${
        failed ? 'border border-qa-warning' : 'border-qa-border'}`}
    >
      {/* 좁은 화면에서는 배지·상태가 다음 줄로 내려간다 — 글자가 한 자씩 꺾이지 않게 이름 칸 최소 폭을 둔다 */}
      <div className="min-w-[200px] flex-1">
        <div className="truncate text-base font-bold leading-6 text-black">{project.name}</div>
        <div className="mt-1 truncate text-xs text-qa-text-sub">{connectionText(project)}</div>
      </div>

      <div className="flex flex-none items-center gap-2">
        {failed ? (
          <Badge tone="danger" text="비교 분석 미완료" redText />
        ) : (
          <>
            <Badge tone="info" text={`QA 결과 ${project.issue_count}건`} />
            <Badge tone={project.open_issue_count > 0 ? 'danger' : 'ok'} text={`FAIL ${project.open_issue_count}건`} />
          </>
        )}
      </div>

      <div className="flex w-40 flex-none flex-col items-end gap-1">
        <AnalysisState project={project} />
        <div className="flex">
          {project.members_preview.map((m, i) => (
            <span key={m.id} title={m.name}
              className={`flex h-6 w-6 items-center justify-center rounded-full border-2 border-white bg-[#e8e8e8] text-[10px] font-semibold text-qa-text-sub ${i > 0 ? '-ml-1.5' : ''}`}>
              {m.name.slice(0, 1)}
            </span>
          ))}
        </div>
      </div>

      <Icon name="chevron-right" className="h-5 w-5 flex-none text-black" />
    </Link>
  )
}

function AnalysisState({ project }: { project: ProjectSummary }) {
  const [state, sub, danger] = (() => {
    switch (project.analysis_status) {
      case 'succeeded':
        return ['분석 완료', project.last_analyzed_at ? `마지막 분석: ${timeAgo(project.last_analyzed_at)}` : '', false]
      case 'failed':
        return ['비교 분석 실패', '연결 정보 유지됨', true]
      case 'queued':
      case 'running':
        return ['분석 중', '비교 분석을 진행하고 있어요', false]
      default:
        return ['분석 전', project.figma_connected && project.github_connected ? '비교 분석을 시작해 주세요' : '연결 후 분석할 수 있어요', false]
    }
  })()
  return (
    <>
      <div className={`text-xs font-medium ${danger ? 'text-qa-warning' : 'text-black'}`}>{state}</div>
      {sub && <div className="text-xs text-qa-text-sub">{sub}</div>}
    </>
  )
}

function Badge({ tone, text, redText }: { tone: 'info' | 'danger' | 'ok'; text: string; redText?: boolean }) {
  const style = {
    info: { bg: 'bg-qa-primary/10', dot: 'bg-qa-primary', text: 'text-black' },
    danger: { bg: 'bg-qa-warning/10', dot: 'bg-qa-warning', text: 'text-black' },
    ok: { bg: 'bg-qa-success/10', dot: 'bg-qa-success', text: 'text-black' },
  }[tone]
  return (
    <span className={`inline-flex h-6 items-center gap-1.5 whitespace-nowrap rounded-full px-2 text-xs font-medium ${style.bg} ${redText ? 'text-qa-warning' : style.text}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${style.dot}`} />{text}
    </span>
  )
}
