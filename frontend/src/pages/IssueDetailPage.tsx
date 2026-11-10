import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  ApiError, changeIssueStatus, deleteFixLink, formatDate, formatDateTime, getCodeTrace, getIssue, listVerifications, putFixLink,
  roleLabel, TEAM_LABELS, type CodeTrace, type IssueStatus, type QaIssue, type Verification,
} from '../lib/api'
import { CodeLinkCard, GithubTraceCard, IssueKey, KvRows, PANEL } from '../components/qa/CodeTrace'
import { Icon } from '../components/common/Icon'
import { CARD, WorkflowStepper } from '../components/workspace/WorkspaceCards'
import {
  ErrorCard, KeyValue, Loading, OUTLINE_BTN, PRIMARY_BTN, PROGRESS, ProgressChip, QaShell, TeamTag, Verdict, issueFrame, issueParts, issueTitle,
  loadErrorMessage, useProject,
} from '../components/qa/QaParts'

// QA 문제 상세(시안 ISS-02 미해결·ISS-03 수정 중·ISS-04 수정 완료·재검증 대기, SPEC AC6·AC13·AC28).
// 진행 상태(in_progress·fixed)는 역할이 그 이슈의 담당 팀인 멤버만 바꿀 수 있다 — 소유자도 역할이 같아야 한다(AC13).
// 시안과 다른 점:
//  - "매핑 신뢰도"·"Props·관련 스타일 파일"·라인 범위는 서버가 주지 않아 뺐다. 코드 파일·커밋·PR은 코드 추적(AC34)으로 보여 준다
//  - 처리 이력은 상태 변경과 재검증 기록만 남는다(담당 팀 지정은 서버가 이력으로 기록하지 않는다)
//  - 시안의 [수정 시작]만 있는 ISS-02와 달리 스펙대로 수정 중을 건너뛰고 바로 수정 완료로 바꿀 수도 있다(AC6)
//  - 사람이 직접 등록한 이슈의 재검증(AC5, 등록자가 재현 절차로 확인)은 이 화면에서 아직 하지 않는다

