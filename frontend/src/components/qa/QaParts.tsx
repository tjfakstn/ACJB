import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  ApiError, fetchMe, figmaFrameUrl, getProjectDetail, PROPERTY_LABELS, TEAM_LABELS, type IssueStatus, type Me, type ProjectDetail, type QaIssue, type Team,
} from '../../lib/api'
import { AppHeader } from '../projects/AppHeader'
import { WorkspaceSidebar } from '../members/WorkspaceSidebar'
import { Icon } from '../common/Icon'
import { ROLE_TAG } from '../common/roles'
import { CARD } from '../workspace/WorkspaceCards'

// QA 화면(시안 QA-01~FIN-02, 2026-11-08) 공용 조각.
// 시안과 스펙이 다른 곳은 스펙을 따른다(decision_log 2026-11-08):
//  - 담당은 사람이 아니라 팀(역할) — "사람이 직접 지정"은 "멤버가 직접 지정"으로 읽는다
//  - PASS 항목 목록·디자인 일치율·평균 신뢰도·심각도는 근거 정의가 없어 만들지 않는다. 결과·Queue는 이슈(FAIL)만 다룬다
//  - 이슈 번호는 시안의 QA-2001 같은 별도 번호가 없어 서버 id(#7)를 쓴다

/** 로그인 확인 + 프로젝트 상세. 비로그인이면 /login으로 보낸다 */
export function useProject(projectId: string) {
  const navigate = useNavigate()
  const [me, setMe] = useState<Me | null>(null)
  const [detail, setDetail] = useState<ProjectDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    try {
      setDetail(await getProjectDetail(projectId))
      setError(null)
    } catch (e) {
      setError(loadErrorMessage(e))
    }
  }, [projectId])

  useEffect(() => {
    fetchMe().then((result) => {
      if (!result.authenticated) {
        navigate('/login', { replace: true })
        return
      }
      setMe(result)
      void reload()
    })
  }, [navigate, reload])

  return { me, detail, error, reload, setError }
}

export function loadErrorMessage(e: unknown): string {
  if (e instanceof ApiError && e.status === 403) return '이 프로젝트의 멤버가 아니에요.'
  if (e instanceof ApiError && e.status === 404) return '찾을 수 없어요.'
  return '정보를 불러오지 못했어요.'
}

/** 헤더 + 작업 메뉴 + 본문 — 작업공간 화면들과 같은 틀 */
export function QaShell({ projectId, children }: { projectId: string; children: ReactNode }) {
  return (
    <div className="min-h-screen bg-qa-surface">
      <AppHeader />
      <div className="flex min-h-[calc(100vh-64px)]">
        <WorkspaceSidebar projectId={projectId} />
        <main className="min-w-0 flex-1 px-8 pb-12 pt-8">{children}</main>
      </div>
    </div>
  )
}

export function PageTitle({ crumbs, title, description, actions }: {
  crumbs: { label: string; to?: string }[]
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
}) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        <nav className="flex flex-wrap items-center gap-1 text-xs leading-4">
          {crumbs.map((c, i) => (
            <span key={i} className="flex items-center gap-1">
              {i > 0 && <Icon name="chevron-right" className="h-3 w-3 text-qa-text-sub" />}
              {c.to ? <Link to={c.to} className="text-qa-text-sub hover:underline">{c.label}</Link>
                : <span className="font-medium text-black">{c.label}</span>}
            </span>
          ))}
        </nav>
        <h1 className="mt-2 text-[28px] font-bold leading-9 text-black">{title}</h1>
        {description && <p className="mt-2 text-sm leading-5 text-qa-text-sub">{description}</p>}
      </div>
      {actions && <div className="flex flex-none flex-wrap gap-2">{actions}</div>}
    </div>
  )
}

export function ErrorCard({ message, projectId }: { message: string; projectId: string }) {
  return (
    <div role="alert" className={`${CARD} mt-8 p-10 text-center`}>
      <div className="text-sm font-semibold text-gray-900">{message}</div>
      <Link to={`/app/projects/${projectId}`} className="mt-3 inline-block text-sm font-semibold text-qa-primary hover:underline">작업공간 홈으로</Link>
    </div>
  )
}

export function Loading() {
  return <div className="mt-8 text-sm text-qa-text-sub">불러오는 중…</div>
}

