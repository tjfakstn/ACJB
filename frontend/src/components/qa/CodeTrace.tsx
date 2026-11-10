import { useState, type ReactNode } from 'react'
import { ApiError, formatDateTime, type CodeTrace, type IssueStatus, type TraceCommit, type TraceFix, type TracePull } from '../../lib/api'
import { Icon } from '../common/Icon'

// 이슈 상세의 코드 추적(시안 ISS-02~04, SPEC AC34). GitHub는 읽기만 한다.
//  - 고치기 전: 문제 코드가 들어온 커밋·PR(그 줄을 마지막으로 바꾼 커밋) — 개발자가 참고해서 고친다
//  - 고친 뒤: 같은 자리에 고친 커밋·PR — 커밋 메시지·PR 제목의 이슈 키, 담당 팀이 붙인 링크, Re-QA 커밋
// 시안과 다른 점: Props·관련 스타일 파일·라인 범위·매핑 신뢰도는 뽑는 값이 없어 만들지 않았다

const short = (sha: string) => sha.slice(0, 7)

/** 시안 ISS-02 카드 — 테두리 1px·안쪽 20·모서리 12 */
export const PANEL = 'rounded-xl border border-qa-border bg-white p-5'

/** 시안의 키-값 줄 — 높이 36, 구분선 없음, 라벨 14 회색 · 값 14 굵게 */
export function KvRows({ rows }: { rows: { label: string; value: ReactNode }[] }) {
  return (
    <dl className="flex flex-col">
      {rows.map((r) => (
        <div key={r.label} className="flex min-h-9 items-center justify-between gap-4">
          <dt className="flex-none text-sm text-qa-text-sub">{r.label}</dt>
          <dd className="min-w-0 truncate text-right text-sm font-medium text-black">{r.value}</dd>
        </div>
      ))}
    </dl>
  )
}

/** 이슈 키 — 커밋 메시지나 PR 제목에 넣으면 이 이슈에 연결된다 */
export function IssueKey({ issueKey }: { issueKey: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(issueKey)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      setCopied(false)
    }
  }
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-qa-text-sub">
      <button type="button" onClick={() => void copy()} title="이슈 키 복사"
        className="inline-flex h-6 items-center gap-1 rounded-md border border-qa-border bg-white px-2 font-mono text-xs font-medium text-black hover:bg-qa-surface">
        {issueKey}<Icon name={copied ? 'check' : 'copy'} className="h-3 w-3 text-qa-text-sub" />
      </button>
      {copied ? '복사했어요' : '커밋 메시지나 PR 제목에 넣으면 이 이슈에 연결돼요'}
    </span>
  )
}

/** 연결 코드 파일 · 컴포넌트 — 첫 QA가 값을 읽은 곳. Props·관련 스타일 파일은 뽑는 값이 없어 "-" */
export function CodeLinkCard({ trace, manual }: { trace: CodeTrace | null; manual: boolean }) {
  const code = trace?.code
  return (
    <section className={PANEL}>
      <h2 className="text-base font-bold leading-6 text-black">연결 코드 파일 · 컴포넌트</h2>
      {!trace ? <p className="mt-4 text-xs text-qa-text-sub">불러오는 중…</p> : !code ? (
        <Empty title="연결된 코드 파일이 없습니다" sub={manual ? '등록할 때 기준 Figma 프레임을 고르면 그 화면 파일을 보여 줘요.' : '이 이슈에는 코드 위치 기록이 없어요.'} />
      ) : (
        <>
          <a href={code.url ?? undefined} target="_blank" rel="noreferrer" title={code.path}
            className="mt-4 flex h-10 items-center gap-2 rounded-lg bg-qa-surface px-3 hover:bg-qa-primary/5">
            <Icon name="file-code" className="h-4 w-4 flex-none text-gray-900" />
            <span className="min-w-0 flex-1 truncate text-xs font-medium leading-4 text-black">{code.path}</span>
          </a>
          {code.basis === 'screen' && (
            <p className="mt-2 text-xs leading-4 text-qa-text-sub">기준 Figma 프레임을 비교 분석이 짝지은 화면 파일이에요 · 줄 단위 위치는 없어요</p>
          )}
          <div className="mt-4">
            <KvRows rows={[
              { label: '컴포넌트명', value: code.symbol ?? '-' },
              { label: 'Props', value: '-' },
              { label: '라인', value: code.line ? `L.${code.line}` : '-' },
              { label: '관련 스타일 파일', value: '-' },
            ]} />
          </div>
        </>
      )}
    </section>
  )
}

