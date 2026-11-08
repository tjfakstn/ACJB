import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  formatDateTime, getFirstQa, getReQa, listIssues, listMembers, PROPERTY_LABELS, TEAM_LABELS,
  type ProjectMember, type ProjectRun, type QaIssue, type Team,
} from '../lib/api'
import { Icon } from '../components/common/Icon'
import { CARD, WorkflowStepper } from '../components/workspace/WorkspaceCards'
import { currentStep } from '../components/workspace/workflow'
import { AssignPanel } from '../components/qa/AssignPanel'
import {
  Banner, ErrorCard, Loading, OUTLINE_BTN, PageTitle, PRIMARY_BTN, ProgressChip, QaShell, TeamTag, Verdict, demoNote, issueParts,
  loadErrorMessage, useProject,
} from '../components/qa/QaParts'

// QA 결과(시안 QA-04·QA-04P·ISS-01·ASN-01~04·FIN-02, SPEC AC23·AC3).
// 첫 QA가 만든 이슈(FAIL)만 보여 준다. 시안과 다른 점(decision_log 2026-11-08):
//  - PASS 항목 목록(QA-04P2)·디자인 일치율·평균 신뢰도·심각도는 만들지 않는다 — 서버가 PASS 항목을 저장하지 않고 근거 정의가 없다
//  - 그래서 전체/PASS/FAIL 탭 대신 배정 상태·담당 팀 필터만 둔다
//  - 판정은 이슈 상태로 정한다: Re-QA로 해결(resolved)되면 PASS, 그 외는 FAIL
const TEAMS: Team[] = ['planner', 'designer', 'frontend', 'backend']

type AssignFilter = 'all' | 'unassigned' | 'assigned'

