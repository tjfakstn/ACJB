import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  acceptInvitation, ApiError, fetchMe, formatDate, googleLoginUrl, listMembers, listProjects, logoutUrl, previewInvitation,
  rememberAfterLogin, ROLE_LABELS, type InvitationPreview, type Me, type MemberRole,
} from '../lib/api'
import { AppHeader } from '../components/projects/AppHeader'
import { Icon } from '../components/common/Icon'
import { Spinner } from '../components/common/Spinner'
import { Overlay } from '../components/projects/WizardParts'
import { initialOf, ROLE_TAG } from '../components/common/roles'
import type { WorkspaceLocationState } from './WorkspacePage'

// 초대 메일의 수락 링크(/invitations/{token}) — 시안 GST-02 흐름.
//  GST-02·02S 역할 선택 → 02C 확정 확인 → 02D 참여 완료 → [프로젝트로 이동] GST-03(작업공간 + 참여 토스트)
//  예외: 02F 참여 확정 실패(다시 시도) · 02M 로그인 계정 불일치 · 02A 이미 참여 중 · 02I 유효하지 않은·취소된 링크 · 02X 만료
// 시안과 다른 점(스펙·API에 맞춤):
//  - "참여 후 변경할 수 없다"는 문구 — 본인은 못 바꾸지만 소유자는 바꿀 수 있다(SPEC AC24). 확인 창·완료 화면에 "변경은 소유자만"으로 적었다
//  - [거절하기]·[초대 다시 요청하기]는 뺐다 — 거절 기록·재요청 API가 v1에 없다(그냥 두면 7일 뒤 만료). 시안에도 "추가 개발"로 표시돼 있다
//  - "함께하는 멤버"·초대한 사람의 권한(소유자)은 뺐다 — 초대 미리보기 API가 주지 않는 값이라 지어내지 않는다
//  - 02A의 프로젝트·기존 역할: 미리보기에 프로젝트 id가 없어서, 내 프로젝트 중 이름·설명이 같은 것이 정확히 하나일 때만 연결한다
type View =
  | { kind: 'loading' }
  | { kind: 'login' }
  | { kind: 'invalid' }
  | { kind: 'preview'; preview: InvitationPreview }
  | { kind: 'joined'; preview: InvitationPreview; projectId: string; role: MemberRole }
  | { kind: 'already'; preview: InvitationPreview; projectId: string | null; role: MemberRole | null }
  | { kind: 'mismatch'; preview: InvitationPreview }

const ROLE_ORDER: MemberRole[] = ['planner', 'designer', 'frontend', 'backend']

/** 시안 GST-02 역할 카드 설명 */
const ROLE_DESC: Record<MemberRole, string> = {
  planner: '서비스 요구사항, 기능 명세 및 정책 등 기획 관련 업무를 담당합니다.',
  designer: 'UI/UX 디자인과 디자인 시스템 등 디자인 관련 업무를 담당합니다.',
  frontend: '사용자 인터페이스와 클라이언트 기능 구현을 담당합니다.',
  backend: 'API, 데이터 처리 및 서버 기능 구현을 담당합니다.',
}

/** 02A — 미리보기에 프로젝트 id가 없어 내 프로젝트에서 이름·설명으로 찾는다. 하나로 정해지지 않으면 연결하지 않는다 */
async function findMyProject(preview: InvitationPreview, me: Me): Promise<{ projectId: string | null; role: MemberRole | null }> {
  try {
    const matches = (await listProjects()).filter((p) => p.name === preview.project_name && (p.description ?? null) === (preview.project_description ?? null))
    if (matches.length !== 1) return { projectId: null, role: null }
    const mine = (await listMembers(matches[0].id)).find((m) => m.id === me.id)
    return { projectId: matches[0].id, role: mine?.role ?? null }
  } catch {
    return { projectId: null, role: null }
  }
}

