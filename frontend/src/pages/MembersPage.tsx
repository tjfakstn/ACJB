import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  ApiError, cancelInvitation, fetchMe, formatDate, inviteMember, listInvitations, listMembers, listProjects, removeMember,
  ROLE_LABELS, type Invitation, type Me, type Project, type ProjectMember,
} from '../lib/api'
import { AppHeader } from '../components/projects/AppHeader'
import { WorkspaceSidebar } from '../components/members/WorkspaceSidebar'
import { InviteDialog } from '../components/members/InviteDialog'

// 프로젝트 멤버 관리(시안 U02-C1). 멤버와 대기 중 초대를 한 표에 보여준다.
// 시안과 다른 점: 역할 변경 드롭다운 대신 역할 표시만 한다(역할 변경 API 없음 — 역할은 본인이 수락할 때 고른다),
// "초대 실패" 탭은 뺐다(메일 발송 실패 상태를 서버가 기록하지 않는다).
type Tab = 'all' | 'joined' | 'pending'

const ROLE_BADGE: Record<string, string> = {
  designer: 'bg-[#fdeef5] text-[#c2457f]',
  developer: 'bg-[#eef4ff] text-qa-primary',
  planner: 'bg-[#f3eeff] text-[#7b4fd6]',
}

export function MembersPage() {
  const { projectId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const [me, setMe] = useState<Me | null>(null)
  const [project, setProject] = useState<Project | null>(null)
  const [members, setMembers] = useState<ProjectMember[] | null>(null)
  const [invitations, setInvitations] = useState<Invitation[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('all')
  const [inviteOpen, setInviteOpen] = useState(searchParams.get('invite') === '1')
  const [busy, setBusy] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const [projects, memberList, inviteList] = await Promise.all([
        listProjects(), listMembers(projectId), listInvitations(projectId),
      ])
      setProject(projects.find((p) => p.id === projectId) ?? null)
      setMembers(memberList)
      setInvitations(inviteList)
      setLoadError(null)
    } catch (e) {
      setLoadError(e instanceof ApiError && e.status === 403
        ? '이 프로젝트의 멤버가 아니에요.'
        : e instanceof ApiError && e.status === 404 ? '프로젝트를 찾을 수 없어요.' : '멤버 정보를 불러오지 못했어요.')
    }
  }, [projectId])

  useEffect(() => {
    fetchMe().then((result) => {
      if (!result.authenticated) {
        navigate('/login', { replace: true })
        return
      }
      setMe(result)
      load()
    })
  }, [navigate, load])

  if (!me) return null

  const isOwner = !!project && project.owner_id === me.id
  const joined = members ?? []
  const pending = invitations.filter((i) => i.status === 'pending')

  const run = async (key: string, action: () => Promise<unknown>) => {
    setBusy(key)
    setActionError(null)
    try {
      await action()
      await load()
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : '요청을 처리하지 못했어요.')
    } finally {
      setBusy(null)
    }
  }

  const closeInvite = () => {
    setInviteOpen(false)
    if (searchParams.has('invite')) setSearchParams({}, { replace: true })
  }

  return (
    <div className="min-h-screen bg-[#f5f7fa]">
      <AppHeader />
      <div className="flex min-h-[calc(100vh-64px)]">
        <WorkspaceSidebar projectId={projectId} />
        <main className="min-w-0 flex-1 px-10 pb-16 pt-8">
          <nav className="text-xs text-gray-500">
            <Link to="/app/projects" className="hover:underline">모든 프로젝트</Link>
            <span className="mx-1.5">›</span>
            <span>{project?.name ?? '프로젝트'}</span>
            <span className="mx-1.5">›</span>
            <span className="text-gray-900">멤버 관리</span>
          </nav>

          <div className="mt-3 flex items-start justify-between gap-6">
            <div>
              <h1 className="text-2xl font-extrabold tracking-tight">프로젝트 멤버</h1>
              <p className="mt-2 text-sm text-gray-600">이 프로젝트에 참여하는 멤버와 초대 상태를 관리합니다. 역할은 이 프로젝트에만 적용됩니다.</p>
            </div>
            <button type="button" onClick={() => setInviteOpen(true)} disabled={!!loadError}
              className="inline-flex h-10 flex-none items-center gap-2 rounded-lg bg-qa-primary px-4 text-sm font-semibold text-white hover:bg-qa-primary-hover disabled:opacity-50">
              멤버 초대
            </button>
          </div>

          {loadError ? (
            <div role="alert" className="mt-8 rounded-2xl border border-qa-border bg-white p-10 text-center">
              <div className="text-sm font-semibold text-gray-900">{loadError}</div>
              <Link to="/app/projects" className="mt-3 inline-block text-sm font-semibold text-qa-primary hover:underline">프로젝트 목록으로</Link>
            </div>
          ) : (
            <>
              <div className="mt-6 grid grid-cols-3 gap-3">
                <Stat label="전체" value={joined.length + pending.length} hint="멤버와 대기 중 초대" />
                <Stat label="참여 완료" value={joined.length} hint="초대를 수락한 멤버" />
                <Stat label="초대 대기 중" value={pending.length} hint="7일 안에 수락하지 않으면 만료" />
              </div>

              {actionError && (
                <div role="alert" className="mt-4 rounded-xl border border-[#f7c9c9] bg-[#fdecec] px-4 py-3 text-sm text-qa-warning">{actionError}</div>
              )}

              <section className="mt-6 overflow-hidden rounded-2xl border border-qa-border bg-white">
                <div role="tablist" className="flex gap-6 border-b border-qa-border px-6">
                  {([['all', '전체', joined.length + pending.length], ['joined', '참여 완료', joined.length], ['pending', '초대 대기 중', pending.length]] as const).map(([key, label, count]) => (
                    <button key={key} type="button" role="tab" aria-selected={tab === key} onClick={() => setTab(key)}
                      className={`-mb-px border-b-2 py-3.5 text-sm ${tab === key ? 'border-qa-primary font-semibold text-gray-900' : 'border-transparent text-gray-500'}`}>
                      {label} <span className="ml-1 text-xs text-qa-sub">{count}</span>
                    </button>
                  ))}
                </div>

                <table className="w-full text-sm">
                  <thead className="bg-qa-surface text-left text-xs text-gray-500">
                    <tr>
                      <th className="px-6 py-3 font-medium">멤버</th>
                      <th className="px-4 py-3 font-medium">프로젝트 역할</th>
                      <th className="px-4 py-3 font-medium">상태</th>
                      <th className="px-4 py-3 font-medium">초대·만료</th>
                      <th className="px-6 py-3 text-right font-medium">관리</th>
                    </tr>
                  </thead>
                  <tbody>
                    {members === null && <tr><td colSpan={5} className="px-6 py-10 text-center text-qa-sub">불러오는 중…</td></tr>}
                    {tab !== 'pending' && joined.map((m) => {
                      const memberIsOwner = project?.owner_id === m.id
                      return (
                        <tr key={`m-${m.id}`} className="border-t border-[#eef1f5]">
                          <td className="px-6 py-3.5">
                            <div className="flex items-center gap-3">
                              <Avatar text={m.name} />
                              <div className="min-w-0">
                                <div className="flex items-center gap-1.5 font-medium text-gray-900">
                                  {m.name}{m.id === me.id && <span className="text-xs text-qa-sub">(나)</span>}
                                  {memberIsOwner && <span className="rounded-full border border-qa-border px-2 py-0.5 text-[11px] text-gray-600">소유자</span>}
                                </div>
                                <div className="truncate text-xs text-qa-sub">{m.email}</div>
                              </div>
                            </div>
                          </td>
                          <td className="px-4 py-3.5">
                            <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${ROLE_BADGE[m.role] ?? 'bg-gray-100 text-gray-600'}`}>{ROLE_LABELS[m.role] ?? m.role}</span>
                          </td>
                          <td className="px-4 py-3.5"><StatusDot color="bg-qa-success" label="참여 완료" /></td>
                          <td className="px-4 py-3.5 text-xs text-qa-sub">-</td>
                          <td className="px-6 py-3.5 text-right">
                            {isOwner && !memberIsOwner && (
                              <button type="button" disabled={busy !== null}
                                onClick={() => { if (window.confirm(`${m.name} 님을 이 프로젝트에서 내보낼까요? 담당 중인 미해결 이슈는 담당자가 비워집니다.`)) run(`rm-${m.id}`, () => removeMember(projectId, m.id)) }}
                                className="text-xs font-medium text-qa-warning hover:underline disabled:opacity-50">
                                {busy === `rm-${m.id}` ? '내보내는 중…' : '내보내기'}
                              </button>
                            )}
                          </td>
                        </tr>
                      )
                    })}
                    {tab !== 'joined' && pending.map((inv) => (
                      <tr key={`i-${inv.id}`} className="border-t border-[#eef1f5]">
                        <td className="px-6 py-3.5">
                          <div className="flex items-center gap-3">
                            <Avatar text="@" muted />
                            <div className="min-w-0">
                              <div className="truncate font-medium text-gray-900">{inv.email}</div>
                              <div className="text-xs text-qa-sub">수락 후 이름이 표시돼요 · 초대한 사람 {inv.invited_by_name}</div>
                            </div>
                          </div>
                        </td>
                        <td className="px-4 py-3.5 text-xs text-qa-sub">수락할 때 선택</td>
                        <td className="px-4 py-3.5"><StatusDot color="bg-gray-400" label="초대 대기 중" /></td>
                        <td className="px-4 py-3.5 text-xs text-gray-600">{formatDate(inv.created_at)} 발송 · {formatDate(inv.expires_at)} 만료 예정</td>
                        <td className="px-6 py-3.5 text-right">
                          <div className="flex justify-end gap-3 text-xs font-medium">
                            <button type="button" disabled={busy !== null} onClick={() => run(`re-${inv.id}`, () => inviteMember(projectId, inv.email))}
                              className="text-qa-primary hover:underline disabled:opacity-50">{busy === `re-${inv.id}` ? '보내는 중…' : '재전송'}</button>
                            <button type="button" disabled={busy !== null} onClick={() => run(`cx-${inv.id}`, () => cancelInvitation(projectId, inv.id))}
                              className="text-gray-500 hover:underline disabled:opacity-50">{busy === `cx-${inv.id}` ? '취소 중…' : '취소'}</button>
                          </div>
                        </td>
                      </tr>
                    ))}
                    {members !== null && ((tab === 'pending' && pending.length === 0) || (tab !== 'pending' && joined.length === 0 && pending.length === 0)) && (
                      <tr><td colSpan={5} className="px-6 py-10 text-center text-qa-sub">{tab === 'pending' ? '대기 중인 초대가 없어요' : '멤버가 없어요'}</td></tr>
                    )}
                  </tbody>
                </table>
              </section>

              <p className="mt-4 text-xs text-qa-sub">
                역할은 프로젝트 단위로 적용돼요. 멤버 내보내기는 소유자만 할 수 있고, 내보낸 멤버가 담당하던 미해결 이슈는 담당자가 비워집니다.
              </p>
            </>
          )}
        </main>
      </div>

      {inviteOpen && <InviteDialog projectId={projectId} onClose={closeInvite} onInvited={load} />}
    </div>
  )
}

function Stat({ label, value, hint }: { label: string; value: number; hint: string }) {
  return (
    <div className="rounded-xl border border-qa-border bg-white px-5 py-4">
      <div className="text-xs text-gray-500">{label}</div>
      <div className="mt-1 text-2xl font-extrabold text-gray-900">{value}</div>
      <div className="mt-1 text-xs text-qa-sub">{hint}</div>
    </div>
  )
}

function StatusDot({ color, label }: { color: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full bg-gray-50 px-2.5 py-1 text-xs text-gray-700">
      <span className={`h-1.5 w-1.5 rounded-full ${color}`} />{label}
    </span>
  )
}

function Avatar({ text, muted }: { text: string; muted?: boolean }) {
  return (
    <span className={`flex h-8 w-8 flex-none items-center justify-center rounded-full text-xs font-semibold ${muted ? 'bg-gray-100 text-gray-500' : 'bg-[#eef4ff] text-qa-primary'}`}>
      {text.slice(0, 1)}
    </span>
  )
}
