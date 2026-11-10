import { useMemo, useState } from 'react'
import { formatDateTime, manualQaId, PROPERTY_LABELS, SEVERITY_LABELS, type ProjectMember, type QaIssue } from '../../lib/api'
import { Icon } from '../common/Icon'
import { ProgressChip, Verdict, issueParts, issueTitle } from '../qa/QaParts'

// 아래 왼쪽 QA 항목 목록(시안 W-01·M-01). 항목을 고르면 오른쪽 상세만 바뀐다 — 페이지 이동 없음.
// 시안과 다른 점: 자동 QA는 첫 QA가 저장한 불일치(FAIL)뿐이라 "검사 오류"·"대기" 상태가 없다 — 그 필터는 두지 않는다.
// 자동 QA의 PASS는 Re-QA로 해결된(resolved) 항목이다(QA 결과 화면과 같은 규칙). 수동 QA는 판정 대신 수정 진행 상태를 보여 준다.
type SourceTab = 'all' | 'auto' | 'manual'
type StatusFilter = 'all' | 'fail' | 'pass' | 'open'

export function itemId(i: QaIssue): string {
  return i.manual_qa ? manualQaId(i.manual_qa.pin_number) : `#${i.id}`
}

export function QaItemList({ issues, members, selectedId, onSelect }: {
  issues: QaIssue[]
  members: ProjectMember[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  const [tab, setTab] = useState<SourceTab>('all')
  const [status, setStatus] = useState<StatusFilter>('all')
  const [query, setQuery] = useState('')

  const auto = issues.filter((i) => i.source === 'first_qa')
  const manual = issues.filter((i) => i.source === 'manual')
  const inTab = tab === 'auto' ? auto : tab === 'manual' ? manual : issues
  const counts = {
    all: inTab.length,
    fail: inTab.filter((i) => i.source === 'first_qa' && i.status !== 'resolved').length,
    pass: inTab.filter((i) => i.source === 'first_qa' && i.status === 'resolved').length,
    open: inTab.filter((i) => i.source === 'manual' && i.status !== 'resolved').length,
  }
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return inTab.filter((i) => {
      if (status === 'fail' && !(i.source === 'first_qa' && i.status !== 'resolved')) return false
      if (status === 'pass' && !(i.source === 'first_qa' && i.status === 'resolved')) return false
      if (status === 'open' && !(i.source === 'manual' && i.status !== 'resolved')) return false
      return !q || issueTitle(i).toLowerCase().includes(q) || itemId(i).toLowerCase().includes(q)
    })
  }, [inTab, status, query])
  const nameOf = (id: string) => members.find((m) => m.id === id)?.name ?? '알 수 없음'

  return (
    <section className="flex min-h-[420px] flex-col overflow-hidden rounded-xl border border-qa-border bg-white" aria-label="QA 항목">
      <div className="border-b border-qa-border p-4">
        <div className="flex items-center gap-2">
          <h2 className="text-base font-bold text-black">QA 항목</h2>
          <span className="text-xs font-medium text-qa-text-sub">{issues.length}개</span>
        </div>
        <label className="mt-3 flex h-9 items-center gap-2 rounded-lg border border-qa-border px-3">
          <Icon name="search" className="h-4 w-4 text-qa-text-sub" />
          <span className="sr-only">QA 항목 검색</span>
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="QA 항목 ID 또는 제목 검색"
            className="min-w-0 flex-1 bg-transparent text-sm outline-none" />
        </label>
        <div className="mt-3 grid grid-cols-3 rounded-lg bg-qa-surface p-0.5 text-xs" role="tablist" aria-label="출처">
          {([['all', '전체', issues.length], ['auto', '자동 QA', auto.length], ['manual', '수동 QA', manual.length]] as const).map(([v, label, n]) => (
            <button key={v} type="button" role="tab" aria-selected={tab === v} onClick={() => { setTab(v); setStatus('all') }}
              className={`rounded-md py-1.5 ${tab === v ? 'bg-white font-medium text-black shadow-sm' : 'text-qa-text-sub'}`}>{label} {n}</button>
          ))}
        </div>
        <div className="mt-2 flex flex-wrap gap-1.5 text-xs">
          {([['all', '전체', counts.all], ['fail', 'FAIL', counts.fail], ['pass', 'PASS', counts.pass], ['open', '미해결', counts.open]] as const)
            .filter(([v, , n]) => v === 'all' || n > 0 || status === v)
            .map(([v, label, n]) => (
              <button key={v} type="button" aria-pressed={status === v} onClick={() => setStatus(v)}
                className={`rounded-full px-2.5 py-1 ${status === v ? 'bg-qa-primary/10 font-medium text-qa-primary-hover' : 'text-qa-text-sub hover:bg-qa-surface'}`}>
                {label} {n}
              </button>
            ))}
        </div>
      </div>
      <ul className="max-h-[520px] flex-1 overflow-y-auto">
        {shown.map((i) => {
          const active = i.id === selectedId
          const m = i.manual_qa
          return (
            <li key={i.id}>
              <button type="button" onClick={() => onSelect(i.id)} aria-current={active}
                className={`flex w-full items-start gap-3 border-b border-l-2 border-qa-border px-4 py-3 text-left ${active ? 'border-l-qa-primary bg-qa-primary/10' : 'border-l-transparent hover:bg-qa-surface'}`}>
                <span className="min-w-0 flex-1">
                  <span className="flex items-center gap-1.5 text-xs text-qa-text-sub">
                    {m && <span className="flex h-4 w-4 items-center justify-center rounded-full bg-[#7c3aed] text-[10px] font-bold text-white">{m.pin_number}</span>}
                    {itemId(i)}
                    <span className={`rounded px-1.5 text-[11px] font-medium ${i.source === 'manual' ? 'bg-[#7c3aed]/10 text-[#7c3aed]' : 'bg-qa-primary/10 text-qa-primary-hover'}`}>
                      {i.source === 'manual' ? '수동 QA' : '자동 QA'}
                    </span>
                  </span>
                  <span className="mt-0.5 block truncate text-sm font-medium text-black">{issueTitle(i)}</span>
                  <span className="block truncate text-xs text-qa-text-sub">
                    {i.source === 'manual'
                      ? `${nameOf(i.reported_by)} · ${formatDateTime(i.created_at)} 등록`
                      : `${issueParts(i).screen}${i.style_mismatch ? ` · ${PROPERTY_LABELS[i.style_mismatch.property]}` : ''}`}
                  </span>
                </span>
                <span className="flex flex-none flex-col items-end gap-1">
                  {i.source === 'manual' ? <ProgressChip status={i.status} /> : <Verdict pass={i.status === 'resolved'} />}
                  {m?.severity && <span className="text-[11px] text-qa-text-sub">{SEVERITY_LABELS[m.severity]}</span>}
                </span>
              </button>
            </li>
          )
        })}
        {shown.length === 0 && (
          <li className="px-4 py-10 text-center text-xs text-qa-text-sub">
            {issues.length === 0 ? '아직 QA 항목이 없어요. 배포 화면에서 [QA 등록]으로 문제 위치를 찍어 등록해 보세요.' : '조건에 맞는 항목이 없어요'}
          </li>
        )}
      </ul>
      <div className="border-t border-qa-border px-4 py-2 text-xs text-qa-text-sub">항목을 선택하면 오른쪽 상세 패널만 바뀌어요 · 페이지 이동 없음</div>
    </section>
  )
}
