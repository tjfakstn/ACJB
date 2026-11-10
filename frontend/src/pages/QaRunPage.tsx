import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  ApiError, formatDate, formatDateTime, getAnalysis, getFirstQa, getMappings, getReQa, listIssues, QA_SCOPES, startFirstQa, startReQa,
  type MappingList, type ProjectDetail, type ProjectRun, type QaIssue, type QaScope,
} from '../lib/api'
import { Icon } from '../components/common/Icon'
import { CARD, WorkflowStepper } from '../components/workspace/WorkspaceCards'
import {
  Banner, ErrorCard, KeyValue, Loading, PageTitle, PRIMARY_BTN, QaShell, SectionTitle, demoNote, issueTitle, loadErrorMessage, useProject,
} from '../components/qa/QaParts'

// 디자인 QA 실행(시안 QA-01·02·02F·03, SPEC AC23)과 Re-QA 실행(시안 RQA-01·02, AC28).
// 첫 QA가 성공하기 전에는 첫 QA 화면, 그 뒤로는 Re-QA 화면이다 — 첫 QA는 프로젝트당 성공 1번이라서.
// Re-QA도 검증 범위를 고른다(2026-11-10): 지금까지 검사한 범위는 잠겨 늘 다시 확인하고, 더 고른 범위는 매핑 전체를 새로 비교한다.
// 시안과 다른 점:
//  - Figma "프레임 수·컴포넌트 수·마지막 동기화", GitHub "최근 커밋·관련 PR"은 서버가 주지 않는다 —
//    대신 비교 분석이 찾은 화면·요소 짝 수와 분석한 커밋을 보여 준다(관련 PR은 v1 비포함)
//  - 실행 중에 페이지를 떠나도 서버에서 계속 돈다. 다시 들어오면 실행 상태를 읽어 이어서 보여 준다(QA-02 확인 필요 항목)
//  - QA-02F 실패 사유는 서버 error_message를 그대로 쓴다(시안의 "응답 시간 초과"는 시연용 문구)

const POLL_MS = 2000

