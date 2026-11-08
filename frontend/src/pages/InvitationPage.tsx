import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  acceptInvitation, ApiError, fetchMe, formatDate, googleLoginUrl, logoutUrl, previewInvitation, rememberAfterLogin,
  ROLE_LABELS, type InvitationPreview, type Me, type MemberRole,
} from '../lib/api'
import { AppHeader } from '../components/projects/AppHeader'
import { Icon } from '../components/common/Icon'
import { Spinner } from '../components/common/Spinner'
import { initialOf, ROLE_TAG } from '../components/common/roles'
import type { WorkspaceLocationState } from './WorkspacePage'

// 초대 메일의 수락 링크(/invitations/{token}) — 시안 GST-02(확인·수락), GST-02X(만료). 수락하면 GST-03(작업공간 + 참여 토스트).
// 시안과 다른 점(스펙에 맞춤):
//  - 역할: 시안은 초대한 사람이 정한 역할을 보여 주지만, 스펙은 여기서 본인이 고른다(SPEC AC18) — "나의 역할" 상자 안에서 고른다
//  - [거절하기]·[초대 다시 요청하기]는 뺐다 — 거절 기록·재요청 API가 v1에 없다(그냥 두면 7일 뒤 만료). 시안에도 "추가 개발"로 표시돼 있다
//  - "함께하는 멤버"·초대한 사람의 권한(소유자)은 뺐다 — 초대 미리보기 API가 주지 않는 값이라 지어내지 않는다
type View =
  | { kind: 'loading' }
  | { kind: 'login' }
  | { kind: 'invalid' }
  | { kind: 'preview'; preview: InvitationPreview }

const ROLE_ORDER: MemberRole[] = ['planner', 'designer', 'frontend', 'backend']

/** 시안 GST-02 "나의 역할" 설명 — 디자인은 시안 문구, 나머지는 시안 역할 안내(INV·MEM)의 업무 분야로 같은 형식 */
const ROLE_DESC: Record<MemberRole, string> = {
  planner: '요구사항·화면 흐름·정책 정의를 담당해요. QA 결과에서 기획 관련 문제를 확인하고 팀과 협업할 수 있어요.',
  designer: 'Figma 디자인과 디자인 시스템을 담당해요. QA 결과에서 디자인 관련 문제를 확인하고 팀과 협업할 수 있어요.',
  frontend: 'UI 구현과 컴포넌트 코드를 담당해요. QA 결과에서 화면 구현 관련 문제를 확인하고 팀과 협업할 수 있어요.',
  backend: 'API·데이터·서버 로직을 담당해요. QA 결과에서 서버 관련 문제를 확인하고 팀과 협업할 수 있어요.',
}

const ROLE_POINTS = [
  '이 프로젝트에만 적용되는 역할이에요. 다른 프로젝트의 역할은 바뀌지 않아요.',
  '접근 권한(소유자·멤버)과는 별개예요.',
  'QA 문제의 수정 담당 팀은 프로젝트에서 사람이 직접 배정해요.',
]