export function InvitationPage() {
  const { token = '' } = useParams()
  const navigate = useNavigate()
  const [me, setMe] = useState<Me | null>(null)
  const [view, setView] = useState<View>({ kind: 'loading' })
  const [role, setRole] = useState<MemberRole | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [accepting, setAccepting] = useState(false)
  const [failed, setFailed] = useState(false)

  const load = useCallback(async () => {
    const result = await fetchMe()
    setMe(result)
    if (!result.authenticated) {
      setView({ kind: 'login' })
      return
    }
    let preview: InvitationPreview
    try {
      preview = await previewInvitation(token)
    } catch {
      setView({ kind: 'invalid' })
      return
    }
    const emailMatches = !!result.email && result.email.toLowerCase() === preview.email.toLowerCase()
    if (preview.status === 'pending' && !emailMatches) {
      setView({ kind: 'mismatch', preview })
    } else if (preview.status === 'accepted' && emailMatches) {
      setView({ kind: 'already', preview, ...(await findMyProject(preview, result)) })
    } else {
      setView({ kind: 'preview', preview })
    }
  }, [token])

  useEffect(() => {
    // 첫 화면 데이터를 서버에서 가져오는 동기화 — 상태 변경은 응답이 온 뒤에만 일어난다
    void Promise.resolve().then(load)
  }, [load])

  const login = () => {
    // 서버는 로그인 뒤 항상 '/'로 보낸다 — 이 초대 화면으로 돌아오도록 적어 둔다
    rememberAfterLogin(`/invitations/${token}`)
    window.location.href = googleLoginUrl()
  }

  const switchAccount = () => {
    rememberAfterLogin(`/invitations/${token}`)
    window.location.href = logoutUrl()
  }

  const accept = async () => {
    if (role === null || view.kind !== 'preview' || !me) return
    const preview = view.preview
    setAccepting(true)
    try {
      const project = await acceptInvitation(token, role)
      setFailed(false)
      setView({ kind: 'joined', preview, projectId: project.id, role })
    } catch (e) {
      if (e instanceof ApiError && e.code === 'INVITATION_CLOSED') {
        await load() // 그사이 만료·취소됐다 — 닫힌 초대 화면으로 바꾼다
      } else if (e instanceof ApiError && e.status === 403) {
        setView({ kind: 'mismatch', preview })
      } else if (e instanceof ApiError && e.code === 'ALREADY_MEMBER') {
        setView({ kind: 'already', preview, ...(await findMyProject(preview, me)) })
      } else {
        setFailed(true) // GST-02F — 고른 역할은 그대로 두고 다시 시도
      }
    } finally {
      setAccepting(false)
      setConfirming(false)
    }
  }

  const toProject = (projectId: string | null, joinedRole?: MemberRole) => {
    if (!projectId) {
      navigate('/app/projects')
      return
    }
    const state: WorkspaceLocationState | null = joinedRole ? { joinedRole } : null
    navigate(`/app/projects/${projectId}`, { replace: true, state })
  }

  return (
    <div className="min-h-screen bg-qa-surface">
      {me?.authenticated ? <AppHeader /> : <PlainHeader />}
      <main className="flex justify-center px-4 pb-14 pt-12">
        <div className="flex w-[560px] max-w-full flex-col gap-6 rounded-2xl border border-qa-border bg-white p-8">
          {view.kind === 'loading' && <div className="py-10 text-center text-sm text-qa-sub">초대를 확인하는 중…</div>}

          {view.kind === 'login' && (
            <>
              <StatusHeading tone="primary" icon="mail" eyebrow="프로젝트 초대" title="초대를 확인하려면 로그인해 주세요" />
              <Body>초대받은 이메일과 같은 구글 계정으로 로그인하면 프로젝트 정보와 역할 선택 화면이 보여요.</Body>
              <button type="button" onClick={login} className={PRIMARY}>Google로 로그인</button>
            </>
          )}

          {/* GST-02I — 링크를 찾을 수 없거나 취소된 초대 */}
          {(view.kind === 'invalid' || (view.kind === 'preview' && view.preview.status === 'canceled')) && (
            <>
              <StatusHeading tone="danger" icon="alert-circle" eyebrow="접근 불가" title="유효하지 않은 초대 링크입니다." />
              <Body>링크가 잘못되었거나 취소된 초대입니다. 프로젝트 소유자에게 초대 링크를 다시 요청해 주세요.</Body>
              <Link to="/app/projects" className={SECONDARY}>QAting 홈으로</Link>
              <Note>이 링크로는 프로젝트에 참여할 수 없습니다.</Note>
            </>
          )}

          {/* GST-02X — 만료. 다른 계정으로 수락된 초대도 이 링크로는 참여할 수 없다는 점에서 같은 틀을 쓴다 */}
          {view.kind === 'preview' && (view.preview.status === 'expired' || view.preview.status === 'accepted') && (
            <>
              <StatusHeading tone="danger" icon="clock" eyebrow={view.preview.status === 'expired' ? '초대 만료' : '사용된 초대'}
                title={view.preview.status === 'expired' ? '초대 링크가 만료되었어요' : '이미 사용된 초대 링크예요'} />
              <Body>{view.preview.status === 'expired'
                ? `이 초대는 ${formatDate(view.preview.expires_at)}에 만료되었어요. 초대한 사람에게 초대를 다시 요청해 주세요.`
                : '이 초대는 이미 수락되었어요. 초대받은 계정으로 로그인하면 프로젝트 목록에서 확인할 수 있어요.'}</Body>
              <ProjectBox preview={view.preview} />
              <dl className={DL}>
                <Kv label="초대받은 이메일"><Value>{view.preview.email}</Value></Kv>
                <Kv label="초대한 사람"><Value>{view.preview.invited_by_name}</Value></Kv>
                <Kv label="유효기간">
                  <Value>{formatDate(view.preview.expires_at)}{view.preview.status === 'expired' ? ' 만료됨' : '까지'}</Value>
                </Kv>
              </dl>
              <Link to="/app/projects" className={SECONDARY}>QAting 홈으로</Link>
            </>
          )}

          {/* GST-02M — 로그인 계정 불일치 */}
          {view.kind === 'mismatch' && (
            <>
              <StatusHeading tone="primary" icon="alert-circle" eyebrow="계정 확인 필요" title="초대받은 이메일과 로그인 계정이 달라요" />
              <Body>이 초대는 아래 이메일로 발송되었어요. 초대받은 이메일의 계정으로 로그인하면 역할을 선택하고 참여할 수 있습니다.</Body>
              <ProjectBox preview={view.preview} />
              <dl className={DL}>
                <Kv label="초대받은 이메일"><Value>{view.preview.email}</Value></Kv>
                <Kv label="현재 로그인 계정"><Value>{me?.email ?? '-'}</Value></Kv>
              </dl>
              <div className="grid grid-cols-2 gap-2">
                <Link to="/app/projects" className={SECONDARY}>QAting 홈으로</Link>
                <button type="button" onClick={switchAccount} className={SECONDARY}>다른 계정으로 로그인</button>
              </div>
            </>
          )}

          {/* GST-02A — 이미 참여 중 */}
          {view.kind === 'already' && (
            <>
              <StatusHeading tone="primary" icon="circle-check" eyebrow="이미 참여 중" title="이미 이 프로젝트에 참여하고 있어요" />
              <Body>기존 프로젝트 역할이 그대로 유지됩니다. 프로젝트로 이동해 작업을 이어가세요.</Body>
              <ProjectBox preview={view.preview} />
              <dl className={DL}>
                <Kv label="초대받은 이메일"><Value>{view.preview.email}</Value></Kv>
                <Kv label="초대한 사람"><Value>{view.preview.invited_by_name}</Value></Kv>
                {view.projectId && <Kv label="프로젝트 역할"><RoleBadge role={view.role} /></Kv>}
              </dl>
              <button type="button" onClick={() => toProject(view.projectId)} className={PRIMARY}>
                {view.projectId ? '프로젝트로 이동' : '프로젝트 목록으로 이동'}
              </button>
              <Note>이미 참여한 프로젝트라 역할을 다시 선택하지 않아요. 역할 변경은 소유자가 멤버 관리에서 할 수 있어요.</Note>
            </>
          )}

          {/* GST-02D — 참여 완료 */}
          {view.kind === 'joined' && (
            <>
              <StatusHeading tone="success" icon="circle-check" eyebrow="참여 완료" title="프로젝트 참여가 완료되었습니다." />
              <Body>{ROLE_LABELS[view.role]} 역할로 프로젝트에 참여했습니다.</Body>
              <ProjectBox preview={view.preview} />
              <dl className={DL}>
                <Kv label="초대받은 이메일"><Value>{view.preview.email}</Value></Kv>
                <Kv label="초대한 사람"><Value>{view.preview.invited_by_name}</Value></Kv>
                <Kv label="프로젝트 역할"><RoleBadge role={view.role} /></Kv>
              </dl>
              <button type="button" onClick={() => toProject(view.projectId, view.role)} className={PRIMARY} autoFocus>프로젝트로 이동</button>
              <Note>프로젝트 역할은 작업공간에서 확인할 수 있어요. 변경은 소유자만 할 수 있어요.</Note>
            </>
          )}

          {/* GST-02·02S·02F — 역할 선택 */}
          {view.kind === 'preview' && view.preview.status === 'pending' && (() => {
            const p = view.preview
            return (
              <>
                {failed && (
                  <div role="alert" className="flex gap-3 rounded-xl bg-qa-warning/10 px-4 py-3">
                    <Icon name="alert" className="h-5 w-5 flex-none text-qa-warning" />
                    <div className="min-w-0">
                      <div className="text-sm font-medium leading-5 text-black">프로젝트 참여를 완료하지 못했어요</div>
                      <div className="text-xs leading-4 text-qa-text-sub">일시적인 오류로 참여가 확정되지 않았어요. 선택한 역할은 유지되니 다시 시도해 주세요.</div>
                    </div>
                  </div>
                )}
                <div className="flex items-center gap-3">
                  <span className="flex h-8 w-8 flex-none items-center justify-center rounded-full bg-qa-primary/10 text-xs font-medium text-qa-primary">{initialOf(p.invited_by_name)}</span>
                  <div className="min-w-0">
                    <div className="text-xs font-medium leading-4 text-qa-primary">프로젝트 초대</div>
                    <h1 className="text-xl font-bold leading-7 text-black">{p.invited_by_name}님이 프로젝트에 초대했어요</h1>
                  </div>
                </div>
                <ProjectBox preview={p} />
                <dl className={DL}>
                  <Kv label="초대받은 이메일">
                    <Value>{p.email}</Value>
                    <span className="inline-flex h-[18px] items-center gap-1 rounded-full bg-qa-success/10 px-2 text-xs font-medium text-qa-success">
                      <Icon name="check" className="h-3 w-3" />로그인 계정과 일치
                    </span>
                  </Kv>
                  <Kv label="초대한 사람"><Value>{p.invited_by_name}</Value></Kv>
                  <Kv label="유효기간"><Value>{formatDate(p.expires_at)}까지</Value></Kv>
                </dl>

                {/* 라디오 카드 2×2, 한 개만 고른다. 확정 전에는 다른 카드로 바꿀 수 있다 */}
                <section>
                  <div id="role-label" className="text-xs font-medium leading-4 text-qa-text-sub">이 프로젝트에서 맡을 역할을 선택해 주세요 <span className="text-qa-warning">*</span></div>
                  <p className="mt-[11px] text-xs leading-4 text-qa-text-sub">프로젝트 역할은 권한 등급이 아닌 담당 업무 분야입니다. 한 가지만 선택할 수 있어요.</p>
                  <div role="radiogroup" aria-labelledby="role-label" className="mt-[11px] grid grid-cols-2 gap-3">
                    {ROLE_ORDER.map((r) => {
                      const selected = role === r
                      return (
                        <button key={r} type="button" role="radio" aria-checked={selected} onClick={() => setRole(r)} disabled={accepting}
                          className={`flex min-h-[90px] flex-col items-start rounded-lg border p-[14px] text-left ${
                            selected ? 'border-qa-primary-hover bg-qa-primary/10' : 'border-qa-border bg-white hover:bg-gray-50'}`}>
                          <span className="flex items-center gap-2">
                            <span className={`flex h-5 w-5 flex-none items-center justify-center rounded-full border ${selected ? 'border-qa-primary' : 'border-qa-sub'}`}>
                              {selected && <span className="h-1.5 w-1.5 rounded-full bg-qa-primary" />}
                            </span>
                            <RoleBadge role={r} />
                          </span>
                          <span className="mt-2 text-xs leading-4 text-qa-text-sub">{ROLE_DESC[r]}</span>
                        </button>
                      )
                    })}
                  </div>
                  <div className="mt-3 flex gap-3 rounded-xl bg-qa-primary/10 px-4 py-3">
                    <Icon name="info" className="h-5 w-5 flex-none text-qa-primary-hover" />
                    <div className="min-w-0">
                      <div className="text-sm font-medium leading-5 text-black">역할은 참여 전에 한 번만 선택할 수 있어요</div>
                      <div className="text-xs leading-4 text-qa-text-sub">선택한 역할은 참여 후 직접 바꿀 수 없어요. 역할을 확인한 뒤 참여해 주세요.</div>
                    </div>
                  </div>
                </section>
                <div className="flex flex-col gap-3">
                  {failed ? (
                    // GST-02F [다시 시도] — 확인 창 없이 같은 역할로 바로 다시 보낸다
                    <button type="button" onClick={() => void accept()} disabled={accepting || role === null} className={PRIMARY} aria-busy={accepting}>
                      {accepting ? <Spinner /> : <Icon name="refresh" className="h-5 w-5" />}다시 시도
                    </button>
                  ) : (
                    <button type="button" onClick={() => setConfirming(true)} disabled={role === null || accepting} className={PRIMARY}>
                      이 역할로 참여하기
                    </button>
                  )}
                  {role === null && <p className="text-center text-xs leading-4 text-qa-text-sub">역할을 선택하면 [이 역할로 참여하기]가 활성화됩니다.</p>}
                </div>
                <Note>
                  다른 계정으로 로그인되어 있나요?{' '}
                  <button type="button" onClick={switchAccount} className="font-medium text-qa-primary-hover hover:underline">계정 전환</button>
                </Note>
                {confirming && role && (
                  // GST-02C — 확정 전 한 번 더 확인. 수락과 역할 저장은 한 번의 요청(accept)으로 처리된다
                  <Overlay width={480} onClose={accepting ? undefined : () => setConfirming(false)}>
                    <h2 id="create-project-title" className="text-xl font-bold leading-7 text-black">선택한 역할로 참여하시겠습니까?</h2>
                    <p className="mt-1 text-sm leading-5 text-qa-text-sub">참여 후에는 소유자만 역할을 바꿀 수 있어요.</p>
                    <div className="mt-4 flex items-center gap-3 rounded-xl border border-qa-border bg-qa-surface px-4 py-[13px]">
                      <span className="text-xs font-medium text-qa-text-sub">선택한 역할</span>
                      <RoleBadge role={role} />
                    </div>
                    <div className="mt-4 grid grid-cols-2 gap-2">
                      <button type="button" onClick={() => setConfirming(false)} disabled={accepting} className={SECONDARY}>다시 선택하기</button>
                      <button type="button" onClick={() => void accept()} disabled={accepting} className={PRIMARY} aria-busy={accepting} autoFocus>
                        {accepting && <Spinner />}참여 확정
                      </button>
                    </div>
                  </Overlay>
                )}
              </>
            )
          })()}
        </div>
      </main>
    </div>
  )
}