export function QaRunPage() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const { me, detail, error, reload } = useProject(projectId)
  const [analysis, setAnalysis] = useState<ProjectRun | null>(null)
  const [mapping, setMapping] = useState<MappingList | null>(null)
  const [firstQa, setFirstQa] = useState<ProjectRun | null>(null)
  const [reQa, setReQa] = useState<ProjectRun | null>(null)
  const [issues, setIssues] = useState<QaIssue[]>([])
  const [loaded, setLoaded] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [scope, setScope] = useState<QaScope[]>(QA_SCOPES.map((s) => s.value))
  const [submitting, setSubmitting] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  /** 이 화면에서 실행이 끝나는 것을 본 첫 QA — QA-03(완료 요약)을 보여 준다 */
  const [justFinished, setJustFinished] = useState(false)
  /** Re-QA를 시작할 때의 대상 — 끝나면 대상이 하나일 때 그 결과 화면(RQA-03)으로 간다 */
  const reQaTargets = useRef<string[]>([])

  const load = useCallback(async () => {
    try {
      const [a, f, r, list] = await Promise.all([getAnalysis(projectId), getFirstQa(projectId), getReQa(projectId), listIssues(projectId)])
      setAnalysis(a)
      setMapping(a?.status === 'succeeded' ? await getMappings(projectId) : null)
      setFirstQa(f)
      setReQa(r)
      setIssues(list)
      // Re-QA는 지금까지 검사한 범위에서 시작한다 — 더 고를 범위만 사람이 켠다
      const covered = f?.status === 'succeeded' ? (f.covered_scope ?? f.scope) : f?.scope
      if (covered) setScope(covered)
      setLoadError(null)
    } catch (e) {
      setLoadError(loadErrorMessage(e))
    } finally {
      setLoaded(true)
    }
  }, [projectId])

  useEffect(() => {
    if (me) void load()
  }, [me, load])

  const firstRunning = firstQa?.status === 'queued' || firstQa?.status === 'running'
  const reRunning = reQa?.status === 'queued' || reQa?.status === 'running'

  // 실행 중이면 상태를 다시 읽는다. 끝나면 첫 QA는 완료 요약을, Re-QA는 결과 화면을 보여 준다
  useEffect(() => {
    if (!firstRunning && !reRunning) return
    const timer = setInterval(async () => {
      try {
        if (firstRunning) {
          const f = await getFirstQa(projectId)
          setFirstQa(f)
          if (f && f.status !== 'queued' && f.status !== 'running') {
            if (f.status === 'succeeded') setJustFinished(true)
            void reload()
            void load()
          }
        } else {
          const r = await getReQa(projectId)
          setReQa(r)
          if (r?.status === 'succeeded') {
            const targets = reQaTargets.current
            // 새 범위만 비교했으면 판정 결과가 없다 — 새로 만든 이슈가 있는 QA 결과로 간다
            navigate(targets.length === 0 ? `/app/projects/${projectId}/results`
              : targets.length === 1 && !(r.added_scope?.length) ? `/app/projects/${projectId}/issues/${targets[0]}/re-qa`
                : `/app/projects/${projectId}/queue`,
              { state: { reQaRunId: r.id } })
          } else if (r && r.status === 'failed') {
            void load()
          }
        }
      } catch {
        // 다음 주기에 다시 읽는다
      }
    }, POLL_MS)
    return () => clearInterval(timer)
  }, [firstRunning, reRunning, projectId, navigate, reload, load])

  const targets = useMemo(() => issues.filter((i) => i.status === 'fixed' && i.source === 'first_qa'), [issues])

  if (!me) return null
  const isOwner = !!detail && detail.owner_id === me.id
  const reQaMode = firstQa?.status === 'succeeded' && !justFinished
  /** 지금까지 검사한 범위(첫 QA + 이전 Re-QA가 더한 범위) — Re-QA에서 뺄 수 없다 */
  const covered: QaScope[] = firstQa?.covered_scope ?? firstQa?.scope ?? []
  const added = scope.filter((s) => !covered.includes(s))
  const canReQa = targets.length > 0 || added.length > 0

  const runFirstQa = async () => {
    setSubmitting(true)
    setActionError(null)
    try {
      setFirstQa(await startFirstQa(projectId, scope))
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : '첫 QA를 시작하지 못했어요.')
    } finally {
      setSubmitting(false)
    }
  }

  const runReQa = async () => {
    setSubmitting(true)
    setActionError(null)
    try {
      reQaTargets.current = targets.map((t) => t.id)
      setReQa(await startReQa(projectId, QA_SCOPES.map((s) => s.value).filter((v) => scope.includes(v) || covered.includes(v))))
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : 'Re-QA를 시작하지 못했어요.')
    } finally {
      setSubmitting(false)
    }
  }

  const failMessage = error ?? loadError

  return (
    <QaShell projectId={projectId}>
      <PageTitle
        crumbs={[{ label: detail?.name ?? '프로젝트', to: `/app/projects/${projectId}` }, { label: reQaMode ? 'Re-QA 실행' : '디자인 QA 실행' }]}
        title={reQaMode ? 'Re-QA 실행' : '디자인 QA 실행'}
        description={reQaMode
          ? '수정 완료된 문제를 첫 QA와 같은 기준으로 다시 검증하고, 새로 고른 검증 범위가 있으면 함께 비교합니다.'
          : '검증 범위와 비교 대상을 선택하면 Figma 디자인과 구현 코드를 자동으로 비교합니다.'}
        actions={justFinished ? <Link to={`/app/projects/${projectId}/results`} className="text-sm font-medium text-qa-primary-hover hover:underline">QA 결과 목록</Link> : undefined}
      />
      {failMessage ? <ErrorCard message={failMessage} projectId={projectId} /> : !detail || !loaded ? <Loading /> : (
        <>
          <WorkflowStepper current={reQaMode ? 5 : justFinished ? 2 : 1} />
          {reQaMode
            ? <ReQaBanner run={reQa} running={reRunning} onRetry={runReQa} canRun={isOwner && canReQa && !submitting} />
            : <FirstQaBanner run={firstQa} running={firstRunning} justFinished={justFinished} projectId={projectId}
                onRetry={runFirstQa} canRun={isOwner && scope.length > 0 && !submitting} />}

          <div className="mt-6 grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
            <div className="flex min-w-0 flex-col gap-6">
              <TargetSummary detail={detail} analysis={analysis} firstQa={firstQa} reQaMode={reQaMode} />
              <ScopeCard scope={scope} onChange={setScope} fixed={reQaMode ? covered : []}
                locked={(reQaMode && (reRunning || covered.length === QA_SCOPES.length)) || firstRunning || justFinished || !isOwner}
                lockReason={justFinished ? '첫 QA 때 고른 범위예요.'
                  : firstRunning || (reQaMode && reRunning) ? '실행 중에는 바꿀 수 없어요.'
                    : !isOwner ? '검증 범위는 소유자만 고를 수 있어요.'
                      : reQaMode && covered.length === QA_SCOPES.length ? '모든 검증 범위를 이미 검사했어요. Re-QA는 수정 완료된 문제를 다시 확인해요.'
                        : null}
                fixedReason={reQaMode ? '잠긴 범위는 지금까지 검사한 범위라 수정 완료된 문제를 다시 확인해요. 새로 고른 범위는 매핑된 화면 전체를 새로 비교해 차이를 새 문제로 만들어요.' : null} />
              {reQaMode ? <ReQaTargets targets={targets} projectId={projectId} /> : <CompareTargets detail={detail} mapping={mapping} scope={scope} />}
              {reQaMode ? (
                <ActionBar
                  title={reRunning ? `Re-QA 실행 중 · ${formatDateTime(reQa!.created_at)} 시작`
                    : `Re-QA 준비 · 재검증 대상 ${targets.length}건 (수정 완료)${added.length > 0 ? ` · 새 검증 범위 ${added.length}개` : ''}`}
                  sub={reRunning ? '실행이 끝날 때까지 새 Re-QA를 시작할 수 없어요.'
                    : !canReQa ? '담당 팀이 문제를 수정 완료로 바꾸거나, 아직 검사하지 않은 검증 범위를 고르면 Re-QA를 실행할 수 있어요.'
                      : [targets.length > 0 ? `수정 완료로 표시된 ${targets.length}건을 다시 비교해요.` : null,
                        added.length > 0 ? `새로 고른 ${added.map(scopeLabel).join('·')}은(는) 매핑된 화면 전체를 비교해 차이를 새 문제로 만들어요.` : null]
                        .filter(Boolean).join(' ')}
                  button={
                    <button type="button" className={PRIMARY_BTN} disabled={!isOwner || reRunning || !canReQa || submitting}
                      onClick={runReQa} title={isOwner ? undefined : 'Re-QA는 프로젝트 소유자만 실행할 수 있어요'}>
                      {reRunning ? <><Icon name="refresh" className="h-4 w-4 animate-spin" />Re-QA 실행 중</> : 'Re-QA 실행'}
                    </button>}
                  error={actionError}
                />
              ) : (
                <ActionBar
                  title={firstRunning ? `첫 QA 실행 중 · ${formatDateTime(firstQa!.created_at)} 시작`
                    : justFinished ? `최초 검사 완료 · ${formatDateTime(firstQa!.finished_at ?? firstQa!.created_at)} 결과 생성`
                      : firstQa?.status === 'failed' ? `첫 QA 실행 실패 · ${formatDateTime(firstQa.finished_at ?? firstQa.created_at)} 중단`
                        : analysis?.status === 'succeeded' ? `첫 QA 실행 준비 완료 · ${formatDate(analysis.finished_at ?? analysis.created_at)}` : '첫 QA 실행 준비 중'}
                  sub={firstRunning ? '실행이 끝날 때까지 새 QA를 시작할 수 없어요. 검증 범위와 비교 대상은 실행 중에 변경할 수 없습니다.'
                    : justFinished ? '결과 확인과 수정 담당 팀 지정은 QA 결과에서 진행합니다.'
                      : firstQa?.status === 'failed' ? '연결 상태를 확인한 뒤 다시 시도해 주세요. 검증 범위와 비교 대상은 그대로 유지돼요.'
                        : analysis?.status !== 'succeeded' ? '작업공간 홈의 "최근 AI 분석"에서 비교 분석을 먼저 마쳐야 해요.'
                          : scope.length === 0 ? '검증 범위를 하나 이상 골라 주세요.'
                            : `검증 범위 ${scope.length}개 · 실행 후 결과는 QA 결과에서 확인할 수 있어요.`}
                  button={justFinished ? (
                    <Link to={`/app/projects/${projectId}/results`} className={PRIMARY_BTN}>QA 결과 보기<Icon name="chevron-right" className="h-4 w-4" /></Link>
                  ) : (
                    <button type="button" className={PRIMARY_BTN} onClick={runFirstQa}
                      disabled={!isOwner || firstRunning || submitting || scope.length === 0 || analysis?.status !== 'succeeded'}
                      title={isOwner ? undefined : '첫 QA는 프로젝트 소유자만 실행할 수 있어요'}>
                      {firstRunning ? <><Icon name="refresh" className="h-4 w-4 animate-spin" />QA 실행 중</>
                        : firstQa?.status === 'failed' ? <><Icon name="refresh" className="h-4 w-4" />다시 시도</> : '첫 QA 실행'}
                    </button>
                  )}
                  error={actionError}
                />
              )}
            </div>
            <div className="flex min-w-0 flex-col gap-6">
              <FigmaCard detail={detail} mapping={mapping} />
              <GithubCard detail={detail} mapping={mapping} />
              <HistoryCard firstQa={firstQa} reQa={reQa} running={firstRunning} />
            </div>
          </div>
        </>
      )}
    </QaShell>
  )
}