export function QaResultsPage() {
  const { projectId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const { me, detail, error, reload } = useProject(projectId)
  const [run, setRun] = useState<ProjectRun | null>(null)
  const [reQa, setReQa] = useState<ProjectRun | null>(null)
  const [issues, setIssues] = useState<QaIssue[] | null>(null)
  const [members, setMembers] = useState<ProjectMember[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [assignFilter, setAssignFilter] = useState<AssignFilter>('all')
  const [teamFilter, setTeamFilter] = useState<Team | 'all'>('all')
  const [toast, setToast] = useState<{ text: string; sub: string } | null>(null)
  const selectedId = params.get('issue')

  const load = useCallback(async () => {
    try {
      const [f, r, list, mem] = await Promise.all([getFirstQa(projectId), getReQa(projectId), listIssues(projectId), listMembers(projectId)])
      setRun(f)
      setReQa(r)
      setIssues(list.filter((i) => i.source === 'first_qa'))
      setMembers(mem)
    } catch (e) {
      setLoadError(loadErrorMessage(e))
    }
  }, [projectId])

  useEffect(() => {
    if (me) void load()
  }, [me, load])

  useEffect(() => {
    if (!toast) return
    const t = setTimeout(() => setToast(null), 5000)
    return () => clearTimeout(t)
  }, [toast])

  const shown = useMemo(() => (issues ?? []).filter((i) =>
    (assignFilter === 'all' || (assignFilter === 'unassigned' ? i.assigned_team === null : i.assigned_team !== null))
    && (teamFilter === 'all' || i.assigned_team === teamFilter)), [issues, assignFilter, teamFilter])

  if (!me) return null
  const selected = issues?.find((i) => i.id === selectedId) ?? null
  const select = (id: string | null) => setParams(id ? { issue: id } : {}, { replace: true })

  const onAssigned = (updated: QaIssue, previous: Team | null) => {
    setIssues((list) => list?.map((i) => (i.id === updated.id ? updated : i)) ?? null)
    void reload()
    const team = TEAM_LABELS[updated.assigned_team!]
    // 알림은 그 역할 멤버 각각에게만 간다 — 멤버가 없으면 아무에게도 가지 않는다(AC3)
    const count = members.filter((m) => m.role === updated.assigned_team).length
    const notice = count > 0 ? `${team} 멤버 ${count}명에게 알림을 보냈어요` : `${team} 역할 멤버가 없어 알림은 보내지 않았어요`
    setToast(previous
      ? { text: `담당 팀을 ${TEAM_LABELS[previous]}에서 ${team}으로 변경했어요`, sub: `#${updated.id} · ${notice}` }
      : { text: `${team}에 배정했어요`, sub: `#${updated.id} · ${notice} · 수정 진행 상태는 그대로예요` })
  }

  const failMessage = error ?? loadError
  const total = issues?.length ?? 0
  const unassigned = issues?.filter((i) => i.assigned_team === null).length ?? 0
  const resolved = issues?.filter((i) => i.status === 'resolved').length ?? 0
  const allResolved = total > 0 && resolved === total

  return (
    <QaShell projectId={projectId}>
      <PageTitle
        crumbs={[{ label: detail?.name ?? '프로젝트', to: `/app/projects/${projectId}` }, { label: 'QA 결과' }]}
        title="QA 결과"
        description={allResolved
          ? `첫 QA에서 발견된 문제 ${total}건을 모두 수정하고 Re-QA로 재검증한 최종 결과입니다. 수정이 필요한 문제가 남아 있지 않아요.`
          : total === 0 && run?.status === 'succeeded'
            ? '첫 QA 실행 결과입니다. 모든 항목이 디자인 기대값과 일치해 수정 담당 팀 배정과 QA Queue 등록이 필요하지 않아요.'
            : '첫 QA 실행 결과입니다. 수정이 필요한 항목을 확인하고 수정 담당 팀을 직접 지정한 뒤 QA Queue에서 관리할 수 있어요.'}
        actions={<Link to={`/app/projects/${projectId}/queue`} className={PRIMARY_BTN}>QA Queue로 이동</Link>}
      />
      {failMessage ? <ErrorCard message={failMessage} projectId={projectId} /> : !detail || !issues ? <Loading /> : run?.status !== 'succeeded' ? (
        <div className={`${CARD} mt-8 flex flex-col items-center px-6 py-12 text-center`}>
          <Icon name="list-checks" className="h-7 w-7 text-gray-900" />
          <div className="mt-3 text-sm font-medium text-black">아직 첫 QA 결과가 없어요</div>
          <p className="mt-2 text-xs text-qa-text-sub">디자인 QA에서 검증 범위를 고르고 첫 QA를 실행하면 결과가 여기에 나타나요.</p>
          <Link to={`/app/projects/${projectId}/qa`} className={`${PRIMARY_BTN} mt-4`}>디자인 QA로 이동</Link>
        </div>
      ) : (
        <>
          <WorkflowStepper current={currentStep(detail)} />
          {allResolved ? (
            <Banner tone="green" icon="check" title="최종 QA 결과 · 모든 문제 해결" badge="Re-QA 완료"
              sub={`${reQa?.finished_at ? `${formatDateTime(reQa.finished_at)} Re-QA` : 'Re-QA'} · 첫 QA(${formatDateTime(run.finished_at ?? run.created_at)})에서 발견한 FAIL ${total}건이 모두 재검증을 통과했어요.${demoNote(run.demo)}`}
              action={<span className="rounded-full border border-qa-success bg-white px-3 py-1 text-xs font-medium text-qa-success">남은 문제 0건</span>} />
          ) : total === 0 ? (
            <Banner tone="green" icon="check" title="첫 QA 실행 결과 · 모든 항목 검사 통과" badge="첫 QA"
              sub={`${formatDateTime(run.finished_at ?? run.created_at)} 결과 생성 · 수정이 필요한 항목이 없어 담당 팀 배정이 필요하지 않습니다.${demoNote(run.demo)}`}
              action={<span className="rounded-full border border-qa-success bg-white px-3 py-1 text-xs font-medium text-qa-success">담당 팀 배정 불필요</span>} />
          ) : (
            <section className={`${CARD} mt-6 flex flex-wrap items-center gap-4 px-6 py-4`}>
              <span className="flex h-10 w-10 flex-none items-center justify-center rounded-full bg-qa-success/10 text-qa-success"><Icon name="check" className="h-5 w-5" /></span>
              <div className="min-w-[240px] flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-base font-bold text-black">첫 QA 실행 결과 · 최초 검사 완료</h2>
                  <span className="rounded-full bg-qa-primary px-2 text-xs font-medium leading-[18px] text-white">첫 QA</span>
                </div>
                <p className="mt-1 text-xs text-qa-text-sub">
                  {formatDateTime(run.finished_at ?? run.created_at)} 결과 생성 · 검증 범위 {run.scope?.length ?? 0}개 · 이전 실행 기록이 없어 이전 검사 대비 비교 정보는 제공되지 않아요.{demoNote(run.demo)}
                </p>
              </div>
              <span className="flex h-[22px] items-center rounded-full bg-qa-warning/10 px-2.5 text-xs font-medium text-black">수정이 필요한 항목 {total - resolved}</span>
            </section>
          )}

          {total > 0 && (
            <section className={`${CARD} mt-6 flex flex-wrap items-center gap-x-6 gap-y-3 px-6 py-4`}>
              <div className="min-w-[200px]">
                <div className="text-xs font-medium text-qa-text-sub">담당 팀 배정 현황</div>
                <div className="mt-1 text-base font-bold text-black">수정이 필요한 항목 {total}건 · {unassigned === 0 ? '모두 배정' : `담당 팀 미배정 ${unassigned}건`}</div>
              </div>
              <div className="h-2 min-w-[120px] flex-1 overflow-hidden rounded-full bg-[#e8e8e8]">
                <div className="h-full rounded-full bg-qa-primary" style={{ width: `${((total - unassigned) / total) * 100}%` }} />
              </div>
              <div className="flex flex-wrap items-center gap-3 text-xs text-black">
                <span className="flex items-center gap-1"><span className="h-3 w-3 rounded-full border border-qa-sub" />미배정 <b>{unassigned}</b></span>
                {TEAMS.map((t) => (
                  <span key={t} className="flex items-center gap-1"><TeamTag team={t} /><b>{issues.filter((i) => i.assigned_team === t).length}</b></span>
                ))}
              </div>
            </section>
          )}

          {total > 0 && (
            <>
              <div className="mt-6 flex flex-wrap items-center justify-between gap-3">
                <div className="flex items-center gap-2 text-sm">
                  <span className="font-bold text-black">{allResolved ? '첫 QA 문제 항목' : 'FAIL 항목'}</span>
                  <span className="rounded-full bg-qa-surface px-2 text-xs font-medium text-black ring-1 ring-qa-border">{total}</span>
                </div>
                <div className="flex flex-wrap items-center gap-3">
                  <span className="text-xs text-qa-text-sub">배정 상태</span>
                  <div className="flex rounded-lg border border-qa-border bg-white p-0.5 text-xs">
                    {([['all', `전체 ${total}`], ['unassigned', `미배정 ${unassigned}`], ['assigned', `배정 완료 ${total - unassigned}`]] as const).map(([v, label]) => (
                      <button key={v} type="button" onClick={() => setAssignFilter(v)} aria-pressed={assignFilter === v}
                        className={`rounded-md px-3 py-1.5 ${assignFilter === v ? 'bg-qa-primary/10 font-medium text-qa-primary-hover' : 'text-qa-text-sub hover:bg-qa-surface'}`}>{label}</button>
                    ))}
                  </div>
                  <label className="flex items-center gap-1 text-xs text-black">
                    <span className="sr-only">담당 팀</span>
                    <select value={teamFilter} onChange={(e) => setTeamFilter(e.target.value as Team | 'all')}
                      className="h-8 rounded-lg border border-qa-border bg-white px-2 text-xs">
                      <option value="all">담당 팀: 전체</option>
                      {TEAMS.map((t) => <option key={t} value={t}>{TEAM_LABELS[t]}</option>)}
                    </select>
                  </label>
                </div>
              </div>

              {!allResolved && <div className="mt-4 flex items-start gap-3 rounded-xl bg-qa-primary/10 px-4 py-3">
                <Icon name="info" className="h-5 w-5 flex-none text-qa-primary" />
                <div>
                  <div className="text-sm font-bold text-black">수정 담당 팀은 사람이 직접 결정합니다</div>
                  <div className="mt-0.5 text-xs text-qa-text-sub">AI는 불일치 항목을 찾을 뿐 담당 팀을 자동으로 지정하지 않아요. FAIL 항목을 선택하고 담당 팀을 고른 뒤 [담당 팀 배정]을 눌러 확정하세요.</div>
                </div>
              </div>}
            </>
          )}

          <div className="mt-4 grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_380px]">
            {total > 0 ? (
              <section className={`${CARD} overflow-hidden`}>
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[640px] text-left text-sm">
                    <thead className="bg-qa-surface text-xs text-qa-text-sub">
                      <tr>
                        <th className="px-4 py-2.5 font-medium">컴포넌트</th>
                        <th className="px-3 py-2.5 font-medium">판정</th>
                        <th className="px-3 py-2.5 font-medium">불일치 요약</th>
                        <th className="px-3 py-2.5 font-medium">진행 상태</th>
                        <th className="px-3 py-2.5 font-medium">담당 팀</th>
                      </tr>
                    </thead>
                    <tbody>
                      {shown.map((i) => {
                        const parts = issueParts(i)
                        const m = i.style_mismatch
                        const active = i.id === selectedId
                        return (
                          <tr key={i.id} onClick={() => select(i.id)} aria-selected={active}
                            className={`cursor-pointer border-t border-qa-border ${active ? 'bg-qa-primary/10' : 'hover:bg-qa-surface'}`}>
                            <td className="px-4 py-3">
                              <button type="button" className="text-left" onClick={(e) => { e.stopPropagation(); select(i.id) }}>
                                <span className="block font-medium text-black">{parts.element}</span>
                                <span className="block text-xs text-qa-text-sub">#{i.id} · {parts.screen}</span>
                              </button>
                            </td>
                            <td className="px-3 py-3"><Verdict pass={i.status === 'resolved'} /></td>
                            <td className="px-3 py-3">
                              <span className="block font-medium text-black">{m ? PROPERTY_LABELS[m.property] : '불일치'}</span>
                              <span className="block text-xs text-qa-text-sub">{m ? `${m.expected_value} → ${m.actual_value}` : i.description}</span>
                            </td>
                            <td className="px-3 py-3"><ProgressChip status={i.status} /></td>
                            <td className="px-3 py-3"><TeamTag team={i.assigned_team} /></td>
                          </tr>
                        )
                      })}
                      {shown.length === 0 && (
                        <tr><td colSpan={5} className="px-4 py-8 text-center text-xs text-qa-text-sub">조건에 맞는 항목이 없어요</td></tr>
                      )}
                    </tbody>
                  </table>
                </div>
                <div className="flex items-center justify-between border-t border-qa-border px-4 py-2.5 text-xs text-qa-text-sub">
                  <span>{total}건 중 {shown.length}건 표시 · FAIL 항목만 담당 팀 배정 대상</span>
                </div>
              </section>
            ) : <div />}

            {selected ? (
              <AssignPanel issue={selected} projectId={projectId} members={members} onClose={() => select(null)} onAssigned={onAssigned} />
            ) : (
              <aside className={`${CARD} flex flex-col items-center px-6 py-12 text-center`}>
                <Icon name="list-checks" className="h-6 w-6 text-gray-900" />
                {allResolved || total === 0 ? (
                  <>
                    <div className="mt-3 text-sm font-bold text-black">{allResolved ? '모든 문제가 해결되었어요' : '모든 항목이 검사를 통과했어요'}</div>
                    <p className="mt-2 text-xs leading-4 text-qa-text-sub">
                      {allResolved
                        ? `첫 QA에서 발견된 ${total}건이 모두 해결되어 QA 사이클이 완료되었어요. 디자인이나 구현이 바뀌면 Re-QA로 다시 확인하세요.`
                        : '수정이 필요한 항목이 없어 담당 팀 배정과 QA Queue 등록이 필요하지 않아요.'}
                    </p>
                    <span className="mt-3 rounded-full bg-qa-surface px-3 py-1 text-xs font-medium text-black ring-1 ring-qa-border">
                      {allResolved ? `해결된 문제 ${total}건 · 남은 문제 0건` : '담당 팀 배정 대상 0건'}
                    </span>
                    <div className="mt-4 flex gap-2">
                      <Link to={`/app/projects/${projectId}`} className={OUTLINE_BTN}>프로젝트 홈으로</Link>
                      <Link to={`/app/projects/${projectId}/queue`} className={PRIMARY_BTN}>QA Queue 보기</Link>
                    </div>
                  </>
                ) : (
                  <>
                    <div className="mt-3 text-sm font-bold text-black">확인할 문제를 선택하세요</div>
                    <p className="mt-2 text-xs leading-4 text-qa-text-sub">목록에서 FAIL 항목을 선택하면 불일치 내용과 수정 담당 팀 배정 영역이 이 패널에 열립니다.</p>
                    {unassigned > 0 && (
                      <span className="mt-3 rounded-full bg-qa-warning/10 px-3 py-1 text-xs font-medium text-[#c8161a]">담당 팀 미배정 {unassigned}건 · 문제를 선택해 담당 팀을 지정하세요</span>
                    )}
                  </>
                )}
              </aside>
            )}
          </div>
        </>
      )}

      {toast && (
        <div role="status" className="fixed bottom-6 left-1/2 z-50 flex max-w-[calc(100vw-32px)] -translate-x-1/2 items-center gap-3 rounded-xl bg-gray-900 px-4 py-3 text-white shadow-lg">
          <span className="flex h-6 w-6 flex-none items-center justify-center rounded-full bg-qa-success"><Icon name="check" className="h-3.5 w-3.5" /></span>
          <div className="min-w-0">
            <div className="text-sm font-medium">{toast.text}</div>
            <div className="text-xs text-white/70">{toast.sub}</div>
          </div>
          <Link to={`/app/projects/${projectId}/queue`} className="flex-none text-xs font-medium text-[#7cc0ff] hover:underline">QA Queue에서 보기</Link>
          <button type="button" aria-label="알림 닫기" onClick={() => setToast(null)} className="flex-none text-white/70 hover:text-white"><Icon name="x" className="h-4 w-4" /></button>
        </div>
      )}
    </QaShell>
  )
}