const PRIMARY = 'inline-flex h-10 w-full items-center justify-center gap-2 rounded-lg bg-qa-primary-hover text-sm font-medium text-white hover:bg-[#0062bd] disabled:bg-[#e8e8e8] disabled:text-qa-sub'
const SECONDARY = 'flex h-10 w-full items-center justify-center rounded-lg border border-qa-primary bg-white text-sm font-medium text-qa-primary-hover hover:bg-[#eef4ff] disabled:border-qa-border disabled:text-qa-sub'
const DL = 'flex flex-col gap-2.5 text-sm leading-5'

const TONES = {
  primary: { mark: 'bg-qa-primary/10 text-qa-primary', eyebrow: 'text-qa-primary' },
  success: { mark: 'bg-qa-success/10 text-qa-success', eyebrow: 'text-qa-success' },
  danger: { mark: 'bg-qa-warning/10 text-qa-warning', eyebrow: 'text-qa-warning' },
} as const

/** 시안 GST-02D·02M·02A·02I·02X 머리 — 40px 상태 표시 + 작은 머리말 + 제목 */
function StatusHeading({ tone, icon, eyebrow, title }: { tone: keyof typeof TONES; icon: string; eyebrow: string; title: string }) {
  return (
    <div className="flex items-center gap-3">
      <span className={`flex h-10 w-10 flex-none items-center justify-center rounded-full ${TONES[tone].mark}`}><Icon name={icon} className="h-5 w-5" /></span>
      <div className="min-w-0">
        <div className={`text-xs font-medium leading-4 ${TONES[tone].eyebrow}`}>{eyebrow}</div>
        <h1 className="text-xl font-bold leading-7 text-black">{title}</h1>
      </div>
    </div>
  )
}

