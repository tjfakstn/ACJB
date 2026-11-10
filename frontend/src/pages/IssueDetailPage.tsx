import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  ApiError, changeIssueStatus, figmaFrameUrl, formatDate, formatDateTime, getIssue, listVerifications, PROPERTY_LABELS, roleLabel,
  TEAM_LABELS, type IssueStatus, type QaIssue, type Verification,
} from '../lib/api'
import { Icon } from '../components/common/Icon'
import { CARD, WorkflowStepper } from '../components/workspace/WorkspaceCards'
import {
  ErrorCard, KeyValue, Loading, OUTLINE_BTN, PRIMARY_BTN, PROGRESS, ProgressChip, QaShell, TeamTag, Verdict, issueParts, issueTitle,
  loadErrorMessage, useProject,
} from '../components/qa/QaParts'

// QA 문제 상세(시안 ISS-02 미해결·ISS-03 수정 중·ISS-04 수정 완료·재검증 대기, SPEC AC6·AC13·AC28).
// 진행 상태(in_progress·fixed)는 역할이 그 이슈의 담당 팀인 멤버만 바꿀 수 있다 — 소유자도 역할이 같아야 한다(AC13).
// 시안과 다른 점:
//  - "매핑 신뢰도"·"Props·라인·관련 스타일 파일"·"GitHub 커밋·PR"은 서버가 주지 않아 뺐다(커밋·PR 연동은 v1 비포함)
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
    if (me) void load()
  }, [me, load])

  if (!me) return null
  const failMessage = error ?? loadError
  const isOwner = !!detail && detail.owner_id === me.id
  const canChange = !!detail && !!issue?.assigned_team && detail.my_role === issue.assigned_team

  const change = async (status: 'in_progress' | 'fixed') => {
    setSaving(true)
    setActionError(null)
    try {
      setIssue(await changeIssueStatus(projectId, issueId, status))
      void reload()
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
            <div className="mt-1 text-xs text-qa-text-sub">
              등록 일시: {formatDateTime(issue.created_at)} · {issue.source === 'first_qa' ? '첫 QA 실행' : '직접 등록'}
            </div>
          </section>

          <WorkflowStepper current={step} />

          <div className="mt-6 grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_380px]">
            <div className="flex min-w-0 flex-col gap-6">
              <Evidence issue={issue} latestReQa={latestReQa} projectId={projectId} />
              <History issue={issue} verifications={verifications} />
            </div>

            <div className="flex min-w-0 flex-col gap-6">
              <section className={`${CARD} p-5`}>
                <div className="flex items-center justify-between gap-2">
                  <h2 className="text-base font-bold text-black">수정 담당 팀</h2>
                  {issue.assigned_team
                    ? <span className="inline-flex h-[22px] items-center gap-1 rounded-full bg-qa-success/10 px-2 text-xs font-medium text-qa-success"><Icon name="check" className="h-3 w-3" />배정 완료</span>
                    : <span className="inline-flex h-[22px] items-center rounded-full bg-qa-warning/10 px-2 text-xs font-medium text-[#c8161a]">미배정</span>}
                </div>
                <div className="mt-3">
                  <KeyValue rows={[
                    { label: '담당 팀', value: <TeamTag team={issue.assigned_team} /> },
                    { label: '배정 방식', value: issue.assigned_team ? '멤버가 직접 지정' : '-' },
                    { label: '수정 진행', value: <ProgressChip status={issue.status} /> },
                  ]} />
                </div>
                <p className="mt-3 text-xs leading-4 text-qa-text-sub">
                  {!issue.assigned_team
                    ? '담당 팀을 먼저 지정해야 수정을 시작할 수 있어요.'
                    : canChange
                      ? `${TEAM_LABELS[issue.assigned_team]} 역할인 내가 진행 상태를 바꿀 수 있어요.`
                      : `진행 상태는 ${TEAM_LABELS[issue.assigned_team]} 역할 멤버만 바꿀 수 있어요 (내 역할: ${roleLabel(detail.my_role)}).`}
                </p>
                <StatusActions issue={issue} canChange={canChange} saving={saving} isOwner={isOwner} projectId={projectId} onChange={change} />
                {actionError && <p role="alert" className="mt-2 text-xs text-qa-warning">{actionError}</p>}
                {issue.source === 'first_qa' && (
                  <>
                    <Link to={`/app/projects/${projectId}/results?issue=${issue.id}`} className={`${OUTLINE_BTN} mt-3 w-full`}>
                      {issue.assigned_team ? '담당 팀 변경' : '담당 팀 지정'}
                    </Link>
                    <Link to={`/app/projects/${projectId}/results?issue=${issue.id}`}
                      className="mt-3 inline-flex items-center text-xs font-medium text-qa-primary-hover hover:underline">
                      QA 결과 목록에서 이 문제 보기<Icon name="chevron-right" className="h-3.5 w-3.5" />
                    </Link>
                  </>
                )}
              </section>

              <FigmaElement issue={issue} fileKey={detail.figma?.file_key ?? null} />
            </div>
          </div>
        </>
      )}
    </QaShell>
  )
}

