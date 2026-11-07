import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchMe, listProjects, type Me, type ProjectSummary } from '../lib/api'
import { AppHeader } from '../components/projects/AppHeader'
import { ProjectToolbar, type SortKey, type StatusFilter } from '../components/projects/ProjectToolbar'
import { ProjectListItem } from '../components/projects/ProjectListItem'
import { EmptyState } from '../components/projects/EmptyState'
import { CreateProjectDialog } from '../components/projects/CreateProjectDialog'
import { Icon } from '../components/common/Icon'

// 모든 프로젝트(시안 PRJ-01, 빈 상태 PRJ-01E, 분석 미완료 PRJ-01A). 내가 멤버인 프로젝트만 서버가 name asc로 준다(SPEC AC15).
// "이상 없음"=비교 분석이 끝났고 미해결 이슈가 0건, "FAIL 이슈 보유"=미해결 이슈가 있음, "분석 미완료"=비교 분석이 끝나지 않음.

const hasFail = (p: ProjectSummary) => p.open_issue_count > 0
const analyzed = (p: ProjectSummary) => p.analysis_status === 'succeeded'
const isOk = (p: ProjectSummary) => analyzed(p) && !hasFail(p)

export function ProjectsPage() {
  const navigate = useNavigate()
  const [me, setMe] = useState<Me | null>(null)
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<StatusFilter>('all')
  const [sort, setSort] = useState<SortKey>('name')
  const [dialogOpen, setDialogOpen] = useState(false)

  const load = useCallback(() => {
    setLoadError(false)
    listProjects().then(setProjects).catch(() => setLoadError(true))
  }, [])

  useEffect(() => {
    fetchMe().then((result) => {
      if (!result.authenticated) {
        navigate('/login', { replace: true })
        return
      }
      setMe(result)
      load()
    })
  }, [navigate, load])

  if (!me) return null

  const all = projects ?? []
  const q = query.trim().toLowerCase()
  const visible = all
    .filter((p) => p.name.toLowerCase().includes(q))
    .filter((p) => status === 'all' || (status === 'fail' ? hasFail(p) : status === 'ok' ? isOk(p) : !analyzed(p)))
    .sort((a, b) => {
      if (sort === 'fail') return b.open_issue_count - a.open_issue_count
      if (sort === 'recent') return (b.last_analyzed_at ?? '').localeCompare(a.last_analyzed_at ?? '')
      return 0   // 서버 순서(name asc, id asc)를 그대로 쓴다
    })

  return (
    <div className="min-h-screen bg-qa-surface">
      <AppHeader />

      <main className="mx-auto max-w-[1184px] px-8 pb-8 pt-8">
        <div className="flex items-end justify-between gap-6">
          <div>
            <h1 className="text-[28px] font-bold leading-8 text-black">모든 프로젝트</h1>
            <p className="mt-2 text-sm leading-5 text-qa-text-sub">연결된 프로젝트의 디자인 QA 진행 상황을 확인하고 작업공간으로 이동하세요.</p>
          </div>
          <button type="button" onClick={() => setDialogOpen(true)}
            className="inline-flex h-10 flex-none items-center gap-2 rounded-lg bg-qa-primary-hover px-4 text-sm font-medium text-white hover:bg-[#0062bd]">
            <Icon name="plus" className="h-5 w-5" />새 프로젝트
          </button>
        </div>

        {loadError ? (
          <div role="alert" className="mt-6 rounded-2xl border border-qa-border bg-white p-10 text-center">
            <div className="text-sm font-semibold text-black">프로젝트 목록을 불러오지 못했어요</div>
            <button type="button" onClick={load} className="mt-3 text-sm font-semibold text-qa-primary hover:underline">다시 시도</button>
          </div>
        ) : projects === null ? (
          <div className="mt-6 rounded-2xl border border-qa-border bg-white p-10 text-center text-sm text-qa-sub">불러오는 중…</div>
        ) : all.length === 0 ? (
          <div className="mt-6 flex flex-col gap-6">
            <ProjectToolbar query={query} onQueryChange={setQuery} status={status} onStatusChange={setStatus} sort={sort} onSortChange={setSort} />
            <EmptyState onCreate={() => setDialogOpen(true)} />
          </div>
        ) : (
          <div className="mt-6 flex flex-col gap-6">
            <SummaryStrip projects={all} />
            <ProjectToolbar query={query} onQueryChange={setQuery} status={status} onStatusChange={setStatus} sort={sort} onSortChange={setSort} />
            <section className="overflow-hidden rounded-2xl border border-qa-border bg-white">
              {visible.length === 0 ? (
                <div className="p-10 text-center text-sm text-qa-sub">조건에 맞는 프로젝트가 없어요</div>
              ) : (
                visible.map((p) => <ProjectListItem key={p.id} project={p} />)
              )}
            </section>
          </div>
        )}
      </main>

      {dialogOpen && <CreateProjectDialog
        onClose={() => { setDialogOpen(false); load() }}
        onCreated={load}
        onManageMembers={(p) => navigate(`/app/projects/${p.id}/members`)}
        onStart={(p) => navigate(`/app/projects/${p.id}`)}
      />}
    </div>
  )
}

/** 시안 Highlight Strip — 전체 수, 분석 완료/미완료, FAIL 보유·이상 없음 비율 막대 */
function SummaryStrip({ projects }: { projects: ProjectSummary[] }) {
  const total = projects.length
  const done = projects.filter(analyzed).length
  const fail = projects.filter(hasFail).length
  const ok = projects.filter(isOk).length
  const rest = Math.max(0, total - fail - ok)
  const pct = (n: number) => `${(n / total) * 100}%`
  return (
    <section className="flex items-center gap-8 rounded-2xl border border-qa-border bg-gradient-to-r from-[#eef6ff] via-[#f6faff] to-white px-6 py-5">
      <div className="flex min-w-[72px] flex-none flex-col gap-1 whitespace-nowrap">
        <span className="text-xs font-medium text-qa-text-sub">전체 프로젝트</span>
        <span className="text-[28px] font-bold leading-9 text-black">{total}</span>
        <span className={`text-xs ${done === total ? 'text-qa-text-sub' : 'text-qa-warning'}`}>
          {done === total ? '모두 분석 완료' : `분석 완료 ${done} · 미완료 ${total - done}`}
        </span>
      </div>
      <span className="h-[76px] w-px flex-none bg-qa-border" aria-hidden="true" />
      <div className="flex min-w-0 flex-1 flex-col gap-3">
        <div className="flex h-2 gap-0.5 overflow-hidden rounded-full bg-[#e8e8e8]" role="img"
          aria-label={`이상 없음 ${ok}개, FAIL 이슈 보유 ${fail}개, 그 밖 ${rest}개`}>
          {ok > 0 && <span className="h-full rounded-full bg-qa-success" style={{ width: pct(ok) }} />}
          {fail > 0 && <span className="h-full rounded-full bg-qa-warning" style={{ width: pct(fail) }} />}
        </div>
        <div className="flex gap-6 text-xs">
          <span className="flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-qa-warning" /><span className="text-qa-text-sub">FAIL 이슈 보유</span><span className="font-medium text-black">{fail}</span></span>
          <span className="flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-qa-success" /><span className="text-qa-text-sub">이상 없음</span><span className="font-medium text-black">{ok}</span></span>
        </div>
      </div>
    </section>
  )
}
