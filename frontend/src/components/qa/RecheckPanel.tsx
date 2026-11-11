import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, createVerification, getRecheckSuggestions, type QaIssue, type RecheckSuggestions } from '../../lib/api'
import { Icon } from '../common/Icon'
import { PANEL } from './CodeTrace'
import { OUTLINE_BTN, PRIMARY_BTN } from './QaParts'

// 등록자 재검증(SPEC AC5·AC7·AC13)과 재검증 범위 제안(AC20).
//  - 재현 절차는 등록 때 적은 것을 그대로 보여 준다 — 다시 입력받지 않는다(AC5·N6)
//  - PASS면 해결(resolved), FAIL이면 재확인 필요(needs_recheck)로 바뀌고 담당 팀에 알림이 간다(AC11) — 되돌릴 수 없어 한 번 더 묻는다
//  - 범위 제안은 요청할 때만 부르고 저장하지 않는다. 근거가 이 프로젝트 이슈면 그 이슈로 가는 링크를 단다

const EVIDENCE_LABELS = { qa_issue: '이슈', style_mismatch: '스타일 차이', verification: '재검증 기록' } as const

export function RecheckPanel({ issue, projectId, canVerify, onVerified }: {
  issue: QaIssue
  projectId: string
  /** 등록자 본인, 또는 등록자가 프로젝트에서 빠졌을 때 소유자 */
  canVerify: boolean
  onVerified: () => void
}) {
  const [scope, setScope] = useState('')
  const [confirm, setConfirm] = useState<'pass' | 'fail' | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async (result: 'pass' | 'fail') => {
    setSaving(true)
    setError(null)
    try {
      await createVerification(projectId, issue.id, result, scope.trim() || undefined)
      setConfirm(null)
      onVerified()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '재검증 결과를 등록하지 못했어요.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className={PANEL} aria-labelledby="recheck-title">
      <div className="flex items-center justify-between gap-2">
        <h2 id="recheck-title" className="text-base font-bold leading-6 text-black">재검증</h2>
        <span className="inline-flex h-6 items-center rounded-full bg-[#f08c00]/10 px-2 text-xs font-medium leading-4 text-black">수정 완료 · 확인 대기</span>
      </div>
      <p className="mt-2 text-xs leading-4 text-qa-text-sub">등록할 때 적은 재현 절차를 그대로 다시 따라 해 보고 결과를 남겨 주세요.</p>

      <div className="mt-4 rounded-lg bg-qa-surface px-4 py-3">
        <div className="text-xs font-medium text-qa-text-sub">위치</div>
        <div className="mt-0.5 break-words text-sm text-black">{issue.screen_location}</div>
        <div className="mt-3 text-xs font-medium text-qa-text-sub">재현 절차</div>
        <ol className="mt-1 list-decimal space-y-0.5 pl-5 text-sm leading-5 text-black">
          {issue.repro_steps.map((s, i) => <li key={i} className="break-words">{s}</li>)}
        </ol>
      </div>

      {canVerify ? (
        <>
          <label className="mt-4 block text-xs font-medium text-black">
            확인한 범위 <span className="font-normal text-qa-text-sub">선택</span>
            <input value={scope} onChange={(e) => setScope(e.target.value)} disabled={saving} maxLength={255}
              placeholder="예: Chrome · 로그인 화면과 회원가입 화면"
              className="mt-1 h-9 w-full rounded-lg border border-qa-border bg-white px-3 text-xs font-normal outline-none focus:border-qa-primary" />
          </label>
          {confirm ? (
            <div className="mt-3 rounded-lg border border-qa-border p-3">
              <p className="text-xs leading-4 text-black">
                {confirm === 'pass'
                  ? 'PASS로 등록하면 이 문제는 해결로 닫혀요.'
                  : 'FAIL로 등록하면 재확인 필요로 바뀌고 담당 팀에 알림이 가요.'} 등록한 결과는 되돌릴 수 없어요.
              </p>
              <div className="mt-2 flex gap-2">
                <button type="button" className={`${OUTLINE_BTN} flex-1`} disabled={saving} onClick={() => setConfirm(null)}>취소</button>
                <button type="button" className={`${PRIMARY_BTN} flex-1`} disabled={saving} onClick={() => void submit(confirm)}>
                  {saving ? '등록하는 중…' : `${confirm === 'pass' ? 'PASS' : 'FAIL'} 등록`}
                </button>
              </div>
            </div>
          ) : (
            <div className="mt-3 flex gap-2">
              <button type="button" className={`${PRIMARY_BTN} flex-1`} onClick={() => setConfirm('pass')}>
                <Icon name="check" className="h-4 w-4" />PASS — 고쳐졌어요
              </button>
              <button type="button" className={`${OUTLINE_BTN} flex-1`} onClick={() => setConfirm('fail')}>
                <Icon name="x" className="h-4 w-4" />FAIL — 아직 그대로예요
              </button>
            </div>
          )}
          {error && <p role="alert" className="mt-2 text-xs text-qa-warning">{error}</p>}
          <Suggestions projectId={projectId} issueId={issue.id} />
        </>
      ) : (
        <p className="mt-4 text-xs leading-4 text-qa-text-sub">재검증은 이 문제를 등록한 사람이 해요. 등록자가 프로젝트에서 빠졌으면 소유자가 할 수 있어요.</p>
      )}
    </section>
  )
}

/** 재검증 범위 제안(AC20) — 이 프로젝트의 이력에서 같이 다시 볼 화면을 근거와 함께 제안한다 */
function Suggestions({ projectId, issueId }: { projectId: string; issueId: string }) {
  const [result, setResult] = useState<RecheckSuggestions | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      setResult(await getRecheckSuggestions(projectId, issueId))
    } catch (e) {
      setResult(null)
      setError(e instanceof ApiError ? e.message : '제안을 받지 못했어요. 잠시 뒤 다시 시도해 주세요.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="mt-5 border-t border-qa-border pt-4">
      <div className="flex items-center justify-between gap-2">
        <div>
          <div className="text-sm font-bold text-black">같이 다시 볼 화면</div>
          <div className="text-xs text-qa-text-sub">이 프로젝트의 이슈·재검증 이력에서 연결된 화면을 찾아 드려요.</div>
        </div>
        <button type="button" className={`${OUTLINE_BTN} h-8 px-3 text-xs`} disabled={loading} onClick={() => void load()}>
          <Icon name="search" className="h-3.5 w-3.5" />{loading ? '찾는 중…' : result ? '다시 찾기' : '제안 받기'}
        </button>
      </div>
      {error && <p role="alert" className="mt-2 text-xs text-qa-warning">{error}</p>}
      {result && (
        result.suggestions.length === 0 ? (
          <p className="mt-3 rounded-lg bg-qa-surface px-3 py-2.5 text-xs text-qa-text-sub">
            {result.searched_count === 0 ? '근거로 삼을 이력이 아직 없어요.' : '같이 다시 볼 만한 화면을 찾지 못했어요.'}
          </p>
        ) : (
          <ul className="mt-3 flex flex-col gap-2">
            {result.suggestions.map((s, i) => (
              <li key={i} className="rounded-lg border border-qa-border px-3 py-2.5">
                <div className="text-sm font-medium text-black">{s.screen}</div>
                <div className="mt-0.5 text-xs leading-4 text-qa-text-sub">{s.reason}</div>
                <div className="mt-1.5 flex flex-wrap gap-1">
                  {s.evidence.map((ev) => ev.type === 'qa_issue' ? (
                    <Link key={`${ev.type}${ev.id}`} to={`/app/projects/${projectId}/issues/${ev.id}`}
                      className="rounded bg-qa-primary/10 px-1.5 text-xs font-medium leading-5 text-qa-primary-hover hover:underline">
                      이슈 #{ev.id}
                    </Link>
                  ) : (
                    <span key={`${ev.type}${ev.id}`} className="rounded bg-qa-surface px-1.5 text-xs leading-5 text-qa-text-sub">
                      {EVIDENCE_LABELS[ev.type] ?? ev.type} #{ev.id}
                    </span>
                  ))}
                </div>
              </li>
            ))}
          </ul>
        )
      )}
      {result && result.dropped_count > 0 && (
        <p className="mt-2 text-xs text-qa-text-sub">근거를 확인할 수 없는 제안 {result.dropped_count}건은 뺐어요.</p>
      )}
    </div>
  )
}