function FirstQaBanner({ run, running, justFinished, projectId, onRetry, canRun }: {
  run: ProjectRun | null
  running: boolean
  justFinished: boolean
  projectId: string
  onRetry: () => void
  canRun: boolean
}) {
  if (running && run) {
    return <Banner tone="blue" icon="refresh" title="첫 QA를 실행하고 있어요" badge="첫 QA"
      sub={<>{`Figma 디자인과 구현 코드를 비교하고 있습니다 · ${formatDateTime(run.created_at)} 시작 · 실행이 끝나면 결과를 확인할 수 있어요. 실행 중에는 새 QA를 시작할 수 없습니다.`}<RunProgress run={run} /></>}
      action={<span className={`${PRIMARY_BTN} opacity-40`}><Icon name="refresh" className="h-4 w-4" />QA 실행 중</span>} />
  }
  if (justFinished && run) {
    return <Banner tone="green" icon="check" title="최초 검사 완료" badge="첫 QA"
      sub={`${formatDateTime(run.finished_at ?? run.created_at)} 결과 생성 · 수정이 필요한 항목 ${run.created_count}${demoNote(run.demo)}`}
      action={<Link to={`/app/projects/${projectId}/results`} className={PRIMARY_BTN}>QA 결과 보기<Icon name="chevron-right" className="h-4 w-4" /></Link>} />
  }
  if (run?.status === 'failed') {
    return <Banner tone="red" icon="refresh" title="첫 QA 실행을 완료하지 못했어요" badge="실행 실패"
      sub={`${run.error_message ?? '실행 중 문제가 생겼어요.'} · ${formatDateTime(run.created_at)} 시작 · 결과가 저장되지 않아 QA 실행 이력에 포함되지 않아요.`}
      action={<button type="button" className={PRIMARY_BTN} onClick={onRetry} disabled={!canRun}><Icon name="refresh" className="h-4 w-4" />다시 시도</button>} />
  }
  return null
}