/** GitHub 커밋 · PR — 고치기 전에는 문제 코드가 들어온 커밋·PR, 고친 뒤에는 고친 커밋·PR */
export function GithubTraceCard({ trace, status, canAttach, onAttach, onDetach }: {
  trace: CodeTrace | null
  status: IssueStatus
  canAttach: boolean
  onAttach: (url: string) => Promise<void>
  onDetach: () => Promise<void>
}) {
  const fixes = trace?.fixes ?? []
  const fixed = status === 'fixed' || status === 'resolved' || status === 'needs_recheck'
  const manual = fixes.find((f) => f.source === 'manual')
  return (
    <section className={PANEL}>
      <h2 className="text-base font-bold leading-6 text-black">GitHub 커밋 · PR</h2>
      {!trace ? <p className="mt-4 text-xs text-qa-text-sub">불러오는 중…</p> : trace.github === 'not_connected' ? (
        <Empty title="GitHub 저장소가 연결되지 않았어요" sub="연결 관리에서 저장소를 연결하면 커밋·PR을 보여 줘요." />
      ) : (
        <>
          {trace.github === 'unavailable' && (
            <p role="status" className="mt-4 rounded-lg bg-qa-warning/10 px-3 py-2 text-xs text-black">GitHub를 지금 읽지 못해 일부만 보여 줘요. 잠시 뒤 새로고침해 주세요.</p>
          )}
          {fixes.length > 0 && (
            <div className="mt-4">
              <div className="text-xs font-medium text-qa-text-sub">고친 커밋 · PR</div>
              <ul className="mt-2 flex flex-col gap-2">{fixes.map((f, i) => <FixRow key={i} fix={f} />)}</ul>
            </div>
          )}
          {trace.origin ? (
            <div className={fixes.length > 0 ? "mt-4 border-t border-qa-border pt-4" : "mt-4"}>
              <div className="text-xs font-medium text-qa-text-sub">
                문제 코드가 들어온 커밋 · PR <span className="font-normal">· {trace.origin.basis === 'line' ? '그 줄을 마지막으로 바꾼 커밋' : '그 파일을 마지막으로 바꾼 커밋'}</span>
              </div>
              <CommitRow commit={trace.origin.commit} />
              {trace.origin.pull_requests.length > 0
                ? trace.origin.pull_requests.map((p) => <PullRow key={p.number} pull={p} />)
                : <p className="mt-1.5 text-xs text-qa-text-sub">PR 없이 반영된 커밋이에요.</p>}
            </div>
          ) : fixes.length === 0 && (
            <Empty title="연결된 커밋 · PR 정보가 없습니다" sub={trace.code ? '첫 QA 실행 시점에 확인된 Git 커밋 · PR 정보가 없어 표시하지 않아요.' : '기준 Figma 프레임이 없어 코드 위치를 몰라요 · 고친 커밋·PR은 이슈 키로 연결돼요.'} />
          )}
          {!fixed && (
            <p className="mt-3 text-xs text-qa-text-sub">고친 뒤에는 커밋 메시지나 PR 제목에 <span className="font-mono font-medium text-black">{trace.issue_key}</span>를 넣으면 여기에 연결돼요.</p>
          )}
          {canAttach && <FixLinkControl current={manual?.url ?? null} onAttach={onAttach} onDetach={onDetach} />}
        </>
      )}
    </section>
  )
}

function Empty({ title, sub }: { title: string; sub: string }) {
  return (
    <div className="mt-4 rounded-lg bg-qa-surface px-4 py-4 text-center">
      <div className="text-sm font-medium leading-5 text-black">{title}</div>
      <div className="mt-1 text-xs leading-4 text-qa-text-sub">{sub}</div>
    </div>
  )
}

const SOURCE_LABEL: Record<TraceFix['source'], string> = { key: '이슈 키', manual: '직접 연결', re_qa: 'Re-QA 확인' }

function FixRow({ fix }: { fix: TraceFix }) {
  return (
    <li className="rounded-lg border border-qa-border px-3 py-2">
      <div className="flex items-center gap-1.5 text-[11px] text-qa-text-sub">
        <span className="rounded bg-qa-surface px-1.5 py-0.5 font-medium text-black">{SOURCE_LABEL[fix.source]}</span>
        {fix.verdict && (
          <span className={`rounded px-1.5 py-0.5 font-medium ${fix.verdict === 'pass' ? 'bg-qa-success/10 text-qa-success' : 'bg-qa-warning/10 text-[#c8161a]'}`}>
            Re-QA {fix.verdict === 'pass' ? 'PASS' : 'FAIL'}
          </span>
        )}
      </div>
      {fix.pull_request ? <PullRow pull={fix.pull_request} bare /> : fix.commit && <CommitRow commit={fix.commit} bare />}
    </li>
  )
}

