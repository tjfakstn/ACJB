import { useEffect, useState, type ReactNode } from 'react'
import { ApiError, connectFigma, connectGithub, type FigmaConnection, type GithubConnection } from '../../lib/api'
import { Icon } from '../common/Icon'

// Figma·GitHub 연결 카드 — 새 프로젝트 마법사 3단계(ConnectStep)와 연결 관리 화면(ConnectionsPage, 2026-11-11)이 같이 쓴다.
// 시안 CON-01(연결 전) → CON-02·04(확인 중) → CON-03·05(완료), 예외 CON-E1~E5.
// 링크를 입력하고 칸을 벗어나거나 Enter를 누르면 서버가 실제 접근을 확인한다(SPEC AC21) — 링크 입력만으로는 연결되지 않는다.
// 시안과 다른 점: GitHub는 공개 저장소만 연결한다(GitHub App 없음, 2026-11-07 결정) — CON-E4의 [GitHub에서 접근 허용] 대신
// 공개 저장소로 바꾸라고 안내한다. [프로젝트 시작하기]를 누르면 비교 분석(CON-06)으로 넘어간다.
// GitHub는 연결되면 분석할 브랜치를 고를 수 있다(처음엔 기본 브랜치, 2026-11-07).
const GITHUB_PATTERN = /^(?:https?:\/\/)?(?:www\.)?github\.com\/[A-Za-z0-9-]+\/[A-Za-z0-9._-]+?(?:\.git)?\/?(?:[?#].*)?$/
// 주소창에서 복사한 브랜치·파일 주소(/tree/…, /blob/…) — 저장소 주소만 넣으라고 안내한다. 브랜치는 연결한 뒤 고른다(2026-11-11)
const GITHUB_SUBPATH = /^(?:https?:\/\/)?(?:www\.)?github\.com\/[A-Za-z0-9-]+\/[A-Za-z0-9._-]+\/(?:tree|blob)\//
const GITHUB_SUBPATH_MESSAGE = '브랜치 주소가 아니라 저장소 주소만 넣어 주세요 (예: https://github.com/조직/저장소). 브랜치는 연결한 뒤 고를 수 있어요.'
// Figma는 최종 시안만 담은 페이지([공유하기]) 또는 섹션([링크 복사]) 링크를 받는다(2026-11-07): figma.com/design/{key}/{이름}?node-id=…
// 이 검사는 모양만 본다 — node-id가 실제로 페이지·섹션인지(프레임이 아닌지)는 서버가 Figma로 확인한다
const FIGMA_PATTERN = /^(?:https?:\/\/)?(?:www\.)?figma\.com\/design\/[A-Za-z0-9]+(?:\/[^/?#]*)?\?(?:[^#]*&)?node-id=\d+(?:-|:|%3A)\d+/i

type State =
  | { kind: 'idle' }
  | { kind: 'checking' }
  | { kind: 'connected'; detail: string }
  | { kind: 'format'; message?: string }
  /** message: 서버가 알려 준 이유(Figma 내보내기 막힘처럼 보기 권한과 다른 경우) */
  | { kind: 'forbidden'; message?: string }
  | { kind: 'failed'; message?: string }

type Service = 'github' | 'figma'

const BANNERS: Record<Service, Partial<Record<State['kind'], { title: string; body: string }>>> = {
  figma: {
    forbidden: { title: 'Figma 파일 접근 권한을 확인해 주세요', body: '파일 보기 권한이 없어요. 권한이 확인되어야 [프로젝트 시작하기]가 활성화됩니다.' },
    failed: { title: 'Figma 파일을 연결하지 못했어요', body: '파일을 찾을 수 없거나 응답이 지연되고 있어요. 링크를 확인한 뒤 다시 시도해 주세요.' },
    format: { title: 'Figma 링크를 확인해 주세요', body: '최종 시안만 담은 페이지의 [공유하기] 링크나, 섹션을 우클릭해 [링크 복사]한 링크만 연결할 수 있어요.' },
  },
  github: {
    forbidden: { title: 'GitHub 저장소에 접근할 수 없어요', body: '저장소가 없거나 비공개예요. 공개 저장소만 연결할 수 있어요.' },
    failed: { title: 'GitHub 저장소를 연결하지 못했어요', body: '응답 시간이 초과되었거나 GitHub 오류가 있었어요. 다시 시도해 주세요.' },
    format: { title: 'GitHub 저장소 링크를 확인해 주세요', body: '링크 형식이 올바르지 않아요. 수정하면 자동으로 다시 확인합니다.' },
  },
}

export interface ConnectionStatus {
  bothConnected: boolean
  busy: boolean
}

function figmaDetail(c: FigmaConnection): string {
  return `${c.file_name} › ${c.page_name}${c.scope === 'section' ? ' (섹션)' : ''}${c.uses_own_token ? ` · 본인 토큰 …${c.token_hint ?? ''}` : ''}`
}

/**
 * 배너와 GitHub·Figma 카드. initial이 있으면 이미 연결된 상태로 시작한다(연결 관리 화면).
 * locked가 있으면 그 이유를 보여 주고 바꾸지 못하게 한다(소유자가 아님, 비교 분석이 끝남 — AC21)
 */
export function ConnectionCards({ projectId, initialFigma = null, initialGithub = null, locked = null, onStatus, onConnected }: {
  projectId: string
  initialFigma?: FigmaConnection | null
  initialGithub?: GithubConnection | null
  locked?: string | null
  onStatus?: (s: ConnectionStatus) => void
  /** 연결·브랜치 변경이 저장될 때마다 — 화면이 프로젝트 정보를 다시 읽는다 */
  onConnected?: () => void
}) {
  const [github, setGithub] = useState(() => initialGithub
    ? { url: initialGithub.url, checked: initialGithub.url, state: { kind: 'connected', detail: initialGithub.branch } as State }
    : { url: '', checked: '', state: { kind: 'idle' } as State })
  const [branches, setBranches] = useState<string[]>([])
  const [branchError, setBranchError] = useState<string | null>(null)
  const [switching, setSwitching] = useState(false)
  const [figma, setFigma] = useState(() => initialFigma
    ? { url: initialFigma.url, checked: initialFigma.url, state: { kind: 'connected', detail: figmaDetail(initialFigma) } as State }
    : { url: '', checked: '', state: { kind: 'idle' } as State })

  const bothConnected = github.state.kind === 'connected' && figma.state.kind === 'connected'
  const busy = github.state.kind === 'checking' || figma.state.kind === 'checking' || switching
  useEffect(() => { onStatus?.({ bothConnected, busy }) }, [bothConnected, busy, onStatus])
  /** force=[다시 시도]·[다시 확인] — 같은 링크도 다시 확인한다. 칸을 벗어날 때는 바뀐 링크만 확인한다 */
  const check = async (service: Service, force = false, token?: string) => {
    const cur = service === 'github' ? github : figma
    const set = service === 'github' ? setGithub : setFigma
    const url = cur.url.trim()
    if (!url || cur.state.kind === 'checking' || (!force && !token && url === cur.checked)) return
    if (!(service === 'github' ? GITHUB_PATTERN : FIGMA_PATTERN).test(url)) {
      const message = service === 'github' && GITHUB_SUBPATH.test(url) ? GITHUB_SUBPATH_MESSAGE : undefined
      set({ ...cur, checked: url, state: { kind: 'format', message } })
      return
    }
    set({ ...cur, checked: url, state: { kind: 'checking' } })
    try {
      const detail = service === 'github'
        ? await connectGithub(projectId, url).then((c) => { setBranches(c.branches); setBranchError(null); return c.branch })
        : await connectFigma(projectId, url, token).then(figmaDetail)
      set({ url, checked: url, state: { kind: 'connected', detail } })
      onConnected?.()
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) {
        set({ url, checked: url, state: { kind: 'format', message: e.message } })
        return
      }
      if (e instanceof ApiError && e.status === 422) {
        set({ url, checked: url, state: { kind: 'forbidden', message: e.message } })
        return
      }
      // 503 = 서버 Figma 토큰 문제 — 사용자 링크·권한 문제가 아니므로 서버가 준 문구를 그대로 보여준다
      set({ url, checked: url, state: { kind: 'failed', message: e instanceof ApiError && e.status === 503 ? e.message : undefined } })
    }
  }

  /** 브랜치를 바꾸면 같은 링크와 새 브랜치로 다시 연결한다 — 서버가 브랜치가 있는지 확인한다 */
  const changeBranch = async (branch: string) => {
    setSwitching(true)
    setBranchError(null)
    try {
      const c = await connectGithub(projectId, github.url.trim(), branch)
      setBranches(c.branches)
      setGithub({ ...github, state: { kind: 'connected', detail: c.branch } })
      onConnected?.()
    } catch (e) {
      setBranchError(e instanceof ApiError ? e.message : '브랜치를 바꾸지 못했어요.')
    } finally {
      setSwitching(false)
    }
  }

  /** 이미 연결된 저장소는 브랜치 목록이 없다 — 같은 저장소·브랜치로 다시 확인해 목록을 받아 온다 */
  const loadBranches = () => {
    if (github.state.kind === 'connected') void changeBranch(github.state.detail)
  }

  const reset = (service: Service) => (service === 'github' ? setGithub : setFigma)({ url: '', checked: '', state: { kind: 'idle' } })

  // 배너는 문제가 있는 쪽 하나만(시안 CON-E*) — Figma를 먼저 본다
  // Figma 403에는 보기 권한 말고도 내보내기 막힘이 있다 — 서버가 준 이유를 본문으로 보여 준다(2026-11-11)
  const bannerOf = (service: Service, s: State) => {
    const b = BANNERS[service][s.kind]
    return b && s.kind === 'forbidden' && s.message ? { ...b, body: s.message } : b
  }
  const banner = bannerOf('figma', figma.state) ?? bannerOf('github', github.state)

  return (
    <>
      {locked && (
        <div className="flex gap-3 rounded-xl bg-qa-surface px-4 py-3 text-xs text-qa-text-sub">
          <Icon name="lock" className="h-4 w-4 flex-none" />{locked}
        </div>
      )}
      {banner && (
        <div role="alert" className="flex gap-3 rounded-xl bg-qa-warning/10 px-4 py-3">
          <Icon name="alert" className="mt-px h-5 w-5 flex-none text-qa-warning" />
          <div>
            <div className="text-sm font-medium text-black">{banner.title}</div>
            <div className="mt-0.5 text-xs text-black">{banner.body}</div>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <ServiceCard
          service="github" title="GitHub 연결" label="Repository 링크" placeholder="ex: https://github.com/..."
          value={github.url} state={github.state}
          onChange={(url) => setGithub({ ...github, url, state: github.state.kind === 'connected' ? github.state : { kind: 'idle' } })}
          onCheck={(force) => void check('github', force)} onReset={() => reset('github')}
          locked={!!locked}
          extra={github.state.kind === 'connected' && (branches.length === 0 ? (
            <div className="flex items-center justify-between gap-2 text-xs">
              <span className="text-qa-text-sub">분석할 브랜치 <span className="font-medium text-black">{github.state.detail}</span></span>
              {!locked && (
                <button type="button" disabled={switching} onClick={loadBranches} className="font-medium text-qa-primary-hover hover:underline disabled:opacity-40">
                  {switching ? '불러오는 중…' : '브랜치 변경'}
                </button>
              )}
              {branchError && <span className="text-qa-warning">{branchError}</span>}
            </div>
          ) : (
            <div className="flex flex-col gap-1.5">
              <label htmlFor="connect-github-branch" className="text-xs font-medium text-black">분석할 브랜치</label>
              <div className="relative">
                <select id="connect-github-branch" value={github.state.detail} disabled={switching || !!locked}
                  onChange={(e) => void changeBranch(e.target.value)}
                  className="h-10 w-full appearance-none rounded-lg border border-qa-sub bg-white pl-3 pr-9 text-sm text-black outline-none focus:border-qa-primary disabled:bg-qa-surface">
                  {(branches.includes(github.state.detail) ? branches : [github.state.detail, ...branches]).map((b) => <option key={b} value={b}>{b}</option>)}
                </select>
                <Icon name="chevron-down" className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-black" />
              </div>
              {branchError && <span className="text-xs text-qa-warning">{branchError}</span>}
            </div>
          ))}
        />
        <ServiceCard
          service="figma" title="Figma 연결" label="Figma 페이지·섹션 링크" placeholder="ex: https://www.figma.com/design/…?node-id=…"
          guide="최종 시안만 담은 페이지의 [공유하기] → [링크 복사] 링크를 붙여 넣어 주세요. 지난 시안이 같은 페이지에 섞여 있다면, 최종 시안 섹션을 우클릭해 [링크 복사]한 섹션 링크를 넣어 주세요."
          locked={!!locked}
          value={figma.url} state={figma.state}
          onChange={(url) => setFigma({ ...figma, url, state: figma.state.kind === 'connected' ? figma.state : { kind: 'idle' } })}
          onCheck={(force) => void check('figma', force)} onReset={() => reset('figma')}
          extra={figma.state.kind === 'forbidden' && !locked && <OwnTokenForm onSubmit={(token) => void check('figma', true, token)} />}
        />
      </div>

    </>
  )
}

/**
 * 소유자 본인 Figma 토큰(2026-11-11) — QAting 서버 계정이 이 파일을 읽지 못할 때만(권한 없음·내보내기 막힘·Figma 한도) 보인다.
 * 토큰은 서버가 암호화해 저장하고 다시 보여 주지 않는다. 읽기 권한만 있는 토큰을 만들게 안내한다
 */
function OwnTokenForm({ onSubmit }: { onSubmit: (token: string) => void }) {
  const [token, setToken] = useState('')
  return (
    <form className="flex flex-col gap-2 rounded-lg bg-qa-surface p-3" onSubmit={(e) => { e.preventDefault(); if (token.trim()) onSubmit(token.trim()) }}>
      <div className="text-xs font-medium text-black">본인 Figma 토큰으로 연결</div>
      <ol className="list-decimal space-y-0.5 pl-4 text-xs leading-4 text-qa-text-sub">
        <li>Figma 왼쪽 위 계정 메뉴 → <b>Settings</b> → <b>Security</b> → <b>Personal access tokens</b>에서 <b>Generate new token</b></li>
        <li>권한은 <b>File content: Read only</b> 하나만 고르세요</li>
        <li>이 파일을 볼 수 있는 계정의 토큰이어야 해요</li>
      </ol>
      <div className="flex gap-2">
        <label htmlFor="connect-figma-token" className="sr-only">Figma 토큰</label>
        <input id="connect-figma-token" type="password" autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)}
          placeholder="figd_…"
          className="h-9 min-w-0 flex-1 rounded-lg border border-qa-sub bg-white px-3 text-xs outline-none focus:border-qa-primary" />
        <button type="submit" disabled={!token.trim()}
          className="h-9 flex-none rounded-lg bg-qa-primary px-3 text-xs font-medium text-white hover:bg-qa-primary-hover disabled:opacity-40">토큰으로 연결</button>
      </div>
      <p className="text-xs leading-4 text-qa-text-sub">토큰은 암호화해 저장하고 다시 보여 주지 않아요. 이 프로젝트의 Figma 읽기에만 써요.</p>
    </form>
  )
}

interface ServiceCardProps {
  service: Service
  title: string
  label: string
  placeholder: string
  value: string
  state: State
  /** 입력 칸 아래 안내 — 연결 전·오류일 때 보인다 */
  guide?: string
  /** 입력 칸 아래 추가 칸(GitHub 브랜치 선택) */
  extra?: ReactNode
  /** 바꿀 수 없음 — 입력 칸을 잠그고 [변경]을 숨긴다 */
  locked?: boolean
  onChange: (value: string) => void
  onCheck: (force?: boolean) => void
  onReset: () => void
}

function ServiceCard({ service, title, label, placeholder, value, state, guide, extra, locked, onChange, onCheck, onReset }: ServiceCardProps) {
  const connected = state.kind === 'connected'
  const warn = state.kind === 'forbidden'
  const error = state.kind === 'failed' || state.kind === 'format'
  const border = connected ? 'border-qa-border' : warn ? 'border-[#a85a00]' : error ? 'border-qa-warning' : 'border-qa-border'
  const id = `connect-${service}`
  return (
    <div className={`flex flex-col gap-3 rounded-xl border bg-white p-4 ${border}`}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 text-base font-bold text-black">
          <Icon name={service} className="h-5 w-5" />{title}
        </div>
        <StatusMark state={state} />
      </div>
      <div className="flex flex-col gap-1.5">
        <label htmlFor={id} className="text-xs font-medium text-black">{label}</label>
        <input
          id={id}
          value={value}
          readOnly={connected || locked || state.kind === 'checking'}
          onChange={(e) => onChange(e.target.value)}
          onBlur={() => onCheck()}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); onCheck() } }}
          placeholder={placeholder}
          aria-invalid={error || warn}
          className={`h-10 w-full truncate rounded-lg border px-3 text-sm outline-none placeholder:text-qa-sub read-only:bg-qa-surface ${
            error ? 'border-qa-warning' : 'border-qa-sub focus:border-qa-primary'}`}
        />
      </div>
      {guide && state.kind !== 'connected' && state.kind !== 'checking' && (
        <p className="flex gap-1.5 text-xs leading-5 text-qa-text-sub"><Icon name="alert-circle" className="mt-0.5 h-3.5 w-3.5 flex-none" />{guide}</p>
      )}
      {extra}
      <StatusLine service={service} state={state} onRetry={() => onCheck(true)} onReset={locked ? undefined : onReset} />
    </div>
  )
}

