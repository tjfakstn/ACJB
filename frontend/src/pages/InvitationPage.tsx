import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  acceptInvitation, ApiError, fetchMe, formatDate, googleLoginUrl, logoutUrl, previewInvitation, rememberAfterLogin,
  type InvitationPreview, type Me, type MemberRole,
} from '../lib/api'
import { AppHeader } from '../components/projects/AppHeader'
import { RoleSelect } from '../components/common/RoleSelect'
import { Spinner } from '../components/common/Spinner'

// 초대 메일의 수락 링크(/invitations/{token}) — 시안 U02-B2(확인·수락), U02-B3(만료).
// 시안과 다른 점(스펙에 맞춤): 역할은 초대한 사람이 정한 값을 보여주는 대신 여기서 본인이 고른다(SPEC AC18),
// [거절하기]·[초대 다시 요청하기]는 뺐다(거절 기록·재요청 API가 v1에 없다 — 그냥 두면 7일 뒤 만료).
type View =
  | { kind: 'loading' }
  | { kind: 'login' }
  | { kind: 'invalid' }
  | { kind: 'preview'; preview: InvitationPreview }

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
      navigate(`/app/projects/${project.id}`, { replace: true })
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
    <div className="min-h-screen bg-[#f5f7fa]">
      {me?.authenticated ? <AppHeader /> : <PlainHeader />}
      <main className="flex justify-center px-5 py-14">
        <div className="w-[560px] max-w-full rounded-2xl border border-qa-border bg-white p-7 shadow-[0_1px_2px_rgba(15,23,41,0.04),0_4px_12px_rgba(15,23,41,0.05)]">
          {view.kind === 'loading' && <div className="py-10 text-center text-sm text-qa-sub">초대를 확인하는 중…</div>}

          {view.kind === 'login' && (
            <>
              <Heading tone="primary" eyebrow="프로젝트 초대" title="초대를 확인하려면 로그인해 주세요" />
              <p className="mt-4 text-sm text-gray-600">초대받은 이메일과 같은 구글 계정으로 로그인하면 프로젝트 정보와 수락 버튼이 보여요.</p>
              <button type="button" onClick={login}
                className="mt-6 h-11 w-full rounded-lg bg-qa-primary text-sm font-semibold text-white hover:bg-qa-primary-hover">
                Google로 로그인
              </button>
            </>
          )}

          {view.kind === 'invalid' && (
            <>
              <Heading tone="danger" eyebrow="초대 링크 오류" title="초대 링크를 찾을 수 없어요" />
              <p className="mt-4 text-sm text-gray-600">링크가 잘못됐거나 더 이상 쓸 수 없는 초대예요. 메일의 링크를 다시 확인하거나, 초대한 사람에게 다시 보내 달라고 요청해 주세요.</p>
              <HomeButton />
            </>
          )}

          {view.kind === 'preview' && view.preview.status !== 'pending' && (
            <ClosedInvitation preview={view.preview} />
          )}

          {view.kind === 'preview' && view.preview.status === 'pending' && (() => {
            const p = view.preview
            const emailMatches = !!me?.email && me.email.toLowerCase() === p.email.toLowerCase()
            return (
              <>
                <Heading tone="primary" eyebrow="프로젝트 초대" title={`${p.invited_by_name}님이 프로젝트에 초대했어요`} />
                <div className="mt-5 rounded-xl border border-qa-border bg-qa-surface px-4 py-3">
                  <div className="text-sm font-bold text-gray-900">{p.project_name}</div>
                  {p.project_description && <div className="mt-1 text-xs leading-5 text-gray-600">{p.project_description}</div>}
                </div>
                <dl className="mt-5 grid grid-cols-[96px_1fr] gap-y-2.5 text-sm">
                  <dt className="text-gray-500">초대받은 이메일</dt>
                  <dd className="flex flex-wrap items-center gap-2 text-gray-900">
                    {p.email}
                    {emailMatches ? (
                      <span className="rounded-full bg-[#e8f5f3] px-2 py-0.5 text-[11px] font-medium text-qa-success">로그인 계정과 일치</span>
                    ) : (
                      <span className="rounded-full bg-[#fdecec] px-2 py-0.5 text-[11px] font-medium text-qa-warning">로그인 계정과 다름</span>
                    )}
                  </dd>
                  <dt className="text-gray-500">초대한 사람</dt>
                  <dd className="text-gray-900">{p.invited_by_name}</dd>
                  <dt className="text-gray-500">유효기간</dt>
                  <dd className="text-gray-900">{formatDate(p.expires_at)}까지</dd>
                </dl>

                {emailMatches ? (
                  <>
                    <div className="mt-6 text-sm font-semibold text-gray-900">이 프로젝트에서 나의 역할</div>
                    <div className="mt-2"><RoleSelect value={role} onChange={setRole} invalid={touched && role === null} /></div>
                    <div className={`mt-1.5 text-xs ${touched && role === null ? 'text-qa-warning' : 'text-qa-sub'}`}>
                      {touched && role === null ? '역할을 선택해 주세요' : '이 프로젝트에만 적용되는 역할이에요. 다른 프로젝트의 역할은 바뀌지 않아요.'}
                    </div>
                    {error && <div role="alert" className="mt-4 rounded-xl border border-[#f7c9c9] bg-[#fdecec] px-4 py-3 text-sm text-qa-warning">{error}</div>}
                    <button type="button" onClick={accept} disabled={accepting}
                      className="mt-6 inline-flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-qa-primary text-sm font-semibold text-white hover:bg-qa-primary-hover disabled:opacity-70">
                      {accepting && <Spinner />}
                      초대 수락하고 참여하기
                    </button>
                  </>
                ) : (
                  <div className="mt-6 rounded-xl border border-[#f7c9c9] bg-[#fdecec] px-4 py-3 text-sm text-gray-700">
                    지금 <b>{me?.email}</b>로 로그인돼 있어요. 초대는 <b>{p.email}</b> 계정으로만 수락할 수 있어요.
                  </div>
                )}
                <p className="mt-4 text-center text-xs text-qa-sub">
                  다른 계정으로 로그인했나요? <button type="button" onClick={switchAccount} className="font-medium text-qa-primary hover:underline">계정 전환</button>
                </p>
              </>
            )
          })()}
        </div>
      </main>
    </div>
  )
}