function CommitRow({ commit, bare }: { commit: TraceCommit; bare?: boolean }) {
  return (
    <a href={commit.url ?? undefined} target="_blank" rel="noreferrer" className={`${bare ? 'mt-1' : 'mt-2'} flex items-start gap-2 hover:underline`}>
      <Icon name="git-commit" className="mt-0.5 h-4 w-4 flex-none text-qa-text-sub" />
      <span className="min-w-0">
        <span className="block truncate text-sm text-black">{commit.message ?? '커밋'}</span>
        <span className="block text-xs text-qa-text-sub">
          <span className="font-mono">{short(commit.sha)}</span>{commit.author ? ` · ${commit.author}` : ''}{commit.committed_at ? ` · ${formatDateTime(commit.committed_at)}` : ''}
        </span>
      </span>
    </a>
  )
}

const PR_STATE: Record<NonNullable<TracePull['state']>, { label: string; cls: string }> = {
  open: { label: '열림', cls: 'bg-qa-success/10 text-qa-success' },
  merged: { label: '병합됨', cls: 'bg-[#7c3aed]/10 text-[#7c3aed]' },
  closed: { label: '닫힘', cls: 'bg-qa-surface text-qa-text-sub' },
}

function PullRow({ pull, bare }: { pull: TracePull; bare?: boolean }) {
  const st = pull.state ? PR_STATE[pull.state] : null
  return (
    <a href={pull.url ?? undefined} target="_blank" rel="noreferrer" className={`${bare ? 'mt-1' : 'mt-2'} flex items-start gap-2 hover:underline`}>
      <Icon name="git-pull-request" className="mt-0.5 h-4 w-4 flex-none text-qa-text-sub" />
      <span className="min-w-0">
        <span className="block truncate text-sm text-black">#{pull.number} {pull.title ?? ''}</span>
        <span className="flex items-center gap-1.5 text-xs text-qa-text-sub">
          {st && <span className={`rounded px-1.5 font-medium ${st.cls}`}>{st.label}</span>}
          {pull.author ?? ''}
        </span>
      </span>
    </a>
  )
}

/** 수정 PR·커밋 링크 — 담당 팀만. 키를 깜빡했을 때 직접 붙인다 */
function FixLinkControl({ current, onAttach, onDetach }: { current: string | null; onAttach: (url: string) => Promise<void>; onDetach: () => Promise<void> }) {
  const [open, setOpen] = useState(false)
  const [url, setUrl] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    setErr(null)
    try {
      await fn()
      setOpen(false)
      setUrl('')
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '링크를 저장하지 못했어요.')
    } finally {
      setBusy(false)
    }
  }
  if (!open) {
    return (
      <div className="mt-3 flex items-center gap-3 text-xs">
        <button type="button" onClick={() => setOpen(true)} className="font-medium text-qa-primary-hover hover:underline">
          {current ? '수정 링크 바꾸기' : '수정 PR·커밋 링크 붙이기'}
        </button>
        {current && <button type="button" disabled={busy} onClick={() => void run(onDetach)} className="text-qa-text-sub hover:underline">링크 지우기</button>}
        {err && <span role="alert" className="text-qa-warning">{err}</span>}
      </div>
    )
  }
  return (
    <form className="mt-3" onSubmit={(e) => { e.preventDefault(); void run(() => onAttach(url.trim())) }}>
      <input value={url} onChange={(e) => setUrl(e.target.value)} autoFocus disabled={busy} aria-label="수정 PR·커밋 주소"
        placeholder="https://github.com/owner/repo/pull/42"
        className="h-9 w-full rounded-lg border border-qa-border bg-white px-3 text-xs outline-none focus:border-qa-primary" />
      {err && <p role="alert" className="mt-1 text-xs text-qa-warning">{err}</p>}
      <div className="mt-2 flex justify-end gap-2">
        <button type="button" onClick={() => { setOpen(false); setErr(null) }} disabled={busy}
          className="h-8 rounded-lg border border-qa-border bg-white px-3 text-xs text-black hover:bg-qa-surface">취소</button>
        <button type="submit" disabled={busy || !url.trim()} className="h-8 rounded-lg bg-qa-primary px-3 text-xs font-medium text-white hover:bg-qa-primary-hover disabled:opacity-60">
          {busy ? '저장 중…' : '붙이기'}
        </button>
      </div>
    </form>
  )
}