/** 비교 진행률(2026-11-10) — 서버가 묶음이 끝날 때마다 남기는 "비교한 Figma 요소 수/전체". 비교 전(Figma·코드 읽는 중)이면 준비 문구 */
function RunProgress({ run }: { run: ProjectRun }) {
  const total = run.progress_total ?? 0
  if (total <= 0) {
    return run.kind === 'first_qa' || (run.added_scope?.length ?? 0) > 0
      ? <span className="mt-2 block">Figma 화면과 코드를 읽는 중이에요…</span> : null
  }
  const done = Math.min(run.progress_done ?? 0, total)
  const pct = Math.floor((done / total) * 100)
  return (
    <span className="mt-2 block" role="status">
      <span className="flex items-center justify-between font-medium text-black">
        <span>Figma 요소 {done.toLocaleString()} / {total.toLocaleString()}개 비교</span><span>{pct}%</span>
      </span>
      <span className="mt-1 block h-1.5 w-full overflow-hidden rounded-full bg-white">
        <span className="block h-full rounded-full bg-qa-primary transition-[width]" style={{ width: `${pct}%` }} />
      </span>
    </span>
  )
}

function ReQaBanner({ run, running, onRetry, canRun }: { run: ProjectRun | null; running: boolean; onRetry: () => void; canRun: boolean }) {
  if (running && run) {
    return <Banner tone="blue" icon="refresh" title="Re-QA를 실행하고 있어요" badge="Re-QA"
      sub={<>{`수정 완료된 문제를 첫 QA와 같은 기준으로 다시 비교하고 있습니다 · ${formatDateTime(run.created_at)} 시작`}<RunProgress run={run} /></>} />
  }
  if (run?.status === 'failed') {
    return <Banner tone="red" icon="refresh" title="Re-QA를 완료하지 못했어요" badge="실행 실패"
      sub={`${run.error_message ?? '실행 중 문제가 생겼어요.'} · 어떤 문제의 상태도 바뀌지 않았어요.`}
      action={<button type="button" className={PRIMARY_BTN} onClick={onRetry} disabled={!canRun}><Icon name="refresh" className="h-4 w-4" />다시 시도</button>} />
  }
  return null
}

