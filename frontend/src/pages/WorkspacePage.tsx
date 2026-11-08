import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  ApiError, fetchMe, formatDate, getAnalysis, getMappings, getProjectDetail, listInvitations, listMembers, ROLE_LABELS,
  type Invitation, type MappingList, type Me, type MemberRole, type ProjectDetail, type ProjectMember, type ProjectRun,
} from '../lib/api'
import { AppHeader } from '../components/projects/AppHeader'
import { WorkspaceSidebar } from '../components/members/WorkspaceSidebar'
import { DeleteProjectDialog } from '../components/projects/DeleteProjectDialog'
import { InviteDialog } from '../components/members/InviteDialog'
import { Icon } from '../components/common/Icon'
import {
  AnalysisCard, ConnectionsCard, FirstQaStatus, GettingStarted, MappingCard, MembersCard, ProjectInfoCard, QaQueueCard,
  SmallButton, WorkflowStepper, type ChecklistItem,
} from '../components/workspace/WorkspaceCards'
import { AnalysisStep } from '../components/projects/AnalysisStep'
import { Overlay } from '../components/projects/WizardParts'
import { currentStep } from '../components/workspace/workflow'

// 프로젝트 작업공간 홈(시안 WS-01·01N·01M·02, SPEC AC25). 프로젝트를 열면 여기로 온다.
// 상태별 모양: 멤버 없음(01N) / 초대 후(01M) / 첫 QA 이후(02). 같은 컴포넌트가 데이터로 갈라진다.
// 시안과 다른 점(데이터가 아직 없어 지어내지 않는다, N4):
//  - "최근 AI 분석"은 서버가 주는 실행 상태·매핑 수만 보여 준다(처리 요소 수·변경 이력 분석은 없다).
//    "디자인-코드 매핑" 카드는 분석이 성공했을 때만, 평균 매핑 신뢰도는 서버가 주지 않아 뺐다
//  - [첫 QA 실행]·[QA 준비하기]·[연결 관리]: 화면이 아직 없어 잠가 둔다

/** 마법사 끝(ProjectsPage)에서 넘겨주는 토스트 신호 */
export interface WorkspaceLocationState {
  justCreated?: boolean
  /** 초대 수락 화면(GST-02)에서 고른 역할 */
  joinedRole?: MemberRole
}

type Toast = { kind: 'created' } | { kind: 'invited'; count: number } | { kind: 'joined'; role: MemberRole }