export function IssueDetailPage() {
  const { projectId = '', issueId = '' } = useParams()
  const { me, detail, error, reload } = useProject(projectId)
  const [issue, setIssue] = useState<QaIssue | null>(null)
  const [verifications, setVerifications] = useState<Verification[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  /** 코드 추적(AC34) — GitHub를 읽어 늦게 올 수 있어 화면은 먼저 그린다 */
  const [trace, setTrace] = useState<CodeTrace | null>(null)

  const loadTrace = useCallback(() => {
    getCodeTrace(projectId, issueId).then(setTrace).catch(() => setTrace(null))
  }, [projectId, issueId])

  const load = useCallback(async () => {
    try {
      const [i, v] = await Promise.all([getIssue(projectId, issueId), listVerifications(projectId, issueId)])
      setIssue(i)
      setVerifications(v)
    } catch (e) {
      setLoadError(loadErrorMessage(e))
    }
  }, [projectId, issueId])

  useEffect(() => {
    if (me) {
      void load()
      loadTrace()
    }
  }, [me, load, loadTrace])

  if (!me) return null
  const failMessage = error ?? loadError
  const isOwner = !!detail && detail.owner_id === me.id
  const canChange = !!detail && !!issue?.assigned_team && detail.my_role === issue.assigned_team

  /** 수정 완료로 바꿀 때 수정 PR·커밋 링크를 함께 붙일 수 있다 — 링크가 틀리면 상태도 바꾸지 않는다 */
  const change = async (status: 'in_progress' | 'fixed', fixLink?: string) => {
    setSaving(true)
    setActionError(null)
    try {
      if (fixLink) setTrace(await putFixLink(projectId, issueId, fixLink))
      setIssue(await changeIssueStatus(projectId, issueId, status))
      void reload()
      loadTrace()
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : '진행 상태를 바꾸지 못했어요.')
    } finally {
      setSaving(false)
    }
  }

  const step = !issue ? 4 : issue.status === 'resolved' ? 6 : issue.status === 'fixed' ? 5 : issue.assigned_team ? 4 : 3
  const latestReQa = verifications.find((v) => v.source === 're_qa') ?? null

  return (
    <QaShell projectId={projectId}>
      {failMessage ? <ErrorCard message={failMessage} projectId={projectId} /> : !detail || !issue ? <Loading /> : (
        <>
          <section className="rounded-2xl border border-qa-border bg-gradient-to-r from-white to-qa-primary/10 px-6 py-5">
            <nav className="flex items-center gap-1 text-xs">
              <Link to={`/app/projects/${projectId}/queue`} className="text-qa-text-sub hover:underline">QA Queue</Link>
              <Icon name="chevron-right" className="h-3 w-3 text-qa-text-sub" />
              <span className="font-medium text-black">이슈 #{issue.id}</span>
            </nav>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <h1 className="mr-2 text-[28px] font-bold leading-9 text-black">이슈 #{issue.id}</h1>
              {issue.source === 'manual'
                ? <span className="rounded bg-[#7c3aed]/10 px-1.5 text-xs font-medium leading-5 text-[#7c3aed]">수동 QA</span>
                : <Verdict pass={issue.status === 'resolved'} />}
              <ProgressChip status={issue.status} />
              <TeamTag team={issue.assigned_team} />
            </div>
            <div className="mt-1 text-sm font-medium text-black">{issueTitle(issue)}</div>
            {trace && <div className="mt-2"><IssueKey issueKey={trace.issue_key} /></div>}
            <div className="mt-1 flex flex-wrap items-start justify-between gap-3">
              <div className="text-xs text-qa-text-sub">
                등록 일시: {formatDateTime(issue.created_at)} · {issue.source === 'first_qa' ? '첫 QA 실행' : '직접 등록'}
              </div>
              {/* 시안 ISS-02는 진행 상태 버튼을 머리말 오른쪽에 둔다 — 담당 팀 카드는 배정 정보만 */}
              <div className="w-full max-w-[360px]">
                <StatusActions issue={issue} canChange={canChange} saving={saving} isOwner={isOwner} projectId={projectId} onChange={change}
                  issueKey={trace?.issue_key ?? null} />
                {issue.assigned_team && !canChange && issue.status !== 'fixed' && issue.status !== 'resolved' && (
                  <p className="mt-1.5 text-right text-xs text-qa-text-sub">진행 상태는 {TEAM_LABELS[issue.assigned_team]} 역할 멤버만 바꿀 수 있어요 (내 역할: {roleLabel(detail.my_role)}).</p>
                )}
                {!issue.assigned_team && <p className="mt-1.5 text-right text-xs text-qa-text-sub">담당 팀을 먼저 지정해야 수정을 시작할 수 있어요.</p>}
                {actionError && <p role="alert" className="mt-1.5 text-right text-xs text-qa-warning">{actionError}</p>}
              </div>
            </div>
          </section>

          <WorkflowStepper current={step} />

          <div className="mt-6 grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_440px]">
            <div className="flex min-w-0 flex-col gap-6">
              <Evidence issue={issue} latestReQa={latestReQa} projectId={projectId} fileKey={detail.figma?.file_key ?? null} trace={trace} />
              <History issue={issue} verifications={verifications} />
            </div>

            <div className="flex min-w-0 flex-col gap-6">
              <section className={PANEL}>
                <div className="flex items-center justify-between gap-2">
                  <h2 className="text-base font-bold leading-6 text-black">수정 담당 팀</h2>
                  {issue.assigned_team
                    ? <span className="inline-flex h-6 items-center gap-1 rounded-full bg-qa-success/10 px-2 text-xs font-medium leading-4 text-black"><Icon name="check" className="h-3 w-3 text-qa-success" />배정 완료</span>
                    : <span className="inline-flex h-6 items-center rounded-full bg-qa-warning/10 px-2 text-xs font-medium leading-4 text-black">미배정</span>}
                </div>
                <div className="mt-4">
                  <KvRows rows={[
                    { label: '담당 팀', value: <TeamTag team={issue.assigned_team} /> },
                    { label: '배정 방식', value: issue.assigned_team ? '사람이 직접 지정' : '-' },
                    // 배정 시각은 서버가 기록하지 않는다 — 지어내지 않는다
                    { label: '배정 일시', value: '-' },
                    { label: '수정 진행', value: <ProgressChip status={issue.status} /> },
                  ]} />
                </div>
                <p className="mt-4 text-xs leading-4 text-qa-text-sub">배정 상태와 수정 진행 상태는 별도로 관리돼요. 수정 진행 상태는 QA Queue에서 확인할 수 있습니다.</p>
                <Link to={`/app/projects/${projectId}/results?issue=${issue.id}`}
                  className="mt-4 flex h-10 w-full items-center justify-center rounded-lg border border-qa-primary bg-white text-sm font-medium text-qa-primary hover:bg-qa-primary/5">
                  {issue.assigned_team ? '담당 팀 변경' : '담당 팀 지정'}
                </Link>
                <Link to={`/app/projects/${projectId}/results?issue=${issue.id}`}
                  className="mt-4 inline-flex items-center text-xs font-medium leading-4 text-qa-primary hover:underline">
                  QA 결과 목록에서 이 문제 보기<Icon name="chevron-right" className="h-3.5 w-3.5" />
                </Link>
              </section>

              <FigmaElement issue={issue} fileKey={detail.figma?.file_key ?? null} />
              <CodeLinkCard trace={trace} manual={issue.source === 'manual'} />
              <GithubTraceCard trace={trace} status={issue.status} canAttach={canChange}
                onAttach={async (url) => setTrace(await putFixLink(projectId, issueId, url))}
                onDetach={async () => { await deleteFixLink(projectId, issueId); loadTrace() }} />
            </div>
          </div>
        </>
      )}
    </QaShell>
  )
}

