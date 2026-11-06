import { useState } from 'react'
import { ApiError, inviteMember } from '../../lib/api'
import { Spinner } from '../projects/CreateProjectDialog'

// 멤버 초대 — 이메일만 받는다(SPEC AC17).
// 시안 U02-01~05는 초대하는 사람이 멤버별 역할을 정하고 여러 명을 한 번에 보내지만,
// 지금 스펙은 초대받은 사람이 수락할 때 역할을 고르고 한 번에 한 명씩 초대한다 — 시안과 스펙을 맞출 때 다시 본다.
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

// 열려 있을 때만 그린다 — 다시 열면 입력이 처음부터 시작된다
interface InviteDialogProps {
  projectId: string
  onClose: () => void
  onInvited: () => void
}

export function InviteDialog({ projectId, onClose, onInvited }: InviteDialogProps) {
  const [email, setEmail] = useState('')
  const [touched, setTouched] = useState(false)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sentTo, setSentTo] = useState<string | null>(null)

  const invalid = touched && !EMAIL_PATTERN.test(email.trim())

  const submit = async () => {
    setTouched(true)
    if (!EMAIL_PATTERN.test(email.trim())) return
    setSending(true)
    setError(null)
    try {
      await inviteMember(projectId, email.trim())
      setSentTo(email.trim())
      onInvited()
    } catch (e) {
      setError(
        e instanceof ApiError && e.code === 'ALREADY_MEMBER'
          ? '이미 이 프로젝트의 멤버예요.'
          : e instanceof ApiError ? e.message : '초대를 보내지 못했어요.',
      )
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-[#0f141e]/45 px-5 py-10"
      onMouseDown={(e) => { if (e.target === e.currentTarget && !sending) onClose() }}>
      <div role="dialog" aria-modal="true" aria-labelledby="invite-title"
        className="mt-[100px] w-[480px] max-w-full rounded-2xl bg-white p-6 shadow-[0_8px_24px_rgba(0,0,0,0.12)]">
        <h2 id="invite-title" className="text-lg font-extrabold tracking-tight">멤버 초대</h2>
        {sentTo ? (
          <>
            <p className="mt-2 text-sm text-gray-600">
              <b className="text-gray-900">{sentTo}</b>로 초대 메일을 보냈어요. 7일 안에 같은 구글 계정으로 로그인해 수락하면 멤버가 됩니다.
            </p>
            <div className="mt-6 flex justify-end gap-2">
              <button type="button" onClick={() => { setSentTo(null); setEmail(''); setTouched(false) }}
                className="h-10 rounded-lg border border-qa-border px-4 text-sm font-medium text-gray-700 hover:bg-gray-50">
                한 명 더 초대
              </button>
              <button type="button" onClick={onClose}
                className="h-10 rounded-lg bg-qa-primary px-4 text-sm font-semibold text-white hover:bg-qa-primary-hover">
                완료
              </button>
            </div>
          </>
        ) : (
          <>
            <p className="mt-2 text-sm text-gray-600">구글 계정 이메일로 초대해요. 역할은 초대받은 사람이 수락할 때 직접 고릅니다.</p>
            <label htmlFor="invite-email" className="mt-5 block text-xs font-medium text-gray-800">이메일 <span className="text-qa-warning">*</span></label>
            <input id="invite-email" type="email" value={email} autoFocus disabled={sending}
              onChange={(e) => { setEmail(e.target.value); setError(null) }}
              onBlur={() => setTouched(true)}
              onKeyDown={(e) => { if (e.key === 'Enter') submit() }}
              placeholder="name@example.com" aria-invalid={invalid || !!error}
              className={`mt-1.5 h-10 w-full rounded-lg border px-3 text-sm outline-none focus:border-qa-primary ${invalid || error ? 'border-red-300' : 'border-qa-border'}`} />
            <div role={error ? 'alert' : undefined} className={`mt-1.5 text-xs ${invalid || error ? 'text-qa-warning' : 'text-qa-sub'}`}>
              {invalid ? '이메일 형식을 확인해 주세요' : error ?? '같은 이메일로 다시 보내면 이전 초대는 취소되고 새 초대가 나가요.'}
            </div>
            <div className="mt-6 flex justify-end gap-2">
              <button type="button" onClick={onClose} disabled={sending}
                className="h-10 rounded-lg border border-qa-border px-4 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50">
                취소
              </button>
              <button type="button" onClick={submit} disabled={sending}
                className="inline-flex h-10 items-center gap-2 rounded-lg bg-qa-primary px-4 text-sm font-semibold text-white hover:bg-qa-primary-hover disabled:opacity-70">
                {sending && <Spinner />}
                초대 보내기
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