function TargetSummary({ detail, analysis, firstQa, reQaMode }: {
  detail: ProjectDetail
  analysis: ProjectRun | null
  firstQa: ProjectRun | null
  reQaMode: boolean
}) {
  const ok = analysis?.status === 'succeeded'
  return (
    <section className={`${CARD} p-5`}>
      <SectionTitle n={1}>분석 대상 요약</SectionTitle>
      <div className="mt-4 flex flex-col gap-3">
        <SourceRow icon="figma" title="Figma 디자인" sub={detail.figma ? `${detail.figma.file_name} › ${detail.figma.page_name}` : '연결 전'} ok={!!detail.figma} />
        <SourceRow icon="github" title="GitHub 구현" sub={detail.github ? `${detail.github.repo} · ${detail.github.branch}` : '연결 전'} ok={!!detail.github} />
      </div>
      <p className="mt-3 text-xs text-qa-text-sub">
        {reQaMode && firstQa
          ? `재검증 기준: 첫 QA ${formatDateTime(firstQa.finished_at ?? firstQa.created_at)} 결과 (FAIL ${firstQa.created_count}건)`
          : ok ? `비교 분석 완료: ${formatDateTime(analysis!.finished_at ?? analysis!.created_at)} · 이전 QA 기록 ${firstQa?.status === 'succeeded' ? '있음' : '없음'}`
            : '비교 분석을 먼저 마쳐야 첫 QA를 실행할 수 있어요.'}
      </p>
    </section>
  )
}

function SourceRow({ icon, title, sub, ok }: { icon: string; title: string; sub: string; ok: boolean }) {
  return (
    <div className="flex items-center gap-3 rounded-lg border border-qa-border px-4 py-3">
      <span className="flex h-9 w-9 flex-none items-center justify-center rounded-lg bg-qa-surface"><Icon name={icon} className="h-5 w-5" /></span>
      <div className="min-w-0 flex-1">
        <div className="text-sm font-medium text-black">{title}</div>
        <div className="truncate text-xs text-qa-text-sub">{sub}</div>
      </div>
      <span className="text-xs font-medium text-black">{ok ? '연결됨' : '연결 전'}</span>
      <Icon name={ok ? 'circle-check' : 'alert-circle'} className={`h-5 w-5 flex-none ${ok ? 'text-qa-success' : 'text-qa-sub'}`} />
    </div>
  )
}

function scopeLabel(v: QaScope): string {
  return QA_SCOPES.find((s) => s.value === v)?.label ?? v
}