function StatusActions({ issue, canChange, saving, isOwner, projectId, onChange, issueKey }: {
  issue: QaIssue
  canChange: boolean
  saving: boolean
  isOwner: boolean
  projectId: string
  onChange: (s: 'in_progress' | 'fixed', fixLink?: string) => void
  issueKey: string | null
}) {
  const disabled = !canChange || saving
  const [confirming, setConfirming] = useState(false)
  const [link, setLink] = useState('')
  // 수정 완료로 바꿀 때 고친 PR·커밋 링크를 함께 붙일 수 있다(선택, AC34) — 커밋·PR에 이슈 키를 넣었다면 비워도 된다
  if (confirming && (issue.status === 'needs_check' || issue.status === 'needs_recheck' || issue.status === 'in_progress')) {
    return (
      <form className="mt-3 rounded-lg border border-qa-border p-3" onSubmit={(e) => { e.preventDefault(); onChange('fixed', link.trim() || undefined) }}>
        <label className="block text-xs font-medium text-black">
          고친 PR·커밋 링크 <span className="font-normal text-qa-text-sub">선택</span>
          <input value={link} onChange={(e) => setLink(e.target.value)} disabled={saving} autoFocus
            placeholder="https://github.com/owner/repo/pull/42"
            className="mt-1 h-9 w-full rounded-lg border border-qa-border bg-white px-3 text-xs font-normal outline-none focus:border-qa-primary" />
        </label>
        <p className="mt-1 text-xs text-qa-text-sub">{issueKey ? `커밋 메시지나 PR 제목에 ${issueKey}를 넣었다면 비워 둬도 연결돼요.` : '비워 둬도 돼요.'}</p>
        <div className="mt-2 flex gap-2">
          <button type="button" className={`${OUTLINE_BTN} flex-1`} disabled={saving} onClick={() => { setConfirming(false); setLink('') }}>취소</button>
          <button type="submit" className={`${PRIMARY_BTN} flex-1`} disabled={disabled}>{saving ? '바꾸는 중…' : '수정 완료로 변경'}</button>
        </div>
      </form>
    )
  }
  if (issue.status === 'needs_check' || issue.status === 'needs_recheck') {
    return (
      <div className="mt-3 flex gap-2">
        <button type="button" className={`${PRIMARY_BTN} flex-1`} disabled={disabled} onClick={() => onChange('in_progress')}>수정 시작</button>
        <button type="button" className={`${OUTLINE_BTN} flex-1`} disabled={disabled} onClick={() => setConfirming(true)}>수정 완료로 변경</button>
      </div>
    )
  }
  if (issue.status === 'in_progress') {
    return <button type="button" className={`${PRIMARY_BTN} mt-3 w-full`} disabled={disabled} onClick={() => setConfirming(true)}>수정 완료로 변경</button>
  }
  if (issue.status === 'fixed') {
    return issue.source === 'first_qa' ? (
      <div className="mt-3 rounded-lg bg-[#f08c00]/10 px-3 py-2.5 text-xs leading-4 text-black">
        수정 완료 · Re-QA를 기다리고 있어요. {isOwner ? '' : '소유자가 Re-QA를 실행하면 결과가 반영돼요.'}
        {isOwner && <Link to={`/app/projects/${projectId}/qa`} className={`${PRIMARY_BTN} mt-2 w-full`}><Icon name="refresh" className="h-4 w-4" />Re-QA 실행</Link>}
      </div>
    ) : (
      <div className="mt-3 rounded-lg bg-[#f08c00]/10 px-3 py-2.5 text-xs text-black">수정 완료 · 등록자의 재검증을 기다리고 있어요.</div>
    )
  }
  return <div className="mt-3 rounded-lg bg-qa-success/10 px-3 py-2.5 text-xs text-black">재검증을 통과해 해결된 문제예요.</div>
}