export function InvitationPage() {
  const { token = '' } = useParams()
  const navigate = useNavigate()
  const [me, setMe] = useState<Me | null>(null)
  const [view, setView] = useState<View>({ kind: 'loading' })
  const [role, setRole] = useState<MemberRole | null>(null)
  const [touched, setTouched] = useState(false)
  const [accepting, setAccepting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    const result = await fetchMe()
    setMe(result)
    if (!result.authenticated) {
      setView({ kind: 'login' })
      return
    }
    try {
      setView({ kind: 'preview', preview: await previewInvitation(token) })
    } catch {
      setView({ kind: 'invalid' })
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
    setTouched(true)
    if (role === null) return
    setAccepting(true)
    setError(null)
    try {
      const project = await acceptInvitation(token, role)
      const state: WorkspaceLocationState = { joinedRole: role }
      navigate(`/app/projects/${project.id}`, { replace: true, state })
    } catch (e) {
      if (e instanceof ApiError && e.code === 'INVITATION_CLOSED') {
        await load() // 그사이 만료·취소됐다 — 닫힌 초대 화면으로 바꾼다
      } else if (e instanceof ApiError && e.status === 403) {
        setError('초대받은 이메일과 다른 계정으로 로그인했어요. 초대받은 구글 계정으로 다시 로그인해 주세요.')
      } else if (e instanceof ApiError && e.code === 'ALREADY_MEMBER') {
        setError('이미 이 프로젝트의 멤버예요. 프로젝트 목록에서 확인해 주세요.')
      } else {
        setError(e instanceof ApiError ? e.message : '수락하지 못했어요. 잠시 뒤 다시 시도해 주세요.')
      }
    } finally {
      setAccepting(false)
    }
  }

  return (
    <div className="min-h-screen bg-qa-surface">
      {me?.authenticated ? <AppHeader /> : <PlainHeader />}
      <main className="flex justify-center px-4 pb-14 pt-12">
        <div className="flex w-[560px] max-w-full flex-col gap-6 rounded-2xl border border-qa-border bg-white p-8">
          {view.kind === 'loading' && <div className="py-10 text-center text-sm text-qa-sub">초대를 확인하는 중…</div>}

          {view.kind === 'login' && (
            <>
              <Heading mark={<Mark tone="primary"><Icon name="mail" className="h-4 w-4" /></Mark>} eyebrow="프로젝트 초대" tone="primary"
                title="초대를 확인하려면 로그인해 주세요" />
              <p className="text-sm leading-5 text-qa-text-sub">초대받은 이메일과 같은 구글 계정으로 로그인하면 프로젝트 정보와 수락 버튼이 보여요.</p>
              <button type="button" onClick={login} className={PRIMARY}>Google로 로그인</button>
            </>
          )}

          {view.kind === 'invalid' && (
            <>
              <Heading mark={<Mark tone="danger"><Icon name="alert" className="h-5 w-5" /></Mark>} eyebrow="초대 링크 오류" tone="danger"
                title="초대 링크를 찾을 수 없어요" />
              <p className="text-sm leading-5 text-qa-text-sub">링크가 잘못됐거나 더 이상 쓸 수 없는 초대예요. 메일의 링크를 다시 확인하거나, 초대한 사람에게 다시 보내 달라고 요청해 주세요.</p>
              <Link to="/app/projects" className={SECONDARY}>QAting 홈으로</Link>
            </>
          )}

          {view.kind === 'preview' && view.preview.status !== 'pending' && <ClosedInvitation preview={view.preview} />}

          {view.kind === 'preview' && view.preview.status === 'pending' && (() => {
            const p = view.preview
            const emailMatches = !!me?.email && me.email.toLowerCase() === p.email.toLowerCase()
            const missing = touched && role === null
            return (
              <>
                {/* GST-02 Inviter */}
                <Heading tone="primary" eyebrow="프로젝트 초대" title={`${p.invited_by_name}님이 프로젝트에 초대했어요`}
                  mark={<span className="flex h-8 w-8 flex-none items-center justify-center rounded-full bg-qa-primary/10 text-xs font-medium text-qa-primary">{initialOf(p.invited_by_name)}</span>} />
                <ProjectBox name={p.project_name} description={p.project_description} />
                <dl className="flex flex-col gap-2.5 text-sm leading-5">
                  <Kv label="초대받은 이메일">
                    <span className="font-medium text-black">{p.email}</span>
                    {emailMatches ? (
                      <span className="inline-flex h-[18px] items-center gap-1 rounded-full bg-qa-success/10 px-2 text-xs font-medium text-qa-success">
                        <Icon name="check" className="h-3 w-3" />로그인 계정과 일치
                      </span>
                    ) : (
                      <span className="inline-flex h-[18px] items-center gap-1 rounded-full bg-qa-warning/10 px-2 text-xs font-medium text-qa-warning">
                        <Icon name="alert-circle" className="h-3 w-3" />로그인 계정과 다름
                      </span>
                    )}
                  </Kv>
                  <Kv label="초대한 사람"><span className="font-medium text-black">{p.invited_by_name}</span></Kv>
                  <Kv label="유효기간"><span className="font-medium text-black">{formatDate(p.expires_at)}까지</span></Kv>
                </dl>

                {emailMatches ? (
                  <>
                    {/* GST-02 My Role — 시안은 배정된 역할 태그, 여기서는 네 역할 중 하나를 고른다 */}
                    <section className={`rounded-xl border p-4 ${role ? ROLE_TAG[role].box : missing ? 'border-qa-warning/40 bg-qa-warning/[0.04]' : 'border-qa-border bg-qa-surface'}`}>
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-xs font-medium text-qa-text-sub">이 프로젝트에서 나의 역할 <span className="text-qa-warning">*</span></span>
                        <div role="radiogroup" aria-label="이 프로젝트에서 나의 역할" className="flex flex-wrap gap-1.5">
                          {ROLE_ORDER.map((r) => {
                            const t = ROLE_TAG[r]
                            const selected = role === r
                            return (
                              <button key={r} type="button" role="radio" aria-checked={selected} onClick={() => setRole(r)}
                                className={`flex h-[22px] items-center gap-1.5 rounded-full px-2 text-xs font-medium ${
                                  selected ? `${t.bg} ${t.text} ring-1 ring-current` : 'border border-qa-border bg-white text-qa-text-sub hover:bg-gray-50'}`}>
                                <span className={`h-1.5 w-1.5 rounded-full ${selected ? t.dot : 'bg-qa-sub'}`} />{ROLE_LABELS[r]}
                              </button>
                            )
                          })}
                        </div>
                      </div>
                      <p className={`mt-2.5 text-sm leading-5 ${role ? 'text-black' : missing ? 'text-qa-warning' : 'text-qa-text-sub'}`}>
                        {role ? ROLE_DESC[role] : missing ? '역할을 선택해 주세요.' : '이 프로젝트에서 맡을 업무 분야를 골라 주세요. 수락할 때 한 번 고르고, 이후 변경은 소유자가 해요.'}
                      </p>
                      <ul className="mt-2.5 flex flex-col gap-2.5">
                        {ROLE_POINTS.map((point) => (
                          <li key={point} className="flex items-center gap-2 text-xs leading-[14px] text-qa-text-sub">
                            <Icon name="check" className={`h-3.5 w-3.5 flex-none ${role ? ROLE_TAG[role].text : 'text-qa-sub'}`} />{point}
                          </li>
                        ))}
                      </ul>
                    </section>
                    {error && <div role="alert" className="rounded-xl bg-qa-warning/10 px-4 py-3 text-sm text-black">{error}</div>}
                    <button type="button" onClick={accept} disabled={accepting} className={PRIMARY} aria-busy={accepting}>
                      {accepting && <Spinner />}초대 수락하고 참여하기
                    </button>
                  </>
                ) : (
                  <div role="alert" className="rounded-xl bg-qa-warning/10 px-4 py-3 text-sm leading-5 text-black">
                    지금 <b>{me?.email}</b>로 로그인돼 있어요. 초대는 <b>{p.email}</b> 계정으로만 수락할 수 있어요.
                  </div>
                )}
                <p className="text-xs leading-4 text-qa-sub">
                  다른 계정으로 로그인되어 있나요?{' '}
                  <button type="button" onClick={switchAccount} className="font-medium text-qa-primary-hover hover:underline">계정 전환</button>
                </p>
              </>
            )
          })()}
        </div>
      </main>
    </div>
  )
}

const PRIMARY = 'inline-flex h-10 w-full items-center justify-center gap-2 rounded-lg bg-qa-primary-hover text-sm font-medium text-white hover:bg-[#0062bd] disabled:bg-[#e8e8e8] disabled:text-qa-sub'
const SECONDARY = 'flex h-10 w-full items-center justify-center rounded-lg border border-qa-primary bg-white text-sm font-medium text-qa-primary-hover hover:bg-[#eef4ff]'

const CLOSED_TEXT: Record<string, { eyebrow: string; title: string; body: (p: InvitationPreview) => string }> = {
  expired: {
    eyebrow: '초대 만료', title: '초대 링크가 만료되었어요',
    body: (p) => `이 초대는 ${formatDate(p.expires_at)}에 만료되었어요. 초대한 사람에게 초대를 다시 요청해 주세요.`,
  },
  canceled: {
    eyebrow: '초대 취소', title: '취소된 초대예요',
    body: () => '초대한 사람이 이 초대를 취소했거나 새 초대를 보냈어요. 가장 최근에 받은 메일의 링크를 확인해 주세요.',
  },
  accepted: {
    eyebrow: '수락 완료', title: '이미 수락한 초대예요',
    body: () => '이 초대는 이미 수락되었어요. 프로젝트 목록에서 확인할 수 있어요.',
  },
}

/** GST-02X(만료) — 취소·수락 완료도 같은 틀 */
function ClosedInvitation({ preview }: { preview: InvitationPreview }) {
  const text = CLOSED_TEXT[preview.status] ?? CLOSED_TEXT.expired
  const accepted = preview.status === 'accepted'
  return (
    <>
      <Heading tone={accepted ? 'primary' : 'danger'} eyebrow={text.eyebrow} title={text.title}
        mark={<Mark tone={accepted ? 'primary' : 'danger'} large><Icon name={accepted ? 'circle-check' : 'clock'} className="h-5 w-5" /></Mark>} />
      <p className="text-sm leading-5 text-qa-text-sub">{text.body(preview)}</p>
      <ProjectBox name={preview.project_name} description={preview.project_description} />
      <dl className="flex flex-col gap-2.5 text-sm leading-5">
        <Kv label="초대받은 이메일"><span className="font-medium text-black">{preview.email}</span></Kv>
        <Kv label="초대한 사람"><span className="font-medium text-black">{preview.invited_by_name}</span></Kv>
        <Kv label="유효기간">
          <span className="font-medium text-black">{formatDate(preview.expires_at)}{preview.status === 'expired' ? ' 만료됨' : '까지'}</span>
        </Kv>
      </dl>
      <Link to="/app/projects" className={SECONDARY}>QAting 홈으로</Link>
    </>
  )
}

function Heading({ mark, tone, eyebrow, title }: { mark: ReactNode; tone: 'primary' | 'danger'; eyebrow: string; title: string }) {
  return (
    <div className="flex items-center gap-3">
      {mark}
      <div className="min-w-0">
        <div className={`text-xs font-medium leading-4 ${tone === 'primary' ? 'text-qa-primary' : 'text-qa-warning'}`}>{eyebrow}</div>
        <h1 className="text-xl font-bold leading-7 text-black">{title}</h1>
      </div>
    </div>
  )
}

function Mark({ tone, large, children }: { tone: 'primary' | 'danger'; large?: boolean; children: ReactNode }) {
  return (
    <span className={`flex flex-none items-center justify-center rounded-full ${large ? 'h-10 w-10' : 'h-8 w-8'} ${
      tone === 'primary' ? 'bg-qa-primary/10 text-qa-primary' : 'bg-qa-warning/10 text-qa-warning'}`}>
      {children}
    </span>
  )
}

function ProjectBox({ name, description }: { name: string; description: string | null }) {
  return (
    <div className="rounded-xl border border-qa-border bg-qa-surface p-4">
      <div className="text-base font-bold leading-6 text-black">{name}</div>
      {description && <div className="mt-1 text-sm leading-5 text-qa-text-sub">{description}</div>}
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

function PlainHeader() {
  return (
    <header className="flex h-16 items-center border-b border-qa-border bg-white px-8">
      <span className="text-xl font-bold leading-7 text-qa-logo">QAting</span>
    </header>
  )
}
