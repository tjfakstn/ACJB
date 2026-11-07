import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  ApiError, fetchMe, getProjectDetail, ISSUE_STATUS_LABELS, roleLabel,
  type IssueStatus, type Me, type ProjectDetail, type RunStatus,
} from '../lib/api'
import { AppHeader } from '../components/projects/AppHeader'
import { WorkspaceSidebar } from '../components/members/WorkspaceSidebar'
import { DeleteProjectDialog } from '../components/projects/DeleteProjectDialog'

// 프로젝트 작업공간 홈(시안 WS-01·01N·01M·02, SPEC AC25). 프로젝트를 열면 여기로 온다.
// 시안과 다른 점: Figma·GitHub 연결, 비교 분석, 첫 QA 실행은 아직 서버에 없어(AC21~AC23 구현 전) 상태만 보여주고 버튼은 잠가 둔다.
// 시안의 "프로젝트 정보" 중 생성일·QA 실행 이력 건수, "QA Queue" 목록은 아직 API가 없어 상태별 이슈 수로 대신한다.

const RUN_LABELS: Record<RunStatus, string> = {
  none: '아직 안 함',
  queued: '대기 중',
  running: '진행 중',
  succeeded: '완료',
  failed: '실패',
}

const STATUS_ORDER: IssueStatus[] = ['needs_check', 'in_progress', 'fixed', 'needs_recheck', 'resolved']