// ---------- 시안 ISS-02 패널(Figma 디자인 요소 카드 제외) — 카드 테두리 1px·안쪽 20·모서리 12(비교 표만 16)·카드 사이 24 ----------

const PANEL_TITLE = 'text-base font-bold leading-6 text-black'

/** 표의 속성 이름(시안은 영문) — 색은 글자·배경·테두리, 간격은 padding·gap을 값 앞머리로 구분한다 */
function propertyName(property: string, expected: string): string {
  if (property === 'color') return expected.startsWith('배경 ') ? 'Fill' : expected.startsWith('테두리 ') ? 'Border Color' : 'Text Color'
  if (property === 'spacing') return expected.startsWith('gap ') ? 'Gap' : 'Padding'
  return ({ radius: 'Border Radius', font_weight: 'Font Weight', font_size: 'Font Size', font_family: 'Font Family', shadow: 'Shadow', text: 'Text' } as Record<string, string>)[property] ?? property
}

/** 값 앞머리(배경·글자·테두리·padding·gap)는 속성 이름이 대신하므로 뗀다 */
function bareValue(v: string): string {
  return v.replace(/^(배경|글자|테두리) /, '').replace(/^(padding|gap) /, '')
}

function summaryLabel(property: string, expected: string): string {
  if (property === 'color') return '색상 불일치'
  if (property === 'spacing') return expected.startsWith('gap ') ? '간격 불일치' : '패딩 불일치'
  if (property === 'radius') return '모서리 불일치'
  if (property === 'font_weight' || property === 'font_size' || property === 'font_family') return '타이포그래피 차이'
  if (property === 'text') return '문구 차이'
  return '스타일 차이'
}

function ResultMark({ ok }: { ok: boolean }) {
  return ok ? (
    <span className="flex h-5 w-5 items-center justify-center rounded-full bg-qa-success text-white" aria-label="해결"><Icon name="check" className="h-3 w-3" /></span>
  ) : (
    <span className="flex h-5 w-5 items-center justify-center rounded-full bg-qa-warning text-[13px] font-bold leading-none text-white" aria-label="불일치">!</span>
  )
}

const COLS = 'grid grid-cols-[140px_minmax(0,1fr)_minmax(0,1fr)_40px] items-center gap-3 px-3'