function StatusMark({ state }: { state: State }) {
  if (state.kind === 'checking') {
    return <span className="h-5 w-5 animate-spin rounded-full border-2 border-[#e8e8e8] border-t-qa-primary-hover" aria-label="확인 중" />
  }
  if (state.kind === 'connected') {
    return <span className="flex h-5 w-5 items-center justify-center rounded-full border-2 border-qa-success text-qa-success" aria-label="연결됨"><Icon name="check" className="h-3 w-3" /></span>
  }
  if (state.kind === 'forbidden' || state.kind === 'failed' || state.kind === 'format') {
    return <Icon name="alert" className={`h-5 w-5 ${state.kind === 'forbidden' ? 'text-[#a85a00]' : 'text-qa-warning'}`} />
  }
  return <span className="h-5 w-5 rounded-full border-2 border-qa-sub" aria-label="연결 전" />
}

function StatusLine({ service, state, onRetry, onReset }: { service: Service; state: State; onRetry: () => void; onReset?: () => void }) {
  const action = (text: string, onClick: () => void) => (
    <button type="button" onClick={onClick} className="flex-none text-xs font-medium text-qa-primary-hover hover:underline">{text}</button>
  )
  switch (state.kind) {
    case 'checking':
      return <p className="text-xs text-qa-text-sub">{service === 'figma' ? '파일 보기 권한을' : '저장소 읽기 권한을'} 확인하고 있어요… 링크 입력만으로는 연결되지 않아요.</p>
    case 'connected':
      return (
        <div className="flex items-center justify-between gap-2">
          <span className="flex min-w-0 items-center gap-1 truncate text-xs text-qa-success">
            <Icon name="check" className="h-3.5 w-3.5 flex-none" />
            {service === 'figma' ? `보기 권한 확인됨 · ${state.detail}` : `읽기 권한 확인됨 · ${state.detail} 브랜치`}
          </span>
          {onReset && action('변경', onReset)}
        </div>
      )
    case 'forbidden':
      return (
        <div className="flex flex-col gap-1">
          <span className="text-xs font-medium text-[#a85a00]">{service === 'figma' ? '파일 접근 권한 필요' : '공개 저장소만 연결할 수 있어요'}</span>
          <div className="flex items-center justify-between gap-2">
            <span className="text-xs text-qa-text-sub">
              {state.message ?? (service === 'figma' ? '파일 소유자에게 보기 권한을 요청한 뒤 다시 확인해 주세요.' : '저장소를 공개로 바꾸거나 링크를 확인한 뒤 다시 확인해 주세요.')}
            </span>
            {action('다시 확인', onRetry)}
          </div>
        </div>
      )
    case 'failed':
      return (
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs text-qa-warning">{state.message ?? (service === 'figma' ? '파일을 찾을 수 없거나 Figma 응답이 지연되고 있어요' : '응답 시간이 초과되었거나 GitHub 오류가 있었어요')}</span>
          {action('다시 시도', onRetry)}
        </div>
      )
    case 'format':
      return (
        <span className="flex items-center gap-1 text-xs text-black">
          <Icon name="alert-circle" className="h-4 w-4 flex-none text-qa-warning" />
          {state.message ?? (service === 'figma' ? 'Figma 페이지·섹션 링크가 아니에요' : '올바른 GitHub 저장소 링크가 아니에요')}
        </span>
      )
    default:
      return null
  }
}