export function WorkspacePage() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const [me, setMe] = useState<Me | null>(null)
  const [detail, setDetail] = useState<ProjectDetail | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [deleteOpen, setDeleteOpen] = useState(false)

  const load = useCallback(async () => {
    try {
      setDetail(await getProjectDetail(projectId))
      setLoadError(null)
    } catch (e) {
      setLoadError(e instanceof ApiError && e.status === 403
        ? '이 프로젝트의 멤버가 아니에요.'
        : e instanceof ApiError && e.status === 404 ? '프로젝트를 찾을 수 없어요.' : '프로젝트 정보를 불러오지 못했어요.')
    }
  }, [projectId])

  useEffect(() => {
    fetchMe().then((result) => {
      if (!result.authenticated) {
        navigate('/login', { replace: true })
        return
      }
      setMe(result)
      void load()
    })
  }, [navigate, load])

  if (!me) return null

  const isOwner = !!detail && detail.owner_id === me.id
  const totalIssues = detail ? STATUS_ORDER.reduce((sum, s) => sum + detail.issue_counts[s], 0) : 0
  const hasOthers = !!detail && (detail.member_count > 1 || detail.pending_invitation_count > 0)
  const checklist = detail ? [
    { label: '프로젝트 생성', done: true, hint: detail.name },
    { label: '멤버 초대 및 역할 지정', done: hasOthers,
      hint: hasOthers ? `참여 ${detail.member_count}명 · 초대 대기 ${detail.pending_invitation_count}명` : '아직 초대한 멤버가 없어요 · 역할은 초대받은 사람이 수락할 때 골라요' },
    { label: 'Figma 디자인 파일 연결', done: !!detail.figma, hint: detail.figma ? `${detail.figma.file_name} › ${detail.figma.page_name}` : '새 프로젝트 마법사에서 연결해요' },
    { label: 'GitHub 저장소 연결', done: !!detail.github, hint: detail.github ? `${detail.github.repo} · ${detail.github.branch} 브랜치` : '새 프로젝트 마법사에서 연결해요' },
    { label: '첫 QA 실행', done: detail.first_qa_status === 'succeeded', hint: `상태: ${RUN_LABELS[detail.first_qa_status]}` },
  ] : []
  const doneCount = checklist.filter((c) => c.done).length

  return (
    <div className="min-h-screen bg-[#f5f7fa]">
      <AppHeader />
      <div className="flex min-h-[calc(100vh-64px)]">
        <WorkspaceSidebar projectId={projectId} />
        <main className="min-w-0 flex-1 px-10 pb-16 pt-8">
          <nav className="text-xs text-gray-500">
            <Link to="/app/projects" className="hover:underline">모든 프로젝트</Link>
            <span className="mx-1.5">›</span>
            <span className="text-gray-900">{detail?.name ?? '프로젝트'}</span>
          </nav>

          <div className="mt-3 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1 className="text-2xl font-extrabold tracking-tight">프로젝트 작업공간 홈</h1>
              <p className="mt-2 text-sm text-gray-600">프로젝트 준비 상태와 QA 진행 상황을 한눈에 봐요. 멤버는 언제든 [멤버 초대]로 추가할 수 있어요.</p>
            </div>
            <div className="flex flex-none gap-2">
              <Link to={`/app/projects/${projectId}/members?invite=1`}
                className="inline-flex h-10 items-center rounded-lg border border-qa-primary bg-white px-4 text-sm font-semibold text-qa-primary hover:bg-[#eef4ff]">
                멤버 초대
              </Link>
              <button type="button" disabled title="Figma·GitHub 연결과 비교 분석 기능이 준비되면 실행할 수 있어요"
                className="inline-flex h-10 items-center rounded-lg bg-qa-primary px-4 text-sm font-semibold text-white disabled:opacity-40">
                첫 QA 실행
              </button>
            </div>
          </div>

          {loadError ? (
            <div role="alert" className="mt-8 rounded-2xl border border-qa-border bg-white p-10 text-center">
              <div className="text-sm font-semibold text-gray-900">{loadError}</div>
              <Link to="/app/projects" className="mt-3 inline-block text-sm font-semibold text-qa-primary hover:underline">프로젝트 목록으로</Link>
            </div>
          ) : !detail ? (
            <div className="mt-8 text-sm text-qa-sub">불러오는 중…</div>
          ) : (
            <div className="mt-6 grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_340px]">
              <div className="flex min-w-0 flex-col gap-5">
                {totalIssues === 0 && (
                  <section className="flex items-center gap-4 rounded-2xl border border-qa-border bg-white px-6 py-5">
                    <span className="flex h-10 w-10 flex-none items-center justify-center rounded-full bg-[#eef4ff] text-qa-primary">!</span>
                    <div>
                      <div className="text-sm font-bold text-gray-900">아직 QA 기록이 없습니다.</div>
                      <div className="mt-0.5 text-xs text-qa-sub">Figma·GitHub를 연결하고 첫 QA를 실행하면 디자인과 구현의 차이가 이슈로 정리돼요.</div>
                    </div>
                  </section>
                )}

                <section className="rounded-2xl border border-qa-border bg-white p-6">
                  <div className="flex items-center gap-2">
                    <h2 className="text-sm font-bold text-gray-900">프로젝트 시작하기</h2>
                    <span className="rounded-full bg-[#eef4ff] px-2 py-0.5 text-xs font-semibold text-qa-primary">{doneCount} / {checklist.length} 완료</span>
                  </div>
                  <ul className="mt-4 divide-y divide-[#eef1f5] rounded-xl border border-qa-border">
                    {checklist.map((c) => (
                      <li key={c.label} className="flex items-center gap-3 px-4 py-3">
                        <span aria-label={c.done ? '완료' : '미완료'}
                          className={`flex h-5 w-5 flex-none items-center justify-center rounded-full border text-[11px] ${c.done ? 'border-qa-success bg-qa-success text-white' : 'border-gray-300 text-transparent'}`}>✓</span>
                        <div className="min-w-0">
                          <div className="text-sm font-medium text-gray-900">{c.label}</div>
                          <div className="truncate text-xs text-qa-sub">{c.hint}</div>
                        </div>
                        {c.label === '멤버 초대 및 역할 지정' && !c.done && (
                          <Link to={`/app/projects/${projectId}/members?invite=1`}
                            className="ml-auto flex-none rounded-lg bg-qa-primary px-3 py-1.5 text-xs font-semibold text-white">멤버 초대</Link>
                        )}
                      </li>
                    ))}
                  </ul>
                </section>

                <section className="rounded-2xl border border-qa-border bg-white p-6">
                  <h2 className="text-sm font-bold text-gray-900">이슈 현황 <span className="ml-1 text-xs font-normal text-qa-sub">전체 {totalIssues}건</span></h2>
                  <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-5">
                    {STATUS_ORDER.map((s) => (
                      <div key={s} className="rounded-xl border border-qa-border px-4 py-3">
                        <div className="text-xs text-gray-500">{ISSUE_STATUS_LABELS[s]}</div>
                        <div className="mt-1 text-xl font-extrabold text-gray-900">{detail.issue_counts[s]}</div>
                      </div>
                    ))}
                  </div>
                </section>
              </div>

              <div className="flex min-w-0 flex-col gap-5">
                <section className="rounded-2xl border border-qa-border bg-white p-6">
                  <h2 className="text-sm font-bold text-gray-900">프로젝트 정보</h2>
                  <dl className="mt-4 space-y-3 text-sm">
                    <Row label="프로젝트" value={detail.name} />
                    <Row label="설명" value={detail.description ?? '-'} />
                    <Row label="내 역할" value={`${roleLabel(detail.my_role)}${isOwner ? ' · 소유자' : ''}`} />
                    <Row label="비교 분석" value={RUN_LABELS[detail.analysis_status]} />
                    <Row label="첫 QA" value={RUN_LABELS[detail.first_qa_status]} />
                  </dl>
                </section>

                <section className="rounded-2xl border border-qa-border bg-white p-6">
                  <div className="flex items-center justify-between">
                    <h2 className="text-sm font-bold text-gray-900">프로젝트 멤버 <span className="ml-1 text-xs font-normal text-qa-sub">{detail.member_count}명</span></h2>
                    <Link to={`/app/projects/${projectId}/members`} className="text-xs font-semibold text-qa-primary hover:underline">멤버 관리</Link>
                  </div>
                  <p className="mt-3 text-xs text-qa-sub">
                    {detail.pending_invitation_count > 0
                      ? `초대 대기 중 ${detail.pending_invitation_count}명 — 수락하면 멤버로 표시돼요.`
                      : detail.member_count > 1 ? '대기 중인 초대가 없어요.' : '아직 초대한 멤버가 없어요.'}
                  </p>
                </section>

                <section className="rounded-2xl border border-qa-border bg-white p-6">
                  <h2 className="text-sm font-bold text-gray-900">연결 상태</h2>
                  <div className="mt-4 space-y-3">
                    <Connection name="Figma" detail={detail.figma ? `${detail.figma.file_name} › ${detail.figma.page_name}` : null} />
                    <Connection name="GitHub" detail={detail.github ? `${detail.github.repo} · ${detail.github.branch} 브랜치` : null} />
                  </div>
                  <p className="mt-3 text-xs text-qa-sub">연결은 소유자가 새 프로젝트 마법사에서 해요. 이미 만든 프로젝트의 연결 관리 화면은 아직 없어요.</p>
                </section>
              </div>
            </div>
          )}

          {/* 프로젝트 삭제(AC26) — 소유자에게만, 맨 아래에 둬서 실수로 누르지 않게 한다 */}
          {detail && isOwner && (
            <section className="mt-10 flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-qa-warning/40 bg-white p-6">
              <div>
                <h2 className="text-sm font-bold text-qa-warning">프로젝트 삭제</h2>
                <p className="mt-1 text-xs text-qa-text-sub">프로젝트와 그 안의 이슈·재검증·알림·초대·연결 정보를 모두 지워요. 되돌릴 수 없어요.</p>
              </div>
              <button type="button" onClick={() => setDeleteOpen(true)}
                className="h-10 flex-none whitespace-nowrap rounded-lg border border-qa-warning bg-white px-4 text-sm font-medium text-qa-warning hover:bg-qa-warning/5">
                프로젝트 삭제
              </button>
            </section>
          )}
        </main>
      </div>
      {deleteOpen && detail && (
        <DeleteProjectDialog project={detail} onClose={() => setDeleteOpen(false)} onDeleted={() => navigate('/app/projects', { replace: true })} />
      )}
    </div>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-gray-500">{label}</dt>
      <dd className="truncate text-right font-medium text-gray-900">{value}</dd>
    </div>
  )
}

function Connection({ name, detail }: { name: string; detail: string | null }) {
  return (
    <div className="flex items-center justify-between rounded-xl border border-qa-border px-4 py-3">
      <div className="min-w-0">
        <div className="text-sm font-semibold text-gray-900">{name} 연결</div>
        <div className="truncate text-xs text-qa-sub">{detail ?? '연결 전'}</div>
      </div>
      <span className={`flex-none rounded-full px-2.5 py-1 text-xs font-medium ${detail ? 'bg-[#e8f7f1] text-[#1f8a5f]' : 'bg-gray-100 text-gray-500'}`}>
        {detail ? '연결됨' : '연결 전'}
      </span>
    </div>
  )
}