/** fixed — 켠 채로 잠근 범위(Re-QA의 지금까지 검사한 범위). 나머지만 사람이 켜고 끈다 */
function ScopeCard({ scope, onChange, locked, lockReason, fixed, fixedReason }: {
  scope: QaScope[]
  onChange: (s: QaScope[]) => void
  locked: boolean
  lockReason: string | null
  fixed: QaScope[]
  fixedReason: string | null
}) {
  const toggle = (v: QaScope) => {
    if (fixed.includes(v)) return
    onChange(scope.includes(v) ? scope.filter((s) => s !== v) : QA_SCOPES.map((s) => s.value).filter((s) => s === v || scope.includes(s)))
  }
  return (
    <section className={`${CARD} p-5`}>
      <SectionTitle n={2} aside={<span className="text-xs text-qa-text-sub">{scope.length}개 선택</span>}>QA 검증 범위</SectionTitle>
      <p className="mt-1 text-xs text-qa-text-sub">고른 범위만 Figma 값과 코드 값을 비교해요.</p>
      <fieldset disabled={locked} className="mt-4 grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
        <legend className="sr-only">QA 검증 범위</legend>
        {QA_SCOPES.map((s) => {
          const isFixed = fixed.includes(s.value)
          const isNew = fixed.length > 0 && !isFixed && scope.includes(s.value)
          return (
          <label key={s.value} className={`flex items-start gap-2 text-sm text-black ${locked || isFixed ? 'cursor-default' : 'cursor-pointer'}`}>
            <input type="checkbox" checked={isFixed || scope.includes(s.value)} onChange={() => toggle(s.value)} disabled={isFixed}
              className="mt-0.5 h-4 w-4 flex-none accent-qa-primary-hover disabled:opacity-70" />
            <span>
              <span className="flex items-center gap-1.5">
                {s.label}
                {isFixed && <Icon name="lock" className="h-3.5 w-3.5 text-qa-text-sub" />}
                {isNew && <span className="rounded bg-[#eef4ff] px-1.5 py-0.5 text-[11px] font-medium text-qa-primary-hover">새 범위</span>}
              </span>
              <span className="block text-xs text-qa-text-sub">{s.detail}</span>
            </span>
          </label>
          )
        })}
      </fieldset>
      {locked && lockReason && (
        <p className="mt-3 flex items-center gap-1.5 text-xs text-qa-text-sub"><Icon name="lock" className="h-3.5 w-3.5 flex-none" />{lockReason}</p>
      )}
      {!locked && fixedReason && (
        <p className="mt-3 flex items-start gap-1.5 text-xs text-qa-text-sub"><Icon name="lock" className="mt-px h-3.5 w-3.5 flex-none" />{fixedReason}</p>
      )}
      {!locked && fixed.length === 0 && scope.length === 0 && <p role="alert" className="mt-3 text-xs text-qa-warning">검증 범위를 하나 이상 골라 주세요.</p>}
    </section>
  )
}

function CompareTargets({ detail, mapping, scope }: { detail: ProjectDetail; mapping: MappingList | null; scope: QaScope[] }) {
  const frames = mapping?.items.length ?? 0
  const nodes = mapping?.items.reduce((n, f) => n + f.nodes.length, 0) ?? 0
  return (
    <section className={`${CARD} p-5`}>
      <SectionTitle n={3}>비교 대상</SectionTitle>
      <div className="mt-4 rounded-lg bg-qa-surface px-4 py-1">
        <KeyValue rows={[
          { label: 'Figma 디자인', value: detail.figma ? `${detail.figma.file_name} › ${detail.figma.page_name}` : '-' },
          { label: 'GitHub 구현', value: detail.github ? `${detail.github.repo} · ${detail.github.branch}` : '-' },
          { label: '비교 대상 UI 요소', value: mapping ? `화면 ${frames}개 · 요소 ${nodes}개 · 검증 범위 ${scope.length}개` : '비교 분석 후 표시' },
        ]} />
      </div>
    </section>
  )
}