const CLOSED_TEXT: Record<string, { title: string; body: (p: InvitationPreview) => string }> = {
  expired: { title: '초대 링크가 만료되었어요', body: (p) => `이 초대는 ${formatDate(p.expires_at)}에 만료되었어요. 초대한 사람에게 다시 초대해 달라고 요청해 주세요.` },
  canceled: { title: '취소된 초대예요', body: () => '초대한 사람이 이 초대를 취소했거나 새 초대를 보냈어요. 가장 최근에 받은 메일의 링크를 확인해 주세요.' },
  accepted: { title: '이미 수락한 초대예요', body: () => '이 초대는 이미 수락되었어요. 프로젝트 목록에서 확인할 수 있어요.' },
}

function ClosedInvitation({ preview }: { preview: InvitationPreview }) {
  const text = CLOSED_TEXT[preview.status] ?? CLOSED_TEXT.expired
  return (
    <>
      <Heading tone="danger" eyebrow={preview.status === 'accepted' ? '수락 완료' : '초대 종료'} title={text.title} />
      <p className="mt-4 text-sm text-gray-600">{text.body(preview)}</p>
      <div className="mt-5 rounded-xl border border-qa-border bg-qa-surface px-4 py-3 text-sm font-bold text-gray-900">{preview.project_name}</div>
      <dl className="mt-4 grid grid-cols-[96px_1fr] gap-y-2 text-sm">
        <dt className="text-gray-500">초대받은 이메일</dt><dd>{preview.email}</dd>
        <dt className="text-gray-500">초대한 사람</dt><dd>{preview.invited_by_name}</dd>
        <dt className="text-gray-500">유효기간</dt><dd>{formatDate(preview.expires_at)}{preview.status === 'expired' ? ' 만료됨' : '까지'}</dd>
      </dl>
      <HomeButton />
    </>
  )
}

function Heading({ tone, eyebrow, title }: { tone: 'primary' | 'danger'; eyebrow: string; title: string }) {
  return (
    <div className="flex items-center gap-3">
      <span className={`flex h-9 w-9 items-center justify-center rounded-full text-sm font-bold ${tone === 'primary' ? 'bg-[#eef4ff] text-qa-primary' : 'bg-[#fdecec] text-qa-warning'}`}>
        {tone === 'primary' ? '✉' : '!'}
      </span>
      <div>
        <div className={`text-xs font-medium ${tone === 'primary' ? 'text-qa-primary' : 'text-qa-warning'}`}>{eyebrow}</div>
        <h1 className="text-base font-bold text-gray-900">{title}</h1>
      </div>
    </div>
  )
}

function HomeButton() {
  return (
    <Link to="/app/projects" className="mt-6 flex h-11 items-center justify-center rounded-lg border border-qa-primary text-sm font-semibold text-qa-primary hover:bg-[#eef4ff]">
      QAting 홈으로
    </Link>
  )
}

function PlainHeader() {
  return (
    <header className="flex h-16 items-center border-b border-qa-border bg-white px-12">
      <span className="text-[22px] font-extrabold tracking-tight text-qa-logo">QAting</span>
    </header>
  )
}
