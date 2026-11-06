import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchMe, listProjects, type Me, type Project } from '../lib/api'
import { AppHeader } from '../components/projects/AppHeader'
import { ProjectToolbar } from '../components/projects/ProjectToolbar'
import { ProjectListItem } from '../components/projects/ProjectListItem'
import { EmptyState } from '../components/projects/EmptyState'
import { CreateProjectDialog } from '../components/projects/CreateProjectDialog'

// 프로젝트 목록(시안 U01-03, 빈 상태 U01-A1). 내가 멤버인 프로젝트만 서버가 name asc로 돌려준다(SPEC AC15).
export function ProjectsPage() {
  const navigate = useNavigate()
  const [me, setMe] = useState<Me | null>(null)
  const [projects, setProjects] = useState<Project[] | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [query, setQuery] = useState('')
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

  const visible = (projects ?? []).filter((p) => p.name.toLowerCase().includes(query.trim().toLowerCase()))
  const hasProjects = (projects?.length ?? 0) > 0

  return (
    <div className="min-h-screen bg-[#f5f7fa]">
      <AppHeader />

      <main className="mx-auto max-w-[1184px] px-8 pb-20 pt-10">
        <div className="mb-6 flex items-start justify-between gap-6">
          <div>
            <h1 className="text-2xl font-extrabold leading-8 tracking-tight">모든 프로젝트</h1>
            <p className="mt-2 text-sm leading-5 text-gray-600">참여 중인 프로젝트의 QA 진행 상황을 확인하고 작업공간으로 이동하세요.</p>
          </div>
          {hasProjects && (
            <button type="button" onClick={() => setDialogOpen(true)}
              className="inline-flex h-10 flex-none items-center gap-2 rounded-lg bg-qa-primary px-[18px] text-sm font-semibold text-white transition-colors hover:bg-qa-primary-hover">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
                <path d="M12 5v14M5 12h14" />
              </svg>
              새 프로젝트
            </button>
          )}
        </div>

        {loadError ? (
          <div role="alert" className="rounded-2xl border border-qa-border bg-white p-10 text-center">
            <div className="text-sm font-semibold text-gray-900">프로젝트 목록을 불러오지 못했어요</div>
            <button type="button" onClick={load} className="mt-3 text-sm font-semibold text-qa-primary hover:underline">다시 시도</button>
          </div>
        ) : projects === null ? (
          <div className="rounded-2xl border border-qa-border bg-white p-10 text-center text-sm text-qa-sub">불러오는 중…</div>
        ) : hasProjects ? (
          <>
            <section className="mb-6 flex items-center rounded-2xl border border-[#dce6f5] bg-gradient-to-r from-[#eef4ff] via-[#f4f9ff] to-white px-7 py-5">
              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium text-gray-600">전체 프로젝트</span>
                <span className="text-[28px] font-extrabold leading-9 tracking-tight text-gray-900">{projects.length}</span>
                <span className="text-xs text-qa-sub">내가 소유한 프로젝트 {projects.filter((p) => p.owner_id === me.id).length}개</span>
              </div>
            </section>
            <ProjectToolbar query={query} onQueryChange={setQuery} />
            <section className="overflow-hidden rounded-2xl border border-qa-border bg-white shadow-[0_1px_2px_rgba(15,23,41,0.04),0_4px_12px_rgba(15,23,41,0.05)]">
              {visible.length === 0 ? (
                <div className="p-10 text-center text-sm text-qa-sub">‘{query.trim()}’와 일치하는 프로젝트가 없어요</div>
              ) : (
                visible.map((p) => <ProjectListItem key={p.id} project={p} isOwner={p.owner_id === me.id} />)
              )}
            </section>
          </>
        ) : (
          <EmptyState onCreate={() => setDialogOpen(true)} />
        )}
      </main>

      {dialogOpen && <CreateProjectDialog
        onClose={() => setDialogOpen(false)}
        onCreated={load}
        onInvite={(p) => navigate(`/app/projects/${p.id}/members?invite=1`)}
      />}
    </div>
  )
}