function Evidence({ issue, latestReQa, projectId, fileKey, trace }: {
  issue: QaIssue; latestReQa: Verification | null; projectId: string; fileKey: string | null; trace: CodeTrace | null
}) {
  const m = issue.style_mismatch
  const parts = issueParts(issue)
  const frame = issueFrame(issue, fileKey)
  // 같은 Figma 요소의 다른 첫 QA 이슈(AC34)도 행으로 — 각 행은 그 이슈로 간다
  const rows = m ? [
    { id: issue.id, property: m.property, expected: m.expected_value, actual: m.actual_value, ok: issue.status === 'resolved' },
    ...(trace?.related ?? []).map((r) => ({ id: r.issue_id, property: r.property, expected: r.expected_value, actual: r.actual_value, ok: r.status === 'resolved' })),
  ] : []
  const open = rows.filter((r) => !r.ok)
  const summary = m ? [...new Set(rows.map((r) => summaryLabel(r.property, r.expected)))].join(' · ') : issue.description
  return (
    <>
      <section className="rounded-2xl border border-qa-border bg-white p-5">
        <h2 className={PANEL_TITLE}>디자인 기준 vs 구현 실제값</h2>
        {m ? (
          <div className="mt-4 overflow-x-auto rounded-lg border border-qa-border">
            <div className="min-w-[520px]">
              <div className={`${COLS} border-b border-qa-border bg-qa-surface py-2 text-xs font-medium leading-4 text-qa-text-sub`}>
                <span>속성</span><span>디자인 기준</span><span>구현 실제값</span><span>판정</span>
              </div>
              {rows.map((r, idx) => (
                <div key={r.id} className={`${COLS} py-3 text-sm leading-5 ${idx < rows.length - 1 ? 'border-b border-qa-border' : ''}`}>
                  {r.id === issue.id
                    ? <span className="truncate font-medium text-black">{propertyName(r.property, r.expected)}</span>
                    : <Link to={`/app/projects/${projectId}/issues/${r.id}`} title={`이슈 #${r.id}`} className="truncate font-medium text-black hover:underline">{propertyName(r.property, r.expected)}</Link>}
                  <span className="truncate text-qa-text-sub">{bareValue(r.expected)}</span>
                  <span className="truncate text-qa-text-sub">{bareValue(r.actual)}</span>
                  <ResultMark ok={r.ok} />
                </div>
              ))}
            </div>
          </div>
        ) : <p className="mt-4 text-sm text-black">{issue.description}</p>}
        {latestReQa && (
          <Link to={`/app/projects/${projectId}/issues/${issue.id}/re-qa`}
            className="mt-4 flex items-center justify-between gap-2 rounded-lg bg-qa-surface px-4 py-2.5 text-xs hover:bg-qa-primary/5">
            <span className="flex items-center gap-2 text-black">
              최근 Re-QA {formatDateTime(latestReQa.verified_at)} · <Verdict pass={latestReQa.result === 'pass'} />
              {latestReQa.observed_value && <span className="text-qa-text-sub">다시 읽은 구현값 {latestReQa.observed_value}</span>}
            </span>
            <span className="flex items-center font-medium text-qa-primary-hover">Re-QA 결과 보기<Icon name="chevron-right" className="h-3.5 w-3.5" /></span>
          </Link>
        )}
      </section>

      <section className="rounded-xl border border-qa-warning bg-qa-warning/10 p-5">
        <div className="flex items-center gap-2">
          <h2 className={PANEL_TITLE}>불일치 요약</h2>
          {m && (
            <span className="inline-flex h-6 items-center gap-1.5 rounded-full bg-qa-warning/10 px-2 text-xs font-medium leading-4 text-black">
              <span className="h-1.5 w-1.5 rounded-full bg-qa-warning" />{open.length}건
            </span>
          )}
        </div>
        <p className="mt-4 text-sm leading-5 text-black">{summary}</p>
        <div className="mt-4 grid grid-cols-1 gap-6 sm:grid-cols-3">
          <Fact label="대상 컴포넌트" value={trace?.code?.symbol ?? parts.element} />
          <Fact label="Figma 프레임" value={frame ? (
            frame.href ? <a href={frame.href} target="_blank" rel="noreferrer" className="hover:underline">{frame.name}</a> : frame.name
          ) : '연결된 프레임 없음'} />
          {issue.source === 'first_qa'
            ? <Fact label={`분석 기준 커밋 (${formatDateTime(issue.created_at)})`} value={trace?.code?.commit_sha
              ? <span className="font-mono">{trace.code.commit_sha.slice(0, 7)}</span> : '미확인'} />
            : <Fact label="재현 절차" value={issue.repro_steps.join(' → ')} />}
        </div>
      </section>
    </>
  )
}

