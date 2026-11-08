import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  formatDateTime, getIssue, listVerifications, PROPERTY_LABELS, type QaIssue, type Verification,
} from '../lib/api'
import { Icon } from '../components/common/Icon'
import { CARD, WorkflowStepper } from '../components/workspace/WorkspaceCards'
import {
  ErrorCard, KeyValue, Loading, OUTLINE_BTN, PageTitle, PRIMARY_BTN, ProgressChip, QaShell, TeamTag, Verdict, issueParts, issueTitle,
  loadErrorMessage, useProject,
} from '../components/qa/QaParts'

// Re-QA 결과(시안 RQA-03 해결·RQA-03F 재실패, SPEC AC28) — 한 이슈의 최근 자동 Re-QA 판정.
// 수정 전 값은 첫 QA가 저장한 구현값(style_mismatch.actual_value), 수정 후 값은 Re-QA가 다시 읽은 observed_value다.
// 시안과 다른 점: 이슈 하나에 속성 하나만 저장하므로 비교 표는 한 줄이다. 매핑 신뢰도·관련 PR은 서버가 주지 않아 뺐다.
export function ReQaResultPage() {
  const { projectId = '', issueId = '' } = useParams()
  const { me, detail, error } = useProject(projectId)
  const [issue, setIssue] = useState<QaIssue | null>(null)
  const [verification, setVerification] = useState<Verification | null | undefined>(undefined)
  const [loadError, setLoadError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const [i, v] = await Promise.all([getIssue(projectId, issueId), listVerifications(projectId, issueId)])
      setIssue(i)
      setVerification(v.find((x) => x.source === 're_qa') ?? null)
    } catch (e) {
      setLoadError(loadErrorMessage(e))
    }
  }, [projectId, issueId])

  useEffect(() => {
    if (me) void load()
  }, [me, load])

  if (!me) return null
  const failMessage = error ?? loadError
  const m = issue?.style_mismatch ?? null
  const pass = verification?.result === 'pass'

  return (
    <QaShell projectId={projectId}>
      <PageTitle
        crumbs={[{ label: 'QA 이슈 상세', to: `/app/projects/${projectId}/issues/${issueId}` }, { label: 'Re-QA 결과' }]}
        title="Re-QA 결과"
        description={verification ? `비교 기준: 첫 QA ${issue ? formatDateTime(issue.created_at) : ''} (수정 전) → Re-QA ${formatDateTime(verification.verified_at)} (수정 후)` : undefined}
        actions={<Link to={`/app/projects/${projectId}/issues/${issueId}`} className={OUTLINE_BTN}>QA 이슈 상세</Link>}
      />
      {failMessage ? <ErrorCard message={failMessage} projectId={projectId} /> : !detail || !issue || verification === undefined ? <Loading /> : !verification || !m ? (
        <div className={`${CARD} mt-8 flex flex-col items-center px-6 py-12 text-center`}>
          <Icon name="refresh" className="h-7 w-7 text-gray-900" />
          <div className="mt-3 text-sm font-medium text-black">이 문제의 Re-QA 기록이 없어요</div>
          <p className="mt-2 text-xs text-qa-text-sub">담당 팀이 수정 완료로 바꾼 뒤 소유자가 Re-QA를 실행하면 결과가 여기에 나타나요.</p>
        </div>
      ) : (
        <>
          <WorkflowStepper current={5} />
          <section className={`mt-6 flex flex-wrap items-center gap-4 rounded-xl border px-5 py-4 ${pass ? 'border-qa-success/40 bg-qa-success/10' : 'border-qa-warning/40 bg-qa-warning/10'}`}>
            <Icon name="circle-check" className={`h-6 w-6 flex-none ${pass ? 'text-qa-success' : 'text-qa-text-sub'}`} />
            <div className="min-w-[240px] flex-1">
              <h2 className="text-base font-bold text-black">이슈 #{issue.id} — {issueTitle(issue)}</h2>
              <div className="mt-0.5 text-xs text-qa-text-sub">재검증 완료 · {formatDateTime(verification.verified_at)}</div>
            </div>
            <div className="flex flex-none items-center gap-2 text-xs text-black">
              <Verdict pass={false} />
              <Icon name="chevron-right" className="h-4 w-4 text-qa-text-sub" />
              <Verdict pass={pass} />
              <span>{pass ? '판정 변경됨' : `판정 유지 · ${PROPERTY_LABELS[m.property]} 불일치 남음`}</span>
            </div>
          </section>

          <div className="mt-6 grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
            <div className="flex min-w-0 flex-col gap-6">
              <section className={`${CARD} p-5`}>
                <h2 className="text-base font-bold text-black">속성별 비교 결과</h2>
                <div className="mt-3 overflow-x-auto rounded-lg border border-qa-border">
                  <table className="w-full min-w-[520px] text-left text-sm">
                    <thead className="bg-qa-surface text-xs text-qa-text-sub">
                      <tr>
                        <th className="px-4 py-2 font-medium">속성</th>
                        <th className="px-4 py-2 font-medium">디자인 기대값</th>
                        <th className="px-4 py-2 font-medium">수정 전 실제값</th>
                        <th className="px-4 py-2 font-medium">수정 후 실제값</th>
                        <th className="px-4 py-2 text-right font-medium">판정</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr className="border-t border-qa-border">
                        <td className="px-4 py-3 text-black">{PROPERTY_LABELS[m.property]}</td>
                        <td className="px-4 py-3 text-black">{m.expected_value}</td>
                        <td className="px-4 py-3 text-black">{m.actual_value}</td>
                        <td className="px-4 py-3 text-black">{verification.observed_value ?? '-'}</td>
                        <td className="px-4 py-3 text-right">
                          <Icon name={pass ? 'circle-check' : 'alert-circle'} className={`ml-auto h-5 w-5 ${pass ? 'text-qa-success' : 'text-qa-warning'}`} />
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </section>

              <section className={`${CARD} p-5`}>
                <h2 className="text-base font-bold text-black">변경된 UI 속성 하이라이트</h2>
                <div className="mt-3 grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <div>
                    <div className="flex items-center gap-2 text-xs text-black">수정 전 <Verdict pass={false} /></div>
                    <ValueBox pass={false} title={PROPERTY_LABELS[m.property]} lines={[`기대값 ${m.expected_value} · 실제값 ${m.actual_value}`, '불일치']} />
                  </div>
                  <div>
                    <div className="flex items-center gap-2 text-xs text-black">수정 후 <Verdict pass={pass} /></div>
                    <ValueBox pass={pass} title={PROPERTY_LABELS[m.property]} lines={[`기대값 ${m.expected_value} · 실제값 ${verification.observed_value ?? '-'}`, pass ? '일치' : '여전히 불일치']} />
                  </div>
                </div>
              </section>
            </div>

            <div className="flex min-w-0 flex-col gap-6">
              <section className={`${CARD} p-5`}>
                <h2 className="text-base font-bold text-black">연결 추적 정보</h2>
                <div className="mt-3">
                  <KeyValue rows={[
                    { label: '이슈 ID', value: `#${issue.id}` },
                    { label: 'Figma 요소', value: issueParts(issue).element },
                    { label: 'Figma 프레임', value: issueParts(issue).screen },
                    { label: '담당 팀', value: <TeamTag team={issue.assigned_team} /> },
                    { label: '수정 진행', value: <ProgressChip status={issue.status} /> },
                  ]} />
                </div>
              </section>
              <section className="rounded-xl border border-qa-primary/50 bg-white p-5">
                <h2 className="text-base font-bold text-black">이슈 해결 여부 및 다음 조치</h2>
                <div className={`mt-3 flex items-center gap-1.5 text-sm font-medium ${pass ? 'text-qa-success' : 'text-qa-warning'}`}>
                  <Icon name={pass ? 'circle-check' : 'alert-circle'} className="h-4 w-4" />{pass ? '해결됨 (PASS 판정)' : '미해결 (FAIL 유지)'}
                </div>
                <p className="mt-2 text-xs leading-4 text-qa-text-sub">
                  {pass
                    ? '수정된 구현이 디자인 기대값과 일치합니다. 이슈는 해결로 처리되었어요.'
                    : `${PROPERTY_LABELS[m.property]}이(가) 아직 디자인 기대값과 달라요. 담당 팀에 알림을 보냈어요 — 다시 수정한 뒤 Re-QA를 실행하세요.`}
                  {issue.description.startsWith('[시연용]') && ' (개발용 가짜 비교기 결과)'}
                </p>
                {pass ? (
                  <Link to={`/app/projects/${projectId}/queue`} className={`${PRIMARY_BTN} mt-4 w-full`}>QA Queue에서 남은 문제 보기</Link>
                ) : (
                  <Link to={`/app/projects/${projectId}/issues/${issue.id}`} className={`${PRIMARY_BTN} mt-4 w-full`}>추가 수정으로 돌아가기</Link>
                )}
              </section>
            </div>
          </div>
        </>
      )}
    </QaShell>
  )
}

function ValueBox({ pass, title, lines }: { pass: boolean; title: string; lines: string[] }) {
  return (
    <div className={`mt-2 rounded-lg px-4 py-3 ${pass ? 'bg-qa-success/10' : 'bg-qa-warning/10'}`}>
      <div className="text-sm font-bold text-black">{title}</div>
      {lines.map((l) => <div key={l} className="mt-0.5 text-xs text-black">{l}</div>)}
    </div>
  )
}