function StatusActions({ issue, canChange, saving, isOwner, projectId, onChange }: {
  issue: QaIssue
  canChange: boolean
  saving: boolean
  isOwner: boolean
  projectId: string
  onChange: (s: 'in_progress' | 'fixed') => void
}) {
  const disabled = !canChange || saving
  if (issue.status === 'needs_check' || issue.status === 'needs_recheck') {
    return (
      <div className="mt-3 flex gap-2">
        <button type="button" className={`${PRIMARY_BTN} flex-1`} disabled={disabled} onClick={() => onChange('in_progress')}>수정 시작</button>
        <button type="button" className={`${OUTLINE_BTN} flex-1`} disabled={disabled} onClick={() => onChange('fixed')}>수정 완료로 변경</button>
      </div>
    )
  }
  if (issue.status === 'in_progress') {
    return <button type="button" className={`${PRIMARY_BTN} mt-3 w-full`} disabled={disabled} onClick={() => onChange('fixed')}>수정 완료로 변경</button>
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

function Evidence({ issue, latestReQa, projectId }: { issue: QaIssue; latestReQa: Verification | null; projectId: string }) {
  const m = issue.style_mismatch
  const parts = issueParts(issue)
  return (
    <>
      <section className={`${CARD} p-5`}>
        <h2 className="text-base font-bold text-black">디자인 기준 vs 구현 실제값</h2>
        {m ? (
          <div className="mt-3 overflow-x-auto rounded-lg border border-qa-border">
            <table className="w-full min-w-[480px] text-left text-sm">
              <thead className="bg-qa-surface text-xs text-qa-text-sub">
                <tr>
                  <th className="px-4 py-2 font-medium">속성</th>
                  <th className="px-4 py-2 font-medium">디자인 기준</th>
                  <th className="px-4 py-2 font-medium">구현 실제값</th>
                  <th className="px-4 py-2 text-right font-medium">판정</th>
                </tr>
              </thead>
              <tbody>
                <tr className="border-t border-qa-border">
                  <td className="px-4 py-3 font-bold text-black">{PROPERTY_LABELS[m.property]}</td>
                  <td className="px-4 py-3 text-black">{m.expected_value}</td>
                  <td className="px-4 py-3 text-black">{m.actual_value}</td>
                  <td className="px-4 py-3 text-right">
                    <Icon name="alert-circle" className="ml-auto h-5 w-5 text-qa-warning" />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        ) : <p className="mt-3 text-sm text-black">{issue.description}</p>}
        {latestReQa && (
          <Link to={`/app/projects/${projectId}/issues/${issue.id}/re-qa`}
            className="mt-3 flex items-center justify-between gap-2 rounded-lg bg-qa-surface px-4 py-2.5 text-xs hover:bg-qa-primary/5">
            <span className="flex items-center gap-2 text-black">
              최근 Re-QA {formatDateTime(latestReQa.verified_at)} · <Verdict pass={latestReQa.result === 'pass'} />
              {latestReQa.observed_value && <span className="text-qa-text-sub">다시 읽은 구현값 {latestReQa.observed_value}</span>}
            </span>
            <span className="flex items-center font-medium text-qa-primary-hover">Re-QA 결과 보기<Icon name="chevron-right" className="h-3.5 w-3.5" /></span>
          </Link>
        )}
      </section>

      <section className="rounded-xl border border-qa-warning/40 bg-qa-warning/10 p-5">
        <div className="flex items-center gap-2">
          <h2 className="text-base font-bold text-black">불일치 요약</h2>
        </div>
        <p className="mt-2 text-sm text-black">{issue.description}</p>
        <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Fact label="대상 요소" value={parts.element} />
          <Fact label="Figma 프레임" value={parts.screen} />
          <Fact label="재현 절차" value={issue.repro_steps.join(' → ')} />
        </div>
      </section>
    </>
  )
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <div className="text-xs text-qa-text-sub">{label}</div>
      <div className="mt-0.5 break-words text-sm font-bold text-black">{value}</div>
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
    <section className={`${CARD} p-5`}>
      <h2 className="text-base font-bold text-black">처리 이력</h2>
      <ol className="mt-3 flex flex-col gap-3">
        {rows.map((r) => (
          <li key={r.key} className="flex gap-3">
            <span className="mt-1 flex h-4 w-4 flex-none items-center justify-center rounded-full border-2 border-qa-primary"><span className="h-1.5 w-1.5 rounded-full bg-qa-primary" /></span>
            <div className="min-w-0">
              <div className="text-sm text-black">{r.title}</div>
              <div className="text-xs text-qa-text-sub">{formatDateTime(r.at)} · {r.sub}</div>
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
  const href = fileKey && m?.frame_id ? figmaFrameUrl(fileKey, m.frame_id) : issue.figma_link
  return (
    <section className={`${CARD} p-5`}>
      <h2 className="text-base font-bold text-black">Figma 디자인 요소</h2>
      <div className="mt-3">
        <KeyValue rows={[
          { label: 'Frame', value: parts.screen },
          { label: '요소', value: parts.element },
          { label: '프레임 ID', value: m?.frame_id ?? '-' },
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