/** 시안 팀 칩 — 이니셜 원 + 팀 이름. 역할 색을 그대로 쓴다 */
export function TeamTag({ team }: { team: Team | null }) {
  if (!team) {
    return (
      <span className="inline-flex h-6 flex-none items-center gap-1 whitespace-nowrap rounded-full border border-qa-border bg-white px-2 text-xs font-medium text-qa-text-sub">
        <span className="h-3 w-3 rounded-full border border-qa-sub" />미배정
      </span>
    )
  }
  const t = ROLE_TAG[team]
  return (
    <span className={`inline-flex h-6 flex-none items-center gap-1.5 whitespace-nowrap rounded-full pl-1 pr-2 text-xs font-medium ${t.bg} ${t.text}`}>
      <span className={`flex h-4 w-4 items-center justify-center rounded-full text-[10px] font-bold text-white ${t.dot}`}>{TEAM_INITIAL[team]}</span>
      {TEAM_LABELS[team]}
    </span>
  )
}

const TEAM_INITIAL: Record<Team, string> = { planner: 'P', designer: 'D', frontend: 'F', backend: 'B' }

export function TeamAvatar({ team, size = 32 }: { team: Team; size?: 24 | 32 }) {
  return (
    <span className={`flex flex-none items-center justify-center rounded-lg font-bold text-white ${ROLE_TAG[team].dot} ${size === 32 ? 'h-8 w-8 text-sm' : 'h-6 w-6 text-xs'}`}>
      {TEAM_INITIAL[team]}
    </span>
  )
}

/** 판정 칩 — FAIL(빨강)·PASS(초록) */
export function Verdict({ pass }: { pass: boolean }) {
  return pass ? (
    <span className="inline-flex h-[22px] flex-none items-center gap-1 rounded-full bg-qa-success/10 px-2 text-xs font-bold text-qa-success">
      <span className="h-1.5 w-1.5 rounded-full bg-qa-success" />PASS
    </span>
  ) : (
    <span className="inline-flex h-[22px] flex-none items-center gap-1 rounded-full bg-qa-warning/10 px-2 text-xs font-bold text-qa-warning">
      <span className="h-1.5 w-1.5 rounded-full bg-qa-warning" />FAIL
    </span>
  )
}

/** 이슈 진행 상태 — 시안의 미해결·수정 중·수정 완료·재검증 대기·해결됨 표기 */
export const PROGRESS: Record<IssueStatus, { label: string; tone: 'red' | 'blue' | 'orange' | 'green' }> = {
  needs_check: { label: '미해결', tone: 'red' },
  in_progress: { label: '수정 중', tone: 'blue' },
  fixed: { label: '수정 완료 · 재검증 대기', tone: 'orange' },
  needs_recheck: { label: '재확인 필요 · 재검증 실패', tone: 'red' }, // 수동 재검증 실패도 같은 상태라 Re-QA로 한정하지 않는다
  resolved: { label: '해결됨 · 재검증 완료', tone: 'green' },
}

const TONE: Record<string, string> = {
  red: 'bg-qa-warning/10 text-[#c8161a]',
  blue: 'bg-qa-primary/10 text-qa-primary-hover',
  orange: 'bg-[#f08c00]/10 text-[#a85a00]',
  green: 'bg-qa-success/10 text-qa-success',
}
const DOT: Record<string, string> = { red: 'bg-qa-warning', blue: 'bg-qa-primary', orange: 'bg-[#f08c00]', green: 'bg-qa-success' }

export function ProgressChip({ status }: { status: IssueStatus }) {
  const p = PROGRESS[status]
  return (
    <span className={`inline-flex h-[22px] flex-none items-center gap-1 whitespace-nowrap rounded-full px-2 text-xs font-medium ${TONE[p.tone]}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${DOT[p.tone]}`} />{p.label}
    </span>
  )
}

/** 시안 상태 배너(QA-02 실행 중 파랑, QA-03 완료 초록, QA-02F 실패 빨강) */
export function Banner({ tone, icon, title, badge, sub, action }: {
  tone: 'blue' | 'green' | 'red'
  icon: string
  title: ReactNode
  badge?: string
  sub?: ReactNode
  action?: ReactNode
}) {
  const box = { blue: 'border-qa-primary/40 bg-qa-primary/10', green: 'border-qa-success/40 bg-qa-success/10', red: 'border-qa-warning/40 bg-qa-warning/10' }[tone]
  const circle = { blue: 'bg-white text-qa-primary', green: 'bg-qa-success text-white', red: 'bg-white text-qa-warning' }[tone]
  const chip = { blue: 'bg-qa-primary text-white', green: 'bg-qa-primary text-white', red: 'bg-qa-primary text-white' }[tone]
  return (
    <section className={`mt-6 flex flex-wrap items-center gap-4 rounded-xl border px-5 py-4 ${box}`}>
      <span className={`flex h-10 w-10 flex-none items-center justify-center rounded-full ${circle}`}>
        <Icon name={icon} className={`h-5 w-5 ${icon === 'refresh' && tone === 'blue' ? 'animate-spin' : ''}`} />
      </span>
      <div className="min-w-[240px] flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-base font-bold leading-6 text-black">{title}</h2>
          {badge && <span className={`rounded-full px-2 text-xs font-medium leading-[18px] ${chip}`}>{badge}</span>}
        </div>
        {sub && <p className="mt-1 text-xs leading-4 text-qa-text-sub">{sub}</p>}
      </div>
      {action && <div className="flex-none">{action}</div>}
    </section>
  )
}