function Body({ children }: { children: ReactNode }) {
  return <p className="text-sm leading-5 text-qa-text-sub">{children}</p>
}

function Note({ children }: { children: ReactNode }) {
  return <p className="text-xs leading-4 text-qa-sub">{children}</p>
}

function RoleBadge({ role }: { role: MemberRole | null }) {
  if (!role) {
    return <span className="flex h-[22px] items-center rounded-full border border-qa-border bg-qa-surface px-2 text-xs font-medium text-qa-text-sub">역할 미지정</span>
  }
  const t = ROLE_TAG[role]
  return (
    <span className={`flex h-[22px] items-center gap-1.5 rounded-full px-2 text-xs font-medium ${t.bg} ${t.text}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${t.dot}`} />{ROLE_LABELS[role]}
    </span>
  )
}

function ProjectBox({ preview }: { preview: InvitationPreview }) {
  return (
    <div className="rounded-xl border border-qa-border bg-qa-surface p-4">
      <div className="text-base font-bold leading-6 text-black">{preview.project_name}</div>
      {preview.project_description && <div className="mt-1 text-sm leading-5 text-qa-text-sub">{preview.project_description}</div>}
    </div>
  )
}

function Kv({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-center gap-4">
      <dt className="w-28 flex-none text-qa-text-sub">{label}</dt>
      <dd className="flex min-w-0 flex-wrap items-center gap-2">{children}</dd>
    </div>
  )
}

function Value({ children }: { children: ReactNode }) {
  return <span className="font-medium text-black">{children}</span>
}

function PlainHeader() {
  return (
    <header className="flex h-16 items-center border-b border-qa-border bg-white px-8">
      <span className="text-xl font-bold leading-7 text-qa-logo">QAting</span>
    </header>
  )
}
