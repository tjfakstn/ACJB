import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  formatDate, formatDateTime, getReQa, listIssues, manualQaId, TEAM_LABELS, type IssueStatus, type ProjectRun, type QaIssue, type Team,
} from '../lib/api'
import { Icon } from '../components/common/Icon'
import { CARD, WorkflowStepper } from '../components/workspace/WorkspaceCards'
import { currentStep } from '../components/workspace/workflow'
import {
  ErrorCard, KeyValue, Loading, OUTLINE_BTN, PageTitle, PRIMARY_BTN, ProgressChip, QaShell, TeamTag, Verdict, issueParts, issueTitle,
  loadErrorMessage, useProject,
} from '../components/qa/QaParts'

// QA Queue(시안 QUE-01·QUE-02·FIN-01) — 문제 작업목록. 매핑 근거는 여기서 빼고 이슈 상세에서 본다.
// 시안과 다른 점: 심각도 필터·정렬은 없다(심각도를 만들지 않음, 목록 순서는 서버가 등록일 최신순으로 고정).
// 사람이 직접 등록한 이슈(source manual)도 함께 보여 주고 "직접 등록"으로 구분한다.
const TEAMS: Team[] = ['planner', 'designer', 'frontend', 'backend']

type Group = 'open' | 'working' | 'resolved'
const GROUP_OF: Record<IssueStatus, Group> = {
  needs_check: 'open', needs_recheck: 'open', in_progress: 'working', fixed: 'working', resolved: 'resolved',
}
const GROUP_LABEL: Record<Group, string> = { open: '미해결', working: '처리 중', resolved: '해결' }