export const PRIMARY_BTN = 'inline-flex h-10 flex-none items-center justify-center gap-1.5 whitespace-nowrap rounded-lg bg-qa-primary px-4 text-sm font-medium text-white hover:bg-qa-primary-hover disabled:cursor-not-allowed disabled:opacity-40'
export const OUTLINE_BTN = 'inline-flex h-10 flex-none items-center justify-center gap-1.5 whitespace-nowrap rounded-lg border border-qa-primary bg-white px-4 text-sm font-medium text-qa-primary-hover hover:bg-qa-primary/5 disabled:cursor-not-allowed disabled:opacity-40'

export function SectionTitle({ n, children, aside }: { n?: number; children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="flex items-center gap-2">
      {n !== undefined && <span className="flex h-5 w-5 flex-none items-center justify-center rounded-full bg-qa-success text-xs font-bold text-white">{n}</span>}
      <h2 className="text-base font-bold leading-6 text-black">{children}</h2>
      {aside}
    </div>
  )
}

export function KeyValue({ rows }: { rows: { label: string; value: ReactNode }[] }) {
  return (
    <dl className="flex flex-col">
      {rows.map((r) => (
        <div key={r.label} className="flex items-center justify-between gap-4 border-b border-qa-border py-2.5 last:border-b-0">
          <dt className="flex-none text-xs text-qa-text-sub">{r.label}</dt>
          <dd className="min-w-0 truncate text-right text-sm font-medium text-black">{r.value}</dd>
        </div>
      ))}
    </dl>
  )
}

// ---------- 이슈 표시 ----------

/** 첫 QA 이슈의 screen_location은 "화면 / 요소" — 요소 이름을 앞에 쓴다 */
export function issueParts(issue: QaIssue): { screen: string; element: string } {
  // 수동 QA(AC31)는 화면 = 찍은 경로, 요소 = 사람이 쓴 제목
  if (issue.manual_qa) return { screen: issue.manual_qa.page_path ?? issue.manual_qa.figma_frame?.frame_name ?? issue.screen_location, element: issue.manual_qa.title }
  const i = issue.screen_location.lastIndexOf(' / ')
  return i < 0 ? { screen: issue.screen_location, element: issue.screen_location }
    : { screen: issue.screen_location.slice(0, i), element: issue.screen_location.slice(i + 3) }
}

/** 이슈의 기준 Figma 프레임 — 자동 QA는 비교한 프레임, 수동 QA는 등록 때 고른 프레임(AC33). 없으면 null */
export function issueFrame(issue: QaIssue, fileKey: string | null): { name: string; href: string | null } | null {
  if (issue.manual_qa) {
    const f = issue.manual_qa.figma_frame
    return f ? { name: f.frame_name, href: issue.figma_link ?? (fileKey ? figmaFrameUrl(fileKey, f.frame_id) : null) } : null
  }
  const frameId = issue.style_mismatch?.frame_id
  return { name: issueParts(issue).screen, href: fileKey && frameId ? figmaFrameUrl(fileKey, frameId) : issue.figma_link }
}

export function issueTitle(issue: QaIssue): string {
  if (issue.manual_qa) return issue.manual_qa.title
  const m = issue.style_mismatch
  return m ? `${issueParts(issue).element} — ${PROPERTY_LABELS[m.property]} 불일치` : issue.description
}

export function mismatchSummary(issue: QaIssue): string {
  const m = issue.style_mismatch
  return m ? `${m.expected_value} → ${m.actual_value}` : issue.description
}

/** "이전 대비" 같은 시안 수치 대신 실제 실행 시각 */
export function demoNote(demo: boolean): string {
  return demo ? ' · 개발용 가짜 비교기 결과(시연용)' : ''
}