function Fact({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="text-xs leading-4 text-qa-text-sub">{label}</div>
      <div className="mt-1 break-words text-sm font-medium leading-5 text-black">{value}</div>
    </div>
  )
}

const HISTORY_TEXT: Record<IssueStatus, string> = {
  needs_check: '이슈 생성',
  in_progress: '수정 시작',
  fixed: '수정 완료로 변경 (Re-QA로 확인 필요)',
  needs_recheck: '재검증 실패 — 재확인 필요',
  resolved: '재검증 통과 — 해결',
}

function History({ issue, verifications }: { issue: QaIssue; verifications: Verification[] }) {
  const rows = [
    ...issue.status_history.map((h, idx) => ({
      at: h.changed_at,
      key: `h${idx}`,
      title: idx === 0 && issue.source === 'first_qa' ? '이슈 최초 생성 — 첫 QA 실행에서 발견' : HISTORY_TEXT[h.status],
      sub: PROGRESS[h.status].label,
    })),
    ...verifications.map((v) => ({
      at: v.verified_at,
      key: `v${v.id}`,
      title: `${v.source === 're_qa' ? 'Re-QA' : '재검증'} ${v.result === 'pass' ? 'PASS' : 'FAIL'}${v.observed_value ? ` — 다시 읽은 구현값 ${v.observed_value}` : ''}`,
      sub: v.source === 're_qa' ? '자동 Re-QA' : '등록자 재검증',
    })),
  ].sort((a, b) => (a.at < b.at ? 1 : a.at > b.at ? -1 : a.key < b.key ? 1 : -1))
  return (
    <section className={PANEL}>
      <h2 className={PANEL_TITLE}>처리 이력</h2>
      <ol className="mt-4 flex flex-col gap-4">
        {rows.map((r) => (
          <li key={r.key} className="flex gap-3">
            <span className="flex h-5 w-5 flex-none items-center justify-center rounded-full border border-qa-primary"><span className="h-1.5 w-1.5 rounded-full bg-qa-primary" /></span>
            <div className="min-w-0">
              <div className="text-sm leading-5 text-black">{r.title}</div>
              <div className="text-xs leading-4 text-qa-text-sub">{formatDateTime(r.at)} · {r.sub}</div>
            </div>
          </li>
        ))}
      </ol>
    </section>
  )
}

function FigmaElement({ issue, fileKey }: { issue: QaIssue; fileKey: string | null }) {
  const m = issue.style_mismatch
  const parts = issueParts(issue)
  const frame = issueFrame(issue, fileKey)
  const href = frame?.href ?? null
  return (
    <section className={`${CARD} p-5`}>
      <h2 className="text-base font-bold text-black">Figma 디자인 요소</h2>
      <div className="mt-3">
        <KeyValue rows={[
          { label: 'Frame', value: frame?.name ?? '-' },
          { label: '요소', value: parts.element },
          { label: '프레임 ID', value: m?.frame_id ?? issue.manual_qa?.figma_frame?.frame_id ?? '-' },
          { label: '등록일', value: formatDate(issue.created_at) },
        ]} />
      </div>
      {href && (
        <a href={href} target="_blank" rel="noreferrer" className="mt-3 inline-flex items-center gap-1 text-xs font-medium text-qa-primary-hover hover:underline">
          Figma에서 프레임 열기<Icon name="external-link" className="h-3.5 w-3.5" />
        </a>
      )}
    </section>
  )
}
