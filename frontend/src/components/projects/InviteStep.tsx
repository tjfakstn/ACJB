import { useState } from 'react'
import {
  ApiError, inviteMember, resendInvitation, roleLabel, type Invitation, type ProjectMember,
} from '../../lib/api'
import { Icon } from '../common/Icon'
import { Spinner } from '../common/Spinner'
import { CloseButton, Stepper } from './WizardParts'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from './wizardStyles'

// 멤버 초대 흐름 — 두 곳에서 같은 화면을 쓴다.
//  - wizard: 새 프로젝트 마법사 2단계. 시안 INV-01(입력) → INV-03V(검증 오류) → INV-04(초대 확인) → INV-05(초대 요청 완료)
//  - manage: 운영 중인 프로젝트의 [멤버 초대]. 시안 MEM-03(입력) → MEM-03V·03V2(검증 오류) → MEM-04(확인) → MEM-04P(처리 중)
//            → MEM-04F(요청 실패) → MEM-05(완료). 단계 표시가 없고, 현재 멤버·대기 중 초대와 겹치는 이메일을 입력 단계에서 바로 막는다
// 시안과 다른 점: 행마다 역할을 고르지 않는다 — 역할은 초대받은 사람이 수락할 때 고른다(decision_log 2026-11-07 결정 5, SPEC AC18).
// 그래서 역할 열·역할 안내·역할 구성·역할 태그·"역할 미선택" 오류는 없다(시안 수정은 디자이너에게 요청함, 2026-11-08).
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

type View = 'input' | 'confirm' | 'sending' | 'sent'
type ErrorKind = 'member' | 'pending' | 'format' | 'dup'

interface Row { key: number; email: string }
interface RowError { kind: ErrorKind; message: string; invitation?: Invitation }

interface Common {
  projectId: string
  projectName: string
  onClose: () => void
  /** INV-05·MEM-05로 넘어갈 때 — 창 너비를 600으로 바꾸게 알린다 */
  onSent?: () => void
  /** 초대·재전송이 한 건 성공할 때마다 — 부모가 목록을 새로 불러온다 */
  onInvited?: () => void
}

interface WizardProps extends Common {
  variant: 'wizard'
  /** [나중에 하기]·[다음: Figma·Git 연결] */
  onNext: (invitedCount: number) => void
  /** INV-05 [멤버 관리] */
  onManageMembers: () => void
}

interface ManageProps extends Common {
  variant: 'manage'
  members: ProjectMember[]
  /** 대기 중(pending) 초대만 */
  invitations: Invitation[]
  ownerId: string
  meId?: string
  /** MEM-05 [멤버 관리에서 확인] */
  onManageMembers: () => void
  /** MEM-05 [작업공간으로 돌아가기] */
  onBackToWorkspace: () => void
}

export type InviteStepProps = WizardProps | ManageProps

const BANNER_LABEL: Record<ErrorKind, string> = { member: '이미 참여 중', pending: '이미 초대됨', format: '이메일 형식 오류', dup: '목록 내 중복' }