export function WorkspacePage() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const [me, setMe] = useState<Me | null>(null)
  const [detail, setDetail] = useState<ProjectDetail | null>(null)
  const [members, setMembers] = useState<ProjectMember[]>([])
  const [invitations, setInvitations] = useState<Invitation[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [inviteOpen, setInviteOpen] = useState(false)
  const [run, setRun] = useState<ProjectRun | null>(null)
  const [mapping, setMapping] = useState<MappingList | null>(null)
  const [analysisOpen, setAnalysisOpen] = useState(false)
  const [invitedNow, setInvitedNow] = useState(0)
  const [toast, setToast] = useState<Toast | null>(() => {
    const state = location.state as WorkspaceLocationState | null
    // GST-03 — 초대를 수락하고 들어오면 고른 역할로 참여했다고 알린다
    if (state?.joinedRole) return { kind: 'joined', role: state.joinedRole }
    return state?.justCreated ? { kind: 'created' } : null
  })

  // 새로고침하면 토스트가 다시 뜨지 않게 history state를 비운다
  useEffect(() => {
    const state = location.state as WorkspaceLocationState | null
    if (state?.justCreated || state?.joinedRole) navigate('.', { replace: true, state: null })
  }, [location.state, navigate])

  const load = useCallback(async () => {
    try {
      const [d, m, inv, run] = await Promise.all([getProjectDetail(projectId), listMembers(projectId), listInvitations(projectId),
        getAnalysis(projectId)])
      setRun(run)
      setMapping(run?.status === 'succeeded' ? await getMappings(projectId) : null)
      setDetail(d)
      setMembers(m)
      setInvitations(inv)
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

  const closeInvite = () => {
    setInviteOpen(false)
    if (invitedNow > 0) setToast({ kind: 'invited', count: invitedNow })
    setInvitedNow(0)
  }

  return (
    <div className="min-h-screen bg-qa-surface">
      <AppHeader />
      <div className="flex min-h-[calc(100vh-64px)]">
        <WorkspaceSidebar projectId={projectId} />
        <main className="min-w-0 flex-1 px-8 pb-8 pt-8">
          {loadError ? (
            <>
              <PageHeader name="프로젝트" description="" actions={null} />
              <div role="alert" className="mt-8 rounded-xl border border-qa-border bg-white p-10 text-center">
                <div className="text-sm font-semibold text-gray-900">{loadError}</div>
                <Link to="/app/projects" className="mt-3 inline-block text-sm font-semibold text-qa-primary hover:underline">프로젝트 목록으로</Link>
              </div>
            </>
          ) : !detail ? (
            <div className="text-sm text-qa-text-sub">불러오는 중…</div>
          ) : (
            <Workspace
              detail={detail} me={me} members={members} invitations={invitations} toast={toast} run={run} mapping={mapping}
              onInvite={() => setInviteOpen(true)} onDelete={() => setDeleteOpen(true)} onAnalysis={() => setAnalysisOpen(true)}
            />
          )}
        </main>
      </div>
      {analysisOpen && detail && (
        <Overlay width={640} onClose={() => { setAnalysisOpen(false); void load() }}>
          <AnalysisStep projectId={projectId} projectName={detail.name} inWizard={false}
            onClose={() => { setAnalysisOpen(false); void load() }}
            onCheckConnection={() => { setAnalysisOpen(false); void load() }}
            onDone={() => { setAnalysisOpen(false); void load() }} />
        </Overlay>
      )}
      {inviteOpen && (
        <InviteDialog projectId={projectId} projectName={detail?.name ?? ''} ownerId={detail?.owner_id ?? ''} meId={me.id}
          members={members} invitations={invitations.filter((i) => i.status === 'pending')} onClose={closeInvite}
          onInvited={() => { setInvitedNow((n) => n + 1); void load() }}
          onManageMembers={() => navigate(`/app/projects/${projectId}/members`)} onBackToWorkspace={closeInvite} />
      )}
      {deleteOpen && detail && (
        <DeleteProjectDialog project={detail} onClose={() => setDeleteOpen(false)} onDeleted={() => navigate('/app/projects', { replace: true })} />
      )}
    </div>
  )
}

function Workspace({ detail, me, members, invitations, toast, run, mapping, onInvite, onDelete, onAnalysis }: {
  detail: ProjectDetail
  me: Me
  members: ProjectMember[]
  invitations: Invitation[]
  toast: Toast | null
  run: ProjectRun | null
  mapping: MappingList | null
  onInvite: () => void
  onDelete: () => void
  onAnalysis: () => void
}) {
  const isOwner = detail.owner_id === me.id
  const c = detail.issue_counts
  const totalIssues = c.needs_check + c.in_progress + c.fixed + c.needs_recheck + c.resolved
  const openIssues = totalIssues - c.resolved
  const qaDone = detail.first_qa_status === 'succeeded'
  const others = members.length - 1
  const alone = others <= 0 && invitations.length === 0
  const bothConnected = !!detail.figma && !!detail.github
  const readyAt = bothConnected
    ? formatDate([detail.figma!.connected_at, detail.github!.connected_at].sort()[1])
    : null

  const lockedQa = '첫 QA 실행 기능은 준비 중이에요'
  const firstQaButton = (
    <button type="button" disabled title={isOwner ? lockedQa : '첫 QA는 프로젝트 소유자만 실행할 수 있어요'}
      className="h-10 flex-none whitespace-nowrap rounded-lg bg-qa-primary px-4 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-40">
      {qaDone ? '디자인 QA 실행' : '첫 QA 실행'}
    </button>
  )

  const description = qaDone
    ? '운영 중인 프로젝트예요 · QA 결과와 진행 상태는 그대로 유지됩니다.'
    : toast?.kind === 'invited'
      ? '멤버를 초대했어요. 초대 상태는 멤버 관리에서 확인할 수 있어요.'
      : alone
        ? '프로젝트를 시작했어요. 멤버 초대는 나중으로 미뤘어요 — 언제든 [멤버 초대]로 팀원을 추가할 수 있어요.'
        : '디자인과 코드의 연결 상태, AI 분석 결과, QA 진행 현황을 한곳에서 확인하세요.'

  const checklist: ChecklistItem[] = [
    { label: '프로젝트 생성', done: true, sub: `${detail.name} · ${formatDate(detail.created_at)} 생성` },
    {
      label: '멤버 초대 및 역할 지정',
      done: !alone,
      sub: alone
        ? '아직 초대한 멤버가 없어요 · 초대받은 팀원이 수락할 때 역할을 골라요'
        : `${others + invitations.length}명 초대 · 수락 대기 ${invitations.length}명 (참여 완료 ${Math.max(others, 0)}명)`,
      action: alone
        ? <SmallButton kind="fill" icon="user-plus" onClick={onInvite}>멤버 초대</SmallButton>
        : <SmallButton kind="ghost" to={`/app/projects/${detail.id}/members`}>멤버 관리</SmallButton>,
    },
    {
      label: 'Figma 디자인 파일 연결',
      done: !!detail.figma,
      sub: detail.figma
        ? `연결됨 · ${detail.figma.file_name} › ${detail.figma.page_name} · ${formatDate(detail.figma.connected_at)}`
        : '아직 연결하지 않았어요 · 소유자가 Figma 페이지 링크를 연결해요',
    },
    {
      label: 'GitHub 저장소 연결',
      done: !!detail.github,
      sub: detail.github
        ? `연결됨 · ${detail.github.repo} · ${detail.github.branch} · ${formatDate(detail.github.connected_at)}`
        : '아직 연결하지 않았어요 · 소유자가 공개 저장소를 연결해요',
    },
    {
      label: '첫 QA 실행',
      done: qaDone,
      sub: '멤버 초대 없이도 실행할 수 있어요 · 사용자가 직접 실행 (자동 실행 없음)',
      action: <SmallButton kind="ghost" icon="arrow-right" disabled title={lockedQa}>QA 준비하기</SmallButton>,
    },
  ]

  return (
    <>
      <PageHeader
        name={detail.name}
        description={description}
        actions={
          <>
            {!qaDone && (
              <button type="button" onClick={onInvite}
                className="flex h-10 flex-none items-center gap-2 whitespace-nowrap rounded-lg border border-qa-primary bg-white px-4 text-sm font-medium text-qa-primary-hover hover:bg-qa-primary/5">
                <Icon name="user-plus" className="h-5 w-5" />
                멤버 초대
              </button>
            )}
            {firstQaButton}
          </>
        }
      />

      {toast && (!qaDone || toast.kind === 'joined') && <ToastBanner toast={toast} detail={detail} alone={alone} />}

      <WorkflowStepper current={currentStep(detail)} />
      <FirstQaStatus detail={detail} openIssues={openIssues} cta={firstQaButton} />

      <div className="mt-6 grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
        <div className="flex min-w-0 flex-col gap-6">
          {!qaDone && <GettingStarted items={checklist} />}
          <QaQueueCard counts={c} />
        </div>
        <div className="flex min-w-0 flex-col gap-6">
          <ProjectInfoCard rows={[
            { label: '프로젝트', value: detail.name },
            { label: '생성일', value: formatDate(detail.created_at) },
            { label: 'QA 실행 준비 완료', value: readyAt ?? '준비 중' },
            { label: 'QA 실행 이력', value: qaDone ? '1건' : '없음 (0건)' },
            { label: '수정 담당 팀 배정 이력', value: totalIssues - c.needs_check > 0 ? '있음' : '없음' },
          ]} />
          <MembersCard projectId={detail.id} me={me.id ?? ''} ownerId={detail.owner_id}
            members={members} invitations={invitations} onInvite={onInvite} />
          <ConnectionsCard detail={detail} />
          <AnalysisCard run={run} mapping={mapping} connected={bothConnected} isOwner={isOwner} onOpen={onAnalysis} />
          {mapping && mapping.run && <MappingCard projectId={detail.id} mapping={mapping} />}
        </div>
      </div>

      {/* 프로젝트 삭제(AC26) — 시안에는 없지만 2026-11-07 결정대로 소유자에게만 맨 아래에 둔다 */}
      {isOwner && (
        <section className="mt-10 flex flex-wrap items-center justify-between gap-4 rounded-xl border border-qa-warning/40 bg-white p-6">
          <div>
            <h2 className="text-sm font-bold text-qa-warning">프로젝트 삭제</h2>
            <p className="mt-1 text-xs text-qa-text-sub">프로젝트와 그 안의 이슈·재검증·알림·초대·연결 정보를 모두 지워요. 되돌릴 수 없어요.</p>
          </div>
          <button type="button" onClick={onDelete}
            className="h-10 flex-none whitespace-nowrap rounded-lg border border-qa-warning bg-white px-4 text-sm font-medium text-qa-warning hover:bg-qa-warning/5">
            프로젝트 삭제
          </button>
        </section>
      )}
    </>
  )
}

function PageHeader({ name, description, actions }: { name: string; description: string; actions: React.ReactNode }) {
  return (
    <div className="flex items-end justify-between gap-4">
      <div className="min-w-0">
        <nav className="flex items-center gap-1 text-xs leading-4">
          <Link to="/app/projects" className="text-qa-text-sub hover:underline">모든 프로젝트</Link>
          <Icon name="chevron-right" className="h-3 w-3 text-qa-text-sub" />
          <span className="font-medium text-black">{name}</span>
        </nav>
        <h1 className="mt-2 text-[28px] font-bold leading-9 text-black">프로젝트 작업공간 홈</h1>
        {description && <p className="mt-2 text-sm leading-5 text-qa-text-sub">{description}</p>}
      </div>
      {actions && <div className="flex flex-none gap-2">{actions}</div>}
    </div>
  )
}

function ToastBanner({ toast, detail, alone }: { toast: Toast; detail: ProjectDetail; alone: boolean }) {
  let title: string
  let sub: string
  if (toast.kind === 'joined') {
    // 시안 문구 "참여 후 변경할 수 없어요"는 스펙과 달라 고쳤다 — 역할은 이후 소유자가 바꿀 수 있다(SPEC AC24)
    title = `‘${detail.name}’에 ${ROLE_LABELS[toast.role]} 역할로 참여했어요`
    sub = '프로젝트 역할은 담당 업무 분야를 나타내요. 이후 변경은 소유자가 멤버 관리에서 할 수 있고, QA 문제의 수정 담당 팀은 QA 결과에서 별도로 배정됩니다.'
  } else if (toast.kind === 'invited') {
    title = `멤버 ${toast.count}명을 초대했어요`
    sub = '수락하면 ‘참여 완료’로 표시돼요. QA 결과와 진행 상태에는 영향이 없으며, QA는 자동으로 실행되지 않아요.'
  } else {
    title = `‘${detail.name}’ 프로젝트를 시작했어요`
    const connected = detail.figma && detail.github
      ? 'Figma·Git 연결을 마쳤어요.'
      : 'Figma·Git 연결은 아직 끝나지 않았어요.'
    sub = `${connected}${alone ? ' 멤버는 아직 초대하지 않았어요 — 소유자 1명.' : ''} QA는 자동으로 실행되지 않아요 — 준비되면 [첫 QA 실행]을 눌러 시작하세요.`
  }
  return (
    <div role="status" className="mt-6 flex items-start gap-3 rounded-xl bg-qa-success/10 px-4 py-3">
      <Icon name="circle-check" className="mt-0 h-5 w-5 flex-none text-qa-success" />
      <div className="min-w-0">
        <div className="text-sm font-medium leading-[14px] text-black">{title}</div>
        <div className="mt-0.5 text-xs leading-[14px] text-qa-text-sub">{sub}</div>
      </div>
    </div>
  )
}
