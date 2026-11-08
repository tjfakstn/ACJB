import { useState } from 'react'
import { ApiError, inviteMember, type Project } from '../../lib/api'
import { Icon } from '../common/Icon'
import { Spinner } from '../common/Spinner'
import { CloseButton, Stepper } from './WizardParts'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from './wizardStyles'

// 새 프로젝트 마법사 2단계 — 시안 INV-01(입력) → INV-03V(검증 오류) → INV-04(초대 확인) → INV-05(초대 요청 완료).
// 크기·배치·문구·아이콘은 시안을 따르되, 시안과 다른 점이 하나 있다:
// 행마다 역할을 고르지 않는다 — 역할은 초대받은 사람이 수락할 때 고른다(decision_log 2026-11-07 결정 5, SPEC AC18).
// 그래서 역할 열·역할 안내·역할 구성·역할 태그는 없고, 그 자리에 "역할은 수락할 때 고른다"는 안내를 둔다.
// (시안 쪽을 스펙에 맞게 고쳐 달라고 디자이너에게 요청함, 2026-11-08)
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

type View = 'input' | 'confirm' | 'sending' | 'sent'

interface Row { key: number; email: string }
interface SendResult { email: string; ok: boolean; message?: string }

interface InviteStepProps {
  project: Project
  onClose: () => void
  /** [나중에 하기]·[다음: Figma·Git 연결] */
  onNext: (invitedCount: number) => void
  /** INV-05 [멤버 관리] */
  onManageMembers: () => void
  /** INV-05로 넘어갈 때 — 창 너비를 600으로 바꾸게 알린다 */
  onSent?: () => void
}