function ReQaTargets({ targets, projectId }: { targets: QaIssue[]; projectId: string }) {
  return (
    <section className={`${CARD} p-5`}>
      <SectionTitle n={3} aside={<span className="text-xs text-qa-text-sub">수정 완료 {targets.length}건</span>}>재검증 대상</SectionTitle>
      {targets.length === 0 ? (
        <p className="mt-4 rounded-lg bg-qa-surface px-4 py-3 text-sm text-qa-text-sub">수정 완료된 첫 QA 문제가 없어요.</p>
      ) : (
        <ul className="mt-4 flex flex-col gap-2">
          {targets.map((t) => (
            <li key={t.id}>
              <Link to={`/app/projects/${projectId}/issues/${t.id}`} className="flex items-center gap-3 rounded-lg border border-qa-border px-4 py-2.5 hover:bg-qa-surface">
                <span className="text-xs font-medium text-qa-text-sub">#{t.id}</span>
                <span className="min-w-0 flex-1 truncate text-sm text-black">{issueTitle(t)}</span>
                <Icon name="chevron-right" className="h-4 w-4 text-qa-text-sub" />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function ActionBar({ title, sub, button, error }: { title: string; sub: string; button: React.ReactNode; error: string | null }) {
  return (
    <section className={`${CARD} flex flex-wrap items-center gap-4 px-5 py-4`}>
      <div className="min-w-[240px] flex-1">
        <div className="text-sm font-bold text-black">{title}</div>
        <div className="mt-1 text-xs text-qa-text-sub">{sub}</div>
        {error && <div role="alert" className="mt-1 text-xs text-qa-warning">{error}</div>}
      </div>
      {button}
    </section>
  )
}

function StatusHead({ title, ok }: { title: string; ok: boolean }) {
  return (
    <div className="flex items-center gap-2">
      <h2 className="text-base font-bold text-black">{title}</h2>
      <span className={`flex h-[22px] items-center gap-1 rounded-full px-2 text-xs font-medium ${ok ? 'bg-qa-success/10 text-qa-success' : 'bg-qa-surface text-qa-text-sub'}`}>
        <span className={`h-1.5 w-1.5 rounded-full ${ok ? 'bg-qa-success' : 'bg-qa-sub'}`} />{ok ? '연결됨' : '연결 전'}
      </span>
    </div>
  )
}

function FigmaCard({ detail, mapping }: { detail: ProjectDetail; mapping: MappingList | null }) {
  const f = detail.figma
  return (
    <section className={`${CARD} p-5`}>
      <StatusHead title="Figma 연결 상태" ok={!!f} />
      <div className="mt-3">
        <KeyValue rows={[
          { label: '파일명', value: f?.file_name ?? '-' },
          { label: f?.scope === 'section' ? '섹션' : '페이지', value: f?.page_name ?? '-' },
          { label: '연결일', value: f ? formatDate(f.connected_at) : '-' },
          { label: '매핑된 화면 수', value: mapping ? `${mapping.items.length}` : '-' },
        ]} />
      </div>
    </section>
  )
}

function GithubCard({ detail, mapping }: { detail: ProjectDetail; mapping: MappingList | null }) {
  const g = detail.github
  return (
    <section className={`${CARD} p-5`}>
      <StatusHead title="GitHub 연결 상태" ok={!!g} />
      <div className="mt-3">
        <KeyValue rows={[
          { label: '저장소', value: g?.repo ?? '-' },
          { label: '브랜치', value: g?.branch ?? '-' },
          { label: '분석한 커밋', value: mapping?.commit_sha ? <span className="font-mono">{mapping.commit_sha.slice(0, 7)}</span> : '미확인' },
        ]} />
      </div>
    </section>
  )
}

function HistoryCard({ firstQa, reQa, running }: { firstQa: ProjectRun | null; reQa: ProjectRun | null; running: boolean }) {
  const done = firstQa?.status === 'succeeded'
  return (
    <section className={`${CARD} p-5`}>
      <h2 className="text-base font-bold text-black">이전 QA 실행 이력</h2>
      <div className="mt-3 flex flex-col items-center rounded-lg bg-qa-surface px-4 py-5 text-center">
        <Icon name="inbox" className="h-6 w-6 text-gray-900" />
        {done ? (
          <>
            <div className="mt-2 text-sm font-medium text-black">첫 QA · {formatDateTime(firstQa.finished_at ?? firstQa.created_at)}</div>
            <div className="mt-1 text-xs text-qa-text-sub">
              FAIL {firstQa.created_count}건 발견{reQa?.status === 'succeeded'
                ? ` · 최근 Re-QA ${formatDateTime(reQa.finished_at ?? reQa.created_at)} (해결 ${reQa.created_count} · 재실패 ${reQa.failed_count ?? 0}${reQa.added_scope?.length ? ` · 새 범위 ${reQa.added_scope.map(scopeLabel).join('·')}에서 ${reQa.found_count ?? 0}건 발견` : ''})`
                : ' · 아직 Re-QA 기록 없음'}
            </div>
          </>
        ) : (
          <>
            <div className="mt-2 text-sm font-medium text-black">QA 실행 이력이 없습니다</div>
            <div className="mt-1 text-xs text-qa-text-sub">
              {running ? '첫 QA가 실행 중이에요.' : firstQa?.status === 'failed' ? '실패한 실행은 이력에 기록되지 않아요.' : '이번 실행이 이 프로젝트의 첫 QA입니다.'}
            </div>
          </>
        )}
      </div>
    </section>
  )
}