const shortDate = (iso: string) => {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '-' : `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function InviteStep(props: InviteStepProps) {
  const { projectId, projectName, onClose, onSent, onInvited } = props
  const manage = props.variant === 'manage'
  const [view, setView] = useState<View>('input')
  const [rows, setRows] = useState<Row[]>([{ key: 1, email: '' }])
  const [nextKey, setNextKey] = useState(2)
  const [touched, setTouched] = useState(false)
  const [sent, setSent] = useState<string[]>([])
  const [failed, setFailed] = useState<{ email: string; message: string }[]>([])
  const [resent, setResent] = useState<string | null>(null)
  const [resending, setResending] = useState<string | null>(null)

  const trimmed = rows.map((r) => r.email.trim().toLowerCase())
  const memberByEmail = new Map(manage ? props.members.map((m) => [m.email.toLowerCase(), m]) : [])
  const pendingByEmail = new Map(manage ? props.invitations.map((i) => [i.email.toLowerCase(), i]) : [])
  const errors: (RowError | null)[] = rows.map((_, i) => {
    const v = trimmed[i]
    if (!v) return null
    if (!EMAIL_PATTERN.test(v)) return { kind: 'format', message: '올바른 이메일 형식이 아니에요 (예: name@company.com)' }
    const member = memberByEmail.get(v)
    if (member && manage) {
      const who = member.id === props.meId ? '본인' : member.name
      const role = member.id === props.ownerId ? '소유자' : roleLabel(member.role)
      return { kind: 'member', message: `이미 이 프로젝트에 참여 중인 멤버입니다 (${who} · ${role})` }
    }
    const invitation = pendingByEmail.get(v)
    if (invitation) {
      return {
        kind: 'pending', invitation,
        message: `이미 초대 요청을 보낸 사용자입니다 · ${shortDate(invitation.created_at)} 발송, ${shortDate(invitation.expires_at)} 만료 예정`,
      }
    }
    if (trimmed.indexOf(v) !== i) return { kind: 'dup', message: manage ? '초대 목록에 같은 이메일이 있어요' : '이미 초대 목록에 있는 이메일이에요' }
    return null
  })
  const counts = (['member', 'pending', 'format', 'dup'] as ErrorKind[])
    .map((k) => [k, errors.filter((e) => e?.kind === k).length] as const).filter(([, n]) => n > 0)
  const errorCount = errors.filter(Boolean).length
  const emails = trimmed.filter(Boolean)
  const canNext = emails.length > 0 && errorCount === 0

  const send = async () => {
    setView('sending')
    setFailed([])
    const ok: string[] = []
    const bad: { email: string; message: string }[] = []
    // 한 명씩 보낸다 — 서버 초대 API가 이메일 하나씩 받는다(SPEC AC17)
    for (const email of emails) {
      try {
        await inviteMember(projectId, email)
        ok.push(email)
        onInvited?.()
      } catch (e) {
        bad.push({ email, message: e instanceof ApiError && e.code === 'ALREADY_MEMBER' ? '이미 이 프로젝트의 멤버예요' : '일시적인 오류로 초대 메일을 보내지 못했어요' })
      }
    }
    const allSent = [...sent, ...ok]
    setSent(allSent)
    if (bad.length > 0) {
      // MEM-04F — 보낸 사람은 목록에서 빼고, 못 보낸 사람만 남겨 [다시 시도]할 수 있게 한다
      setRows(bad.map((b, i) => ({ key: nextKey + i, email: b.email })))
      setNextKey(nextKey + bad.length)
      setFailed(bad)
      setView('confirm')
      return
    }
    setView('sent')
    onSent?.()
  }

  const resend = async (invitation: Invitation, rowKey: number) => {
    setResending(invitation.id)
    try {
      await resendInvitation(projectId, invitation.id)
      // MEM-03V2 [초대 재전송] — 기존 초대를 7일 연장해 다시 보내고, 그 행은 초대 목록에서 뺀다
      setRows((rs) => {
        const left = rs.filter((r) => r.key !== rowKey)
        return left.length > 0 ? left : [{ key: nextKey, email: '' }]
      })
      setNextKey((k) => k + 1)
      setResent(invitation.email)
      onInvited?.()
    } catch {
      setResent(null)
    } finally {
      setResending(null)
    }
  }

  const titleRow = (title: string, description: string) => (
    <div className="flex items-start justify-between gap-4">
      <div className="min-w-0">
        <h2 id="create-project-title" className="text-xl font-bold leading-7 text-black">{title}</h2>
        <p className="mt-1 text-sm leading-5 text-qa-text-sub">{description}</p>
      </div>
      <CloseButton onClick={onClose} disabled={view === 'sending'} />
    </div>
  )
  const header = (title: string, description: string) => manage ? titleRow(title, description) : (
    <div className="flex flex-col gap-3">
      <Stepper current={1} />
      {titleRow(title, description)}
    </div>
  )

  // MEM — 현재 멤버 안내(Existing Members Note)
  const existingNote = manage ? (
    <div className="flex items-center gap-2 rounded-lg border border-qa-border bg-qa-surface px-3 py-2 text-xs leading-4 text-qa-text-sub">
      <Icon name="users" className="h-4 w-4 flex-none text-black" />
      <span>
        {props.invitations.length === 0 && props.members.length <= 1
          ? `현재 멤버 ${props.members.length}명 (소유자) — 아직 초대한 멤버가 없어요. 초대한 멤버는 수락 전까지 ‘초대 대기 중’으로 표시돼요`
          : `현재 멤버 ${props.members.length + props.invitations.length}명 (참여 완료 ${props.members.length} · 초대 대기 ${props.invitations.length}) — 새 초대는 목록에 추가만 되며 기존 멤버의 역할·상태는 바뀌지 않아요`}
      </span>
    </div>
  ) : null

  if (view === 'sent') {
    // INV-05·MEM-05 — 단계 표시 대신 완료 표시(초록 원 + 메일 체크), 600px 창
    return (
      <div className="flex flex-col gap-5">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-qa-success/10 text-qa-success">
          <Icon name="mail-check" className="h-6 w-6" />
        </span>
        {titleRow('멤버 초대를 보냈습니다.', manage
          ? '초대받은 사용자가 수락하면 프로젝트에 참여할 수 있습니다. 수락 전까지는 “초대 대기 중”으로 표시돼요.'
          : '초대받은 사용자가 수락하면 프로젝트에 참여할 수 있습니다.')}
        <div className="grid grid-cols-2 gap-3">
          <Stat label="초대 보냄" value={`${sent.length}명`} hint="초대 요청 완료 · 수락 대기" />
          <Stat label="참여 완료" value="0명" hint="수락 시 프로젝트 멤버로 전환" />
        </div>
        <ul className="divide-y divide-qa-border rounded-xl border border-qa-border">
          {sent.map((email) => (
            <li key={email} className="flex min-h-11 items-center justify-between gap-3 px-4 py-2.5">
              <span className="min-w-0 truncate text-sm font-medium text-black">{email}</span>
              <span className="inline-flex h-6 flex-none items-center gap-1.5 rounded-full border border-qa-border bg-qa-surface px-2 text-xs font-medium text-black">
                <span className="h-1.5 w-1.5 rounded-full bg-qa-text-sub" />초대 대기 중
              </span>
            </li>
          ))}
        </ul>
        <div className="h-px bg-qa-border" />
        <div className="flex items-center justify-between gap-3">
          <FooterNote>{manage ? '초대 상태 확인·재전송·취소는 멤버 관리에서 할 수 있어요' : '수락을 기다리지 않고 바로 연결을 진행할 수 있어요'}</FooterNote>
          {props.variant === 'manage' ? (
            <div className="flex gap-2">
              <button type="button" onClick={props.onBackToWorkspace} className={SECONDARY_BUTTON}>작업공간으로 돌아가기</button>
              <button type="button" onClick={props.onManageMembers} className={PRIMARY_BUTTON} autoFocus>멤버 관리에서 확인</button>
            </div>
          ) : (
            <div className="flex gap-2">
              <button type="button" onClick={props.onManageMembers} className={`${SECONDARY_BUTTON} inline-flex items-center gap-2`}>
                <Icon name="users" className="h-5 w-5" />멤버 관리
              </button>
              <button type="button" onClick={() => props.onNext(sent.length)} className={PRIMARY_BUTTON} autoFocus>다음: Figma·Git 연결</button>
            </div>
          )}
        </div>
      </div>
    )
  }

  if (view === 'confirm' || view === 'sending') {
    // INV-04·MEM-04(확인) / MEM-04P(처리 중) / MEM-04F(요청 실패)
    const sending = view === 'sending'
    const retry = failed.length > 0
    return (
      <div className="flex flex-col gap-5">
        {header('초대 내용을 확인해 주세요', manage
          ? `‘${projectName}’에 ${emails.length}명을 초대합니다. 현재 멤버는 ${props.members.length}명이에요.`
          : `‘${projectName}’ 프로젝트에 ${emails.length}명을 초대합니다. 초대받은 사용자가 수락해야 프로젝트에 참여합니다.`)}
        {retry && (
          <Banner tone="warn" title={sent.length > 0 ? `${failed.length}명에게 초대를 보내지 못했어요` : '초대를 보내지 못했어요'}
            body={`${failed[0].message}${sent.length > 0 ? ` · ${sent.length}명은 보냈어요` : ''}. 입력한 내용은 유지되니 다시 시도해 주세요.`} />
        )}
        {existingNote}
        <ul className="divide-y divide-qa-border rounded-xl border border-qa-border">
          {emails.map((email) => (
            <li key={email} className="flex items-center gap-3 px-4 py-3">
              <span className="flex h-8 w-8 flex-none items-center justify-center rounded-full border border-qa-border bg-qa-surface text-black">
                <Icon name="mail" className="h-4 w-4" />
              </span>
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium leading-4 text-black">{email}</div>
                <div className="text-xs leading-[14px] text-qa-text-sub">초대 메일 발송 예정</div>
              </div>
              <button type="button" onClick={() => setView('input')} disabled={sending}
                className="inline-flex h-8 flex-none items-center gap-1 rounded-lg px-3 text-xs font-medium text-qa-primary-hover hover:bg-[#eef4ff] disabled:text-qa-sub disabled:hover:bg-transparent">
                <Icon name="pencil" className="h-4 w-4" />수정
              </button>
            </li>
          ))}
        </ul>
        <Banner tone="info" title="초대 링크는 7일간 유효합니다" body="프로젝트 역할은 초대받은 사람이 수락할 때 직접 고릅니다." tight={!manage} />
        <div className="h-px bg-qa-border" />
        <div className="flex items-center justify-between gap-3">
          <FooterNote>역할은 수락 후에도 소유자가 멤버 관리에서 바꿀 수 있어요</FooterNote>
          <div className="flex gap-2">
            <button type="button" onClick={() => { setFailed([]); setView('input') }} disabled={sending} className={SECONDARY_BUTTON}>이전</button>
            <button type="button" onClick={() => void send()} disabled={sending} className={PRIMARY_BUTTON} aria-busy={sending}>
              {sending ? <><Spinner />보내는 중…</>
                : retry ? <><Icon name="refresh" className="h-5 w-5" />다시 시도</>
                : <><Icon name="send" className="h-5 w-5" />{emails.length}명에게 초대 보내기</>}
            </button>
          </div>
        </div>
      </div>
    )
  }

  // INV-01·INV-03V / MEM-03·MEM-03V·MEM-03V2
  const showErrors = touched && errorCount > 0
  return (
    <div className="flex flex-col gap-5">
      {header(manage ? '멤버 초대' : '팀원 초대하기', manage
        ? `‘${projectName}’에 함께할 멤버의 구글 이메일을 입력하세요. 역할은 초대받은 사람이 수락할 때 직접 고릅니다.`
        : `초대할 팀원의 구글 이메일을 입력하세요. 역할은 초대받은 사람이 ‘${projectName}’ 프로젝트에 참여할 때 직접 고릅니다.`)}

      {existingNote}

      {resent && (
        <div role="status" className="flex items-center gap-2 rounded-lg bg-qa-success/10 px-3 py-2 text-xs text-black">
          <Icon name="circle-check" className="h-4 w-4 flex-none text-qa-success" />{resent}에게 초대를 다시 보냈어요. 만료가 7일 연장됐어요.
        </div>
      )}

      {showErrors && (
        <Banner tone="warn" role="alert" title={`초대 전에 ${errorCount}개 항목을 확인해 주세요`} tight={!manage}
          body={counts.map(([k, n]) => `${manage ? BANNER_LABEL[k] : k === 'dup' ? '중복 이메일' : BANNER_LABEL[k]} ${n}건`).join(' · ')} />
      )}

      <div className="flex flex-col gap-3 rounded-xl border border-qa-border bg-qa-surface p-4">
        <div className="text-xs font-medium text-qa-text-sub">이메일 주소</div>
        {rows.map((row, i) => {
          const err = touched ? errors[i] : null
          return (
            <div key={row.key} className="flex flex-col gap-3">
              <div className="flex flex-col gap-1.5">
                <div className="flex gap-3">
                  <input
                    type="email"
                    autoFocus={i === rows.length - 1}
                    value={row.email}
                    aria-label={`초대할 이메일 ${i + 1}`}
                    aria-invalid={!!err}
                    onChange={(e) => { setResent(null); setRows(rows.map((r) => (r.key === row.key ? { ...r, email: e.target.value } : r))) }}
                    onBlur={() => setTouched(true)}
                    placeholder="name@company.com"
                    className={`h-10 min-w-0 flex-1 rounded-lg border bg-white px-3 text-sm text-black outline-none placeholder:text-qa-sub ${
                      err ? 'border-qa-warning' : 'border-qa-sub focus:border-qa-primary'}`}
                  />
                  <button type="button" aria-label={`${i + 1}번째 이메일 지우기`} disabled={rows.length === 1}
                    onClick={() => setRows(rows.filter((r) => r.key !== row.key))}
                    className="flex h-10 w-10 flex-none items-center justify-center rounded-lg border border-qa-border text-black hover:bg-white disabled:text-qa-sub disabled:hover:bg-transparent">
                    <Icon name="trash" className="h-[18px] w-[18px]" />
                  </button>
                </div>
                {err && (
                  <div className="flex items-center gap-1 text-xs text-black">
                    <Icon name="alert-circle" className="h-4 w-4 flex-none text-qa-warning" />{err.message}
                  </div>
                )}
              </div>
              {err?.kind === 'pending' && err.invitation && manage && (
                // MEM-03V2 Inline Action — 기존 초대 재전송
                <div className="flex flex-wrap items-center gap-2 pl-1 text-xs">
                  <span className="text-qa-text-sub">기존 초대를 다시 보내려면</span>
                  <button type="button" disabled={resending === err.invitation.id}
                    onClick={() => void resend(err.invitation as Invitation, row.key)}
                    className="inline-flex h-8 items-center gap-1 rounded-lg px-3 font-medium text-qa-primary-hover hover:bg-[#eef4ff] disabled:text-qa-sub">
                    {resending === err.invitation.id ? <Spinner /> : <Icon name="refresh" className="h-4 w-4" />}초대 재전송
                  </button>
                  <span className="text-qa-sub">· 이 행은 초대 목록에서 제외됩니다</span>
                </div>
              )}
            </div>
          )
        })}
        <div className="flex items-center gap-2">
          <button type="button" onClick={() => { setRows([...rows, { key: nextKey, email: '' }]); setNextKey(nextKey + 1) }}
            className="inline-flex h-8 flex-none items-center gap-1 rounded-lg px-3 text-xs font-medium text-qa-primary-hover hover:bg-[#eef4ff]">
            <Icon name="plus" className="h-4 w-4" />멤버 추가
          </button>
          <span className="text-xs text-qa-sub">여러 명을 한 번에 초대할 수 있어요</span>
        </div>
      </div>

      <div className="h-px bg-qa-border" />
      <div className="flex items-center justify-between gap-3">
        {showErrors ? (
          <span className="flex items-center gap-1.5 text-xs text-qa-warning"><Icon name="alert" className="h-4 w-4 flex-none" />
            {manage ? `오류 ${errorCount}건을 수정하거나 행을 제거하면 다음 단계로 이동할 수 있어요` : `오류 ${errorCount}건을 수정하면 다음 단계로 이동할 수 있어요`}
          </span>
        ) : (
          <FooterNote>이메일을 하나 이상 입력해야 다음 단계로 이동할 수 있어요</FooterNote>
        )}
        <div className="flex gap-2">
          {props.variant === 'manage' ? (
            <button type="button" onClick={onClose} className={SECONDARY_BUTTON}>취소</button>
          ) : (
            <button type="button" onClick={() => props.onNext(0)} className={SECONDARY_BUTTON}>나중에 하기</button>
          )}
          <button type="button" disabled={!canNext} onClick={() => { setTouched(true); if (canNext) setView('confirm') }} className={PRIMARY_BUTTON}>
            다음: 초대 확인
          </button>
        </div>
      </div>
    </div>
  )
}

/** 시안 Banner/warn·Banner/info — tight는 INV 시안(54px), 아니면 MEM 시안(62px) */
function Banner({ tone, title, body, tight, role }: { tone: 'warn' | 'info'; title: string; body: string; tight?: boolean; role?: 'alert' }) {
  return (
    <div role={role} className={`flex gap-3 rounded-xl px-4 py-3 ${tone === 'warn' ? 'bg-qa-warning/10' : 'bg-qa-primary/10'}`}>
      <Icon name={tone === 'warn' ? 'alert' : 'info'} className={`h-5 w-5 flex-none ${tone === 'warn' ? 'text-qa-warning' : 'text-qa-primary-hover'}`} />
      <div className="min-w-0">
        <div className={`text-sm font-medium text-black ${tight ? 'leading-4' : 'leading-5'}`}>{title}</div>
        <div className={`text-xs text-qa-text-sub ${tight ? 'leading-[14px]' : 'mt-0.5 leading-4'}`}>{body}</div>
      </div>
    </div>
  )
}

/** 시안 Footer Meta — info 아이콘 + 회색 안내 문구 */
function FooterNote({ children }: { children: string }) {
  return (
    <span className="flex items-center gap-1.5 text-xs text-qa-text-sub">
      <Icon name="info" className="h-4 w-4 flex-none" />{children}
    </span>
  )
}

function Stat({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="rounded-xl border border-qa-border bg-qa-surface p-4">
      <div className="text-xs font-medium text-qa-text-sub">{label}</div>
      <div className="mt-1 text-xl font-bold leading-7 text-black">{value}</div>
      <div className="mt-0.5 text-xs text-qa-text-sub">{hint}</div>
    </div>
  )
}