export function InviteStep({ project, onClose, onNext, onManageMembers, onSent }: InviteStepProps) {
  const [view, setView] = useState<View>('input')
  const [rows, setRows] = useState<Row[]>([{ key: 1, email: '' }])
  const [nextKey, setNextKey] = useState(2)
  const [touched, setTouched] = useState(false)
  const [results, setResults] = useState<SendResult[]>([])

  const trimmed = rows.map((r) => r.email.trim().toLowerCase())
  const filled = rows.filter((_, i) => trimmed[i] !== '')
  const errors = rows.map((_, i) => {
    const v = trimmed[i]
    if (!v) return null
    if (!EMAIL_PATTERN.test(v)) return '올바른 이메일 형식이 아니에요 (예: name@company.com)'
    if (trimmed.indexOf(v) !== i) return '이미 초대 목록에 있는 이메일이에요'
    return null
  })
  const formatErrors = errors.filter((e) => e?.startsWith('올바른')).length
  const dupErrors = errors.filter((e) => e?.startsWith('이미')).length
  const errorCount = formatErrors + dupErrors
  const canNext = filled.length > 0 && errorCount === 0
  const emails = rows.map((r, i) => ({ row: r, email: trimmed[i] })).filter((x) => x.email)

  const send = async () => {
    setView('sending')
    const out: SendResult[] = []
    // 한 명씩 보낸다 — 서버 초대 API가 이메일 하나씩 받는다(SPEC AC17). 실패한 사람만 표시하고 나머지는 그대로 보낸다
    for (const { email } of emails) {
      try {
        await inviteMember(project.id, email)
        out.push({ email, ok: true })
      } catch (e) {
        out.push({
          email, ok: false,
          message: e instanceof ApiError && e.code === 'ALREADY_MEMBER' ? '이미 이 프로젝트의 멤버예요' : '초대를 보내지 못했어요',
        })
      }
    }
    setResults(out)
    setView('sent')
    onSent?.()
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
  const header = (title: string, description: string) => (
    <div className="flex flex-col gap-3">
      <Stepper current={1} />
      {titleRow(title, description)}
    </div>
  )

  if (view === 'sent') {
    // INV-05 — 단계 표시 대신 완료 표시(초록 원 + 메일 체크), 600px 창
    const sent = results.filter((r) => r.ok)
    return (
      <div className="flex flex-col gap-5">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-qa-success/10 text-qa-success">
          <Icon name="mail-check" className="h-6 w-6" />
        </span>
        {titleRow('멤버 초대를 보냈습니다.', '초대받은 사용자가 수락하면 프로젝트에 참여할 수 있습니다.')}
        <div className="grid grid-cols-2 gap-3">
          <Stat label="초대 보냄" value={`${sent.length}명`} hint="초대 요청 완료 · 수락 대기" />
          <Stat label="참여 완료" value="0명" hint="수락 시 프로젝트 멤버로 전환" />
        </div>
        <ul className="divide-y divide-qa-border rounded-xl border border-qa-border">
          {results.map((r) => (
            <li key={r.email} className="flex min-h-11 items-center justify-between gap-3 px-4 py-2.5">
              <span className="min-w-0 truncate text-sm font-medium text-black">{r.email}</span>
              {r.ok ? (
                <span className="inline-flex h-6 flex-none items-center gap-1.5 rounded-full border border-qa-border bg-qa-surface px-2 text-xs font-medium text-black">
                  <span className="h-1.5 w-1.5 rounded-full bg-qa-text-sub" />초대 대기 중
                </span>
              ) : (
                <span className="flex flex-none items-center gap-1 text-xs text-qa-warning">
                  <Icon name="alert-circle" className="h-4 w-4" />{r.message}
                </span>
              )}
            </li>
          ))}
        </ul>
        <div className="h-px bg-qa-border" />
        <div className="flex items-center justify-between gap-3">
          <FooterNote>수락을 기다리지 않고 바로 연결을 진행할 수 있어요</FooterNote>
          <div className="flex gap-2">
            <button type="button" onClick={onManageMembers} className={`${SECONDARY_BUTTON} inline-flex items-center gap-2`}>
              <Icon name="users" className="h-5 w-5" />멤버 관리
            </button>
            <button type="button" onClick={() => onNext(sent.length)} className={PRIMARY_BUTTON} autoFocus>다음: Figma·Git 연결</button>
          </div>
        </div>
      </div>
    )
  }

  if (view === 'confirm' || view === 'sending') {
    // INV-04
    const sending = view === 'sending'
    return (
      <div className="flex flex-col gap-5">
        {header('초대 내용을 확인해 주세요', `‘${project.name}’ 프로젝트에 ${emails.length}명을 초대합니다. 초대받은 사용자가 수락해야 프로젝트에 참여합니다.`)}
        <ul className="divide-y divide-qa-border rounded-xl border border-qa-border">
          {emails.map(({ email }) => (
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
        <div className="flex gap-3 rounded-xl bg-qa-primary/10 px-4 py-3">
          <Icon name="info" className="h-5 w-5 flex-none text-qa-primary-hover" />
          <div className="min-w-0">
            <div className="text-sm font-medium leading-4 text-black">초대 링크는 7일간 유효합니다</div>
            <div className="text-xs leading-[14px] text-qa-text-sub">프로젝트 역할은 초대받은 사람이 수락할 때 직접 고릅니다.</div>
          </div>
        </div>
        <div className="h-px bg-qa-border" />
        <div className="flex items-center justify-between gap-3">
          <FooterNote>역할은 수락 후에도 소유자가 멤버 관리에서 바꿀 수 있어요</FooterNote>
          <div className="flex gap-2">
            <button type="button" onClick={() => setView('input')} disabled={sending} className={SECONDARY_BUTTON}>이전</button>
            <button type="button" onClick={() => void send()} disabled={sending} className={PRIMARY_BUTTON} aria-busy={sending}>
              {sending ? <><Spinner />보내는 중…</> : <><Icon name="send" className="h-5 w-5" />{emails.length}명에게 초대 보내기</>}
            </button>
          </div>
        </div>
      </div>
    )
  }

  // INV-01 · INV-03V
  const showErrors = touched && errorCount > 0
  return (
    <div className="flex flex-col gap-5">
      {header('팀원 초대하기', `초대할 팀원의 구글 이메일을 입력하세요. 역할은 초대받은 사람이 ‘${project.name}’ 프로젝트에 참여할 때 직접 고릅니다.`)}

      {showErrors && (
        <div role="alert" className="flex gap-3 rounded-xl bg-qa-warning/10 px-4 py-3">
          <Icon name="alert" className="h-5 w-5 flex-none text-qa-warning" />
          <div>
            <div className="text-sm font-medium leading-4 text-black">초대 전에 {errorCount}개 항목을 확인해 주세요</div>
            <div className="text-xs leading-[14px] text-qa-text-sub">
              {[formatErrors && `이메일 형식 오류 ${formatErrors}건`, dupErrors && `중복 이메일 ${dupErrors}건`].filter(Boolean).join(' · ')}
            </div>
          </div>
        </div>
      )}

      <div className="flex flex-col gap-3 rounded-xl border border-qa-border bg-qa-surface p-4">
        <div className="text-xs font-medium text-qa-text-sub">이메일 주소</div>
        {rows.map((row, i) => {
          const err = touched ? errors[i] : null
          return (
            <div key={row.key} className="flex flex-col gap-1.5">
              <div className="flex gap-3">
                <input
                  type="email"
                  autoFocus={i === rows.length - 1}
                  value={row.email}
                  aria-label={`초대할 이메일 ${i + 1}`}
                  aria-invalid={!!err}
                  onChange={(e) => setRows(rows.map((r) => (r.key === row.key ? { ...r, email: e.target.value } : r)))}
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
                  <Icon name="alert-circle" className="h-4 w-4 flex-none text-qa-warning" />{err}
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
          <span className="flex items-center gap-1.5 text-xs text-qa-warning"><Icon name="alert" className="h-4 w-4 flex-none" />오류 {errorCount}건을 수정하면 다음 단계로 이동할 수 있어요</span>
        ) : (
          <FooterNote>이메일을 하나 이상 입력해야 다음 단계로 이동할 수 있어요</FooterNote>
        )}
        <div className="flex gap-2">
          <button type="button" onClick={() => onNext(0)} className={SECONDARY_BUTTON}>나중에 하기</button>
          <button type="button" disabled={!canNext} onClick={() => { setTouched(true); if (canNext) setView('confirm') }} className={PRIMARY_BUTTON}>
            다음: 초대 확인
          </button>
        </div>
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
