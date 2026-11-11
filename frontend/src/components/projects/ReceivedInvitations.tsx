import { useEffect, useState } from 'react'
import {
  acceptMyInvitation, ApiError, listMyInvitations, ROLE_LABELS, type Invitation, type MemberRole, type Project,
} from '../../lib/api'
import { Icon } from '../common/Icon'

// 프로젝트 목록 위 "받은 초대"(2026-11-11). 초대 메일을 못 받았거나 링크를 잃어도, 초대받은 구글 계정으로 로그인하면
// 여기서 역할을 골라 바로 참여한다(SPEC AC18 — 수락은 같은 이메일만, 역할은 수락할 때 고른다). 초대가 없으면 아무것도 그리지 않는다

const ROLE_ORDER: MemberRole[] = ['planner', 'designer', 'frontend', 'backend']

export function ReceivedInvitations({ onJoined }: { onJoined: (project: Project) => void }) {
  const [items, setItems] = useState<Invitation[]>([])

  useEffect(() => {
    // 목록을 못 읽어도 프로젝트 목록은 그대로 쓸 수 있어 조용히 넘어간다
    listMyInvitations().then(setItems).catch(() => undefined)
  }, [])

  if (items.length === 0) return null

  return (
    <section aria-labelledby="received-invitations" className="mt-6 overflow-hidden rounded-2xl border border-qa-primary/30 bg-white">
      <div className="flex items-center gap-2 border-b border-qa-border bg-qa-primary/5 px-6 py-3">
        <Icon name="mail" className="h-4 w-4 text-qa-primary" />
        <h2 id="received-invitations" className="text-sm font-semibold text-black">받은 초대 {items.length}건</h2>
        <span className="text-xs text-qa-text-sub">역할을 고르고 참여하면 프로젝트 목록에 추가돼요.</span>
      </div>
      <ul>
        {items.map((inv) => (
          <InvitationRow key={inv.id} invitation={inv} onJoined={(p) => {
            setItems((prev) => prev.filter((x) => x.id !== inv.id))
            onJoined(p)
          }} />
        ))}
      </ul>
    </section>
  )
}

function InvitationRow({ invitation, onJoined }: { invitation: Invitation; onJoined: (project: Project) => void }) {
  const [role, setRole] = useState<MemberRole | ''>('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function accept() {
    if (!role || busy) return
    setBusy(true)
    setError(null)
    try {
      onJoined(await acceptMyInvitation(invitation.id, role))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '참여하지 못했어요. 다시 시도해 주세요.')
      setBusy(false)
    }
  }

  const expires = new Date(invitation.expires_at)
  return (
    <li className="flex flex-wrap items-center gap-x-6 gap-y-3 border-b border-qa-border px-6 py-4 last:border-b-0">
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-semibold text-black">{invitation.project_name}</div>
        <div className="mt-1 text-xs text-qa-text-sub">
          {invitation.invited_by_name}님이 초대했어요 · {expires.getMonth() + 1}월 {expires.getDate()}일까지
        </div>
        {error && <div role="alert" className="mt-1 text-xs text-qa-warning">{error}</div>}
      </div>
      <label className="sr-only" htmlFor={`role-${invitation.id}`}>{invitation.project_name}에서 맡을 역할</label>
      <select id={`role-${invitation.id}`} value={role} disabled={busy} onChange={(e) => setRole(e.target.value as MemberRole)}
        className="h-9 rounded-lg border border-qa-border bg-white px-3 text-sm text-black">
        <option value="">역할 선택</option>
        {ROLE_ORDER.map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
      </select>
      <button type="button" onClick={() => void accept()} disabled={!role || busy}
        className="h-9 rounded-lg bg-qa-primary-hover px-4 text-sm font-medium text-white hover:bg-[#0062bd] disabled:cursor-not-allowed disabled:bg-qa-border disabled:text-qa-sub">
        {busy ? '참여하는 중…' : '참여하기'}
      </button>
    </li>
  )
}