export function QaQueuePage() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const { me, detail, error } = useProject(projectId)
  const [issues, setIssues] = useState<QaIssue[] | null>(null)
  const [reQa, setReQa] = useState<ProjectRun | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [group, setGroup] = useState<Group | 'all'>('all')
  const [team, setTeam] = useState<Team | 'all'>('all')
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const [list, r] = await Promise.all([listIssues(projectId), getReQa(projectId)])
      setIssues(list)
      setReQa(r)
      setSelectedId((cur) => cur ?? list.find((i) => i.status !== 'resolved')?.id ?? list[0]?.id ?? null)
    } catch (e) {
      setLoadError(loadErrorMessage(e))
    }
  }, [projectId])

  useEffect(() => {
    if (me) void load()
  }, [me, load])

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (issues ?? []).filter((i) =>
      (!q || issueTitle(i).toLowerCase().includes(q) || i.screen_location.toLowerCase().includes(q) || `#${i.id}`.includes(q))
      && (group === 'all' || GROUP_OF[i.status] === group)
      && (team === 'all' || i.assigned_team === team))
  }, [issues, query, group, team])

  if (!me) return null
  const isOwner = !!detail && detail.owner_id === me.id
  const all = issues ?? []
  const count = (g: Group) => all.filter((i) => GROUP_OF[i.status] === g).length
  const resolved = count('resolved')
  const pct = all.length ? Math.round((resolved / all.length) * 100) : 0
  const reQaReady = all.filter((i) => i.status === 'fixed' && i.source === 'first_qa').length
  const selected = all.find((i) => i.id === selectedId) ?? null
  const failMessage = error ?? loadError

  return (
    <QaShell projectId={projectId}>
      <PageTitle
        crumbs={[{ label: detail?.name ?? '프로젝트', to: `/app/projects/${projectId}` }, { label: 'QA Queue' }]}
        title="QA Queue"
        description="QA에서 발견된 문제를 담당 팀 기준으로 관리하세요. 담당 팀이 정해지지 않은 문제는 미배정으로 표시됩니다."
        actions={reQaReady > 0 ? (
          <button type="button" className={PRIMARY_BTN} onClick={() => navigate(`/app/projects/${projectId}/qa`)} disabled={!isOwner}
            title={isOwner ? undefined : 'Re-QA는 프로젝트 소유자만 실행할 수 있어요'}>
            <Icon name="refresh" className="h-4 w-4" />Re-QA 실행 ({reQaReady}건)
          </button>
        ) : undefined}
      />
      {failMessage ? <ErrorCard message={failMessage} projectId={projectId} /> : !detail || !issues ? <Loading /> : (
        <>
          <WorkflowStepper current={currentStep(detail)} />

          <section className={`${CARD} mt-6 flex flex-wrap items-center gap-x-8 gap-y-4 rounded-2xl px-6 py-5`}>
            <div className="min-w-[160px]">
              <div className="text-xs font-medium text-qa-text-sub">이슈 해결 진행률</div>
              <div className="mt-1 text-[28px] font-bold leading-9 text-black">{pct}%</div>
              <div className="text-xs text-qa-text-sub">등록 이슈 {all.length}개 중 {resolved}개 해결</div>
            </div>
            <span className="hidden h-[60px] w-px bg-qa-border md:block" />
            <div className="min-w-[160px] flex-1">
              <div className="h-2 overflow-hidden rounded-full bg-[#e8e8e8]">
                <div className="h-full rounded-full bg-qa-success" style={{ width: `${pct}%` }} />
              </div>
              <div className="mt-2 flex gap-4 text-xs text-black">
                <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-qa-success" />해결 {resolved}</span>
                <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-qa-warning" />미해결 {all.length - resolved}</span>
              </div>
            </div>
            <div className="flex gap-8 text-right">
              {(['open', 'working', 'resolved'] as Group[]).map((g) => (
                <div key={g}>
                  <div className="text-xs text-qa-text-sub">{g === 'resolved' ? '재검증 완료' : GROUP_LABEL[g]}</div>
                  <div className="mt-1 text-[28px] font-bold leading-9 text-black">{count(g)}</div>
                </div>
              ))}
            </div>
          </section>

          <div className="mt-6 flex flex-wrap items-center gap-3">
            <label className="relative block w-full max-w-[260px]">
              <span className="sr-only">이슈 검색</span>
              <Icon name="search" className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-qa-text-sub" />
              <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="이슈 검색"
                className="h-10 w-full rounded-lg border border-qa-sub bg-white pl-9 pr-3 text-sm outline-none placeholder:text-qa-sub focus:border-qa-primary" />
            </label>
            <select aria-label="상태" value={group} onChange={(e) => setGroup(e.target.value as Group | 'all')}
              className="h-10 rounded-lg border border-qa-border bg-white px-3 text-sm">
              <option value="all">상태: 전체</option>
              {(['open', 'working', 'resolved'] as Group[]).map((g) => <option key={g} value={g}>{GROUP_LABEL[g]}</option>)}
            </select>
            <select aria-label="담당 팀" value={team} onChange={(e) => setTeam(e.target.value as Team | 'all')}
              className="h-10 rounded-lg border border-qa-border bg-white px-3 text-sm">
              <option value="all">담당 팀: 전체</option>
              {TEAMS.map((t) => <option key={t} value={t}>{TEAM_LABELS[t]}</option>)}
            </select>
          </div>

          <div className="mt-4 flex items-start gap-3 rounded-xl bg-qa-primary/10 px-4 py-3">
            <Icon name="info" className="h-5 w-5 flex-none text-qa-primary" />
            <p className="text-xs leading-5 text-black">
              {reQa?.status === 'succeeded'
                ? `Re-QA 결과 반영 — ${formatDateTime(reQa.finished_at ?? reQa.created_at)} Re-QA에서 ${reQa.created_count}건이 재검증을 통과해 해결됐고, ${reQa.failed_count ?? 0}건은 여전히 달라 재확인이 필요해요${reQa.dropped_count ? ` (근거를 다시 얻지 못해 건너뛴 ${reQa.dropped_count}건은 수정 완료로 남아 있어요)` : ''}. 남은 문제는 담당 팀이 수정한 뒤 Re-QA로 다시 확인하세요.`
                : '작업목록 등록 기준 — 첫 QA(자동 QA)에서 발견된 수정 필요 문제와 실시간 QA 워크스페이스에서 등록한 수동 QA가 이슈 ID 기준으로 한 번씩 등록돼요. 출처는 그대로 유지되고, 담당 팀이 정해지지 않은 문제는 미배정으로 표시돼요.'}
            </p>
          </div>

          {all.length === 0 ? (
            <div className={`${CARD} mt-6 flex flex-col items-center px-6 py-12 text-center`}>
              <Icon name="inbox" className="h-7 w-7 text-gray-900" />
              <div className="mt-3 text-sm font-medium text-black">아직 등록된 QA 문제가 없습니다</div>
              <p className="mt-2 text-xs text-qa-text-sub">첫 QA를 실행하면 수정이 필요한 항목이 문제로 등록돼요.</p>
            </div>
          ) : (
            <div className="mt-6 grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_380px]">
              <ul className="flex flex-col gap-3">
                {shown.map((i) => {
                  const active = i.id === selectedId
                  return (
                    <li key={i.id}>
                      <div onClick={() => setSelectedId(i.id)}
                        className={`cursor-pointer rounded-xl border bg-white px-5 py-4 ${active ? 'border-qa-primary ring-1 ring-qa-primary' : 'border-qa-border hover:border-qa-sub'}`}>
                        <div className="flex items-start justify-between gap-3">
                          <button type="button" onClick={() => setSelectedId(i.id)} className="min-w-0 text-left text-sm font-bold text-black">{issueTitle(i)}</button>
                          {/* 수동 QA는 사람이 확인한 문제라 PASS/FAIL 판정이 없다(시안 M-10C) — 출처 배지로 대신한다 */}
                          {i.source === 'manual'
                            ? <span className="rounded bg-[#7c3aed]/10 px-1.5 text-xs font-medium leading-5 text-[#7c3aed]">수동 QA</span>
                            : <Verdict pass={i.status === 'resolved'} />}
                        </div>
                        <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
                          <span className="text-xs text-qa-text-sub">{i.manual_qa ? manualQaId(i.manual_qa.pin_number) : `#${i.id}`} · {issueParts(i).screen}{i.source === 'manual' ? ' · 수동 QA' : ''}</span>
                          <span className="flex flex-wrap items-center gap-2">
                            <ProgressChip status={i.status} />
                            <TeamTag team={i.assigned_team} />
                            {!i.assigned_team && i.source === 'first_qa' && (
                              <Link to={`/app/projects/${projectId}/results?issue=${i.id}`} onClick={(e) => e.stopPropagation()}
                                className="flex items-center text-xs font-medium text-qa-primary-hover hover:underline">QA 결과에서 배정<Icon name="chevron-right" className="h-3.5 w-3.5" /></Link>
                            )}
                          </span>
                        </div>
                        <Link to={`/app/projects/${projectId}/issues/${i.id}`} onClick={(e) => e.stopPropagation()}
                          className="mt-3 inline-block text-xs font-medium text-qa-primary-hover hover:underline">상세 보기</Link>
                      </div>
                    </li>
                  )
                })}
                {shown.length === 0 && <li className="py-8 text-center text-xs text-qa-text-sub">조건에 맞는 문제가 없어요</li>}
              </ul>

              {selected && (
                <div className="flex flex-col gap-3">
                  <aside className="rounded-xl border border-qa-primary/50 bg-white p-5">
                    <div className="flex items-center gap-2">
                      <h2 className="text-sm font-bold text-black">이슈 요약</h2>
                      {selected.source === 'manual'
                        ? <span className="rounded bg-[#7c3aed]/10 px-1.5 text-xs font-medium leading-5 text-[#7c3aed]">수동 QA</span>
                        : <Verdict pass={selected.status === 'resolved'} />}
                    </div>
                    <div className="mt-3 text-sm font-bold text-black">{issueTitle(selected)}</div>
                    <div className="text-xs text-qa-text-sub">{issueParts(selected).screen}</div>
                    <div className="mt-3">
                      <KeyValue rows={[
                        { label: '이슈 ID', value: `#${selected.id}` },
                        { label: '담당 팀', value: <TeamTag team={selected.assigned_team} /> },
                        { label: '배정 상태', value: selected.assigned_team ? '배정 완료' : '미배정' },
                        { label: '수정 진행', value: <ProgressChip status={selected.status} /> },
                        { label: '등록일', value: formatDate(selected.created_at) },
                        { label: '출처', value: selected.source === 'first_qa' ? '첫 QA' : selected.manual_qa ? `수동 QA · ${manualQaId(selected.manual_qa.pin_number)}` : '직접 등록' },
                      ]} />
                    </div>
                  </aside>
                  <Link to={`/app/projects/${projectId}/issues/${selected.id}`} className={OUTLINE_BTN}>이슈 상세 보기</Link>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </QaShell>
  )
}
