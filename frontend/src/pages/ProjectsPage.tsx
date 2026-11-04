import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchMe } from '../lib/api'
import { AppHeader } from '../components/projects/AppHeader'
import { HighlightStrip } from '../components/projects/HighlightStrip'
import { ProjectToolbar } from '../components/projects/ProjectToolbar'
import { ProjectListItem } from '../components/projects/ProjectListItem'
import { EmptyState } from '../components/projects/EmptyState'
import { ConnectProjectDialog } from '../components/projects/ConnectProjectDialog'
import type { ConnectProjectInput, Project } from '../components/projects/types'

// 02 프로젝트 목록 화면 (로그인 이후).
// 흐름: 빈 상태(02-2) → '새 프로젝트 연결' → 연결 팝업(기본정보→서비스연결→분석) → 목록(02-1)에 카드 추가.
// NOTE: 아직 백엔드 연동 전이라 목록은 로컬 상태로만 관리한다.
// TODO(백엔드 연동):
//   - 마운트 시 GET /projects 로 목록 로드 (openapi.yaml에 목록 조회 엔드포인트 신설 필요 — 현재 미정)
//   - handleComplete 에서 프로젝트 생성/연결 API 호출로 교체하고, 성공 응답으로 목록 갱신
export function ProjectsPage() {
  const navigate = useNavigate()
  const [authChecked, setAuthChecked] = useState(false)
  const [projects, setProjects] = useState<Project[]>([])
  const [dialogOpen, setDialogOpen] = useState(false)

  useEffect(() => {
    fetchMe().then((me) => {
      if (!me.authenticated) {
        navigate('/login', { replace: true })
      } else {
        setAuthChecked(true)
      }
    })
  }, [navigate])

  if (!authChecked) return null

  const hasProjects = projects.length > 0
  const failProjects = projects.filter((p) => p.failCount > 0).length
  const passProjects = projects.length - failProjects

  // 연결 + 분석이 "성공"했을 때만 호출된다(실패하면 팝업 안에서 오류로 끝나 목록에 추가되지 않음).
  // qaCount/failCount(불일치 건수)는 백엔드 분석 결과에서 받은 값을 그대로 쓴다 — 프론트에서 만들지 않는다.
  const handleComplete = (input: ConnectProjectInput, counts: { qaCount: number; failCount: number }) => {
    // TODO: 서버 생성 응답(프로젝트 id 등)으로 대체.
    const newProject: Project = {
      id: crypto.randomUUID(),
      name: input.name || '새 프로젝트',
      description: input.description,
      githubLink: input.githubLink,
      figmaLink: input.figmaLink,
      qaCount: counts.qaCount,
      failCount: counts.failCount,
      lastAnalyzedLabel: '방금 전',
    }
    setProjects((prev) => [newProject, ...prev])
    setDialogOpen(false)
  }

  return (
    <div className="min-h-screen bg-[#f5f7fa]">
      <AppHeader />

      <main className="mx-auto max-w-[1184px] px-8 pb-20 pt-10">
        <div className="mb-6 flex items-start justify-between gap-6">
          <div>
            <h1 className="text-2xl font-extrabold leading-8 tracking-tight">모든 프로젝트</h1>
            <p className="mt-2 text-sm leading-5 text-gray-600">
              연결된 프로젝트의 디자인 QA 진행 상황을 확인하고 작업공간으로 이동하세요.
            </p>
          </div>
          {hasProjects && (
            <button
              type="button"
              onClick={() => setDialogOpen(true)}
              className="inline-flex h-10 flex-none items-center gap-2 rounded-lg bg-qa-primary px-[18px] text-sm font-semibold text-white transition-colors hover:bg-qa-primary-hover"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
                <path d="M12 5v14M5 12h14" />
              </svg>
              새 프로젝트 연결
            </button>
          )}
        </div>

        {hasProjects && <HighlightStrip total={projects.length} failProjects={failProjects} passProjects={passProjects} />}

        <ProjectToolbar />

        {hasProjects ? (
          <section className="overflow-hidden rounded-2xl border border-qa-border bg-white shadow-[0_1px_2px_rgba(15,23,41,0.04),0_4px_12px_rgba(15,23,41,0.05)]">
            {projects.map((p) => (
              <ProjectListItem key={p.id} project={p} />
            ))}
          </section>
        ) : (
          <EmptyState onConnect={() => setDialogOpen(true)} />
        )}
      </main>

      <ConnectProjectDialog open={dialogOpen} onClose={() => setDialogOpen(false)} onComplete={handleComplete} />
    </div>
  )
}
