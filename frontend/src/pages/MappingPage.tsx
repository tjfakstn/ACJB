import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  ApiError, fetchMe, formatDateTime, getMappings, getProjectDetail, type FrameMapping, type MappingList, type Me, type ProjectDetail,
} from '../lib/api'
import { AppHeader } from '../components/projects/AppHeader'
import { WorkspaceSidebar } from '../components/members/WorkspaceSidebar'
import { Icon } from '../components/common/Icon'
import { CARD } from '../components/workspace/WorkspaceCards'

// 디자인-코드 매핑(시안 WS-03, SPEC AC27) — 최근 성공한 비교 분석이 만든 Figma 화면·요소 ↔ 코드 파일·컴포넌트 짝.
// 시안과 다른 점(서버가 주지 않는 값은 지어내지 않는다):
//  - 매핑 완료율·부분 매핑·평균 신뢰도, 디자인 속성(너비·색 등), 매핑 근거 문장, 커밋·PR 추적(N13)은 없다
//  - 대신 매핑된 화면·요소 수, 근거가 없어 버린 짝 수, 분석한 커밋, 화면 안 요소별 컴포넌트를 보여 준다
//  - 왼쪽 목록은 시안의 "Figma 요소"를 화면(프레임) 단위로 보여 준다
export function MappingPage() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const [me, setMe] = useState<Me | null>(null)
  const [detail, setDetail] = useState<ProjectDetail | null>(null)
  const [data, setData] = useState<MappingList | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<string | null>(null)

  useEffect(() => {
    fetchMe().then((result) => {
      if (!result.authenticated) {
        navigate('/login', { replace: true })
        return
      }
      setMe(result)
      Promise.all([getProjectDetail(projectId), getMappings(projectId)])
        .then(([d, m]) => { setDetail(d); setData(m); setSelected(m.items[0]?.frame_id ?? null) })
        .catch((e) => setError(e instanceof ApiError && e.status === 403 ? '이 프로젝트의 멤버가 아니에요.' : '매핑 정보를 불러오지 못했어요.'))
    })
  }, [navigate, projectId])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (data?.items ?? []).filter((f) => !q || f.frame_name.toLowerCase().includes(q) || f.code_path.toLowerCase().includes(q))
  }, [data, query])
  const current = data?.items.find((f) => f.frame_id === selected) ?? null

  if (!me) return null

  const nodes = data?.items.reduce((n, f) => n + f.nodes.length, 0) ?? 0
  const finished = data?.run?.finished_at

  return (
    <div className="min-h-screen bg-qa-surface">
      <AppHeader />
      <div className="flex min-h-[calc(100vh-64px)]">
        <WorkspaceSidebar projectId={projectId} />
        <main className="min-w-0 flex-1 px-8 pb-8 pt-8">
          <div className="flex items-end justify-between gap-4">
            <div className="min-w-0">
              <nav className="flex items-center gap-1 text-xs leading-4">
                <Link to={`/app/projects/${projectId}`} className="text-qa-text-sub hover:underline">{detail?.name ?? '프로젝트'}</Link>
                <Icon name="chevron-right" className="h-3 w-3 text-qa-text-sub" />
                <span className="font-medium text-black">디자인-코드 매핑</span>
              </nav>
              <h1 className="mt-2 text-[28px] font-bold leading-8 text-black">디자인-코드 매핑</h1>
              <p className="mt-2 text-sm leading-5 text-qa-text-sub">Figma 화면과 구현 코드의 연결 관계를 확인하세요. 첫 QA는 이 짝을 기준으로 값을 비교해요.</p>
            </div>
            {finished && <span className="flex-none text-xs text-qa-text-sub">마지막 분석: {formatDateTime(finished)}</span>}
          </div>

          {error ? (
            <div role="alert" className={`${CARD} mt-8 p-10 text-center text-sm font-semibold text-black`}>{error}</div>
          ) : !data ? (
            <div className="mt-8 text-sm text-qa-text-sub">불러오는 중…</div>
          ) : data.items.length === 0 ? (
            <div className={`${CARD} mt-8 flex flex-col items-center px-6 py-12 text-center`}>
              <Icon name="git-compare" className="h-7 w-7 text-gray-900" />
              <div className="mt-3 text-sm font-medium text-black">아직 디자인-코드 매핑이 없어요</div>
              <p className="mt-2 text-xs text-qa-text-sub">
                {data.run ? '최근 분석에서 구현 파일과 짝을 찾은 화면이 없어요. 연결한 Figma 페이지·섹션과 GitHub 브랜치를 확인해 주세요.'
                  : '작업공간 홈의 "최근 AI 분석"에서 비교 분석을 마치면 여기에 나타나요.'}
              </p>
              <Link to={`/app/projects/${projectId}`} className="mt-4 text-sm font-medium text-qa-primary-hover hover:underline">작업공간 홈으로</Link>
            </div>
          ) : (
            <>
              <section className={`${CARD} mt-8 flex flex-wrap items-center gap-x-8 gap-y-4 rounded-2xl px-6 py-5`}>
                <Metric label="매핑된 화면" value={`${data.items.length}개`} helper="구현 파일과 짝을 찾은 Figma 화면" />
                <span className="hidden h-[76px] w-px bg-qa-border md:block" />
                <Metric label="매핑된 요소" value={`${nodes}개`} helper="화면 안 블록 ↔ 컴포넌트" />
                <Metric label="버린 짝" value={`${data.run?.dropped_count ?? 0}개`} helper="코드에 없는 경로·요소를 가리킨 답" />
                <div className="ml-auto text-right">
                  <div className="text-xs font-medium text-qa-text-sub">분석한 커밋</div>
                  <div className="mt-1 font-mono text-base font-bold text-black">{data.commit_sha?.slice(0, 7) ?? '-'}</div>
                  <div className="mt-1 text-xs text-qa-text-sub">{detail?.github ? `${detail.github.repo} · ${detail.github.branch}` : ''}</div>
                </div>
              </section>

              <div className="mt-8 grid grid-cols-1 items-start gap-6 lg:grid-cols-[320px_minmax(0,1fr)]">
                <section>
                  <div className="flex h-6 items-center gap-2">
                    <h2 className="text-base font-bold text-black">Figma 화면 목록</h2>
                    <span className="flex h-6 items-center rounded-full border border-qa-border bg-qa-surface px-2 text-xs font-medium text-black">{data.items.length}개</span>
                  </div>
                  <label className="relative mt-3 block">
                    <span className="sr-only">화면 검색</span>
                    <Icon name="search" className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-qa-text-sub" />
                    <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="화면·파일 검색"
                      className="h-10 w-full rounded-lg border border-qa-sub bg-white pl-9 pr-3 text-sm outline-none placeholder:text-qa-sub focus:border-qa-primary" />
                  </label>
                  <ul className="mt-3 flex max-h-[640px] flex-col gap-1 overflow-y-auto pr-1">
                    {filtered.map((f) => (
                      <li key={f.frame_id}>
                        <button type="button" onClick={() => setSelected(f.frame_id)} aria-current={f.frame_id === selected}
                          className={`flex min-h-11 w-full items-center gap-2 rounded-lg px-4 py-2 text-left text-sm ${f.frame_id === selected ? 'bg-qa-primary/10 font-medium text-black' : 'text-black hover:bg-white'}`}>
                          <span className="min-w-0 flex-1 truncate">{f.frame_name}</span>
                          <MappedBadge />
                        </button>
                      </li>
                    ))}
                    {filtered.length === 0 && <li className="px-4 py-6 text-center text-xs text-qa-text-sub">검색 결과가 없어요</li>}
                  </ul>
                </section>
                {current && <Detail frame={current} repo={detail?.github?.repo ?? null} sha={data.commit_sha} />}
              </div>
            </>
          )}
        </main>
      </div>
    </div>
  )
}

function Metric({ label, value, helper }: { label: string; value: string; helper: string }) {
  return (
    <div>
      <div className="text-xs font-medium text-qa-text-sub">{label}</div>
      <div className="mt-1 text-[28px] font-bold leading-9 text-black">{value}</div>
      <div className="mt-1 text-xs text-qa-text-sub">{helper}</div>
    </div>
  )
}

function MappedBadge() {
  return (
    <span className="flex h-6 flex-none items-center gap-1.5 rounded-full bg-qa-success/10 px-2 text-xs font-medium text-black">
      <span className="h-1.5 w-1.5 rounded-full bg-qa-success" />매핑됨
    </span>
  )
}

/** GitHub에서 그 커밋의 파일을 연다 — 분석이 읽은 코드 그대로 */
function fileUrl(repo: string | null, sha: string | null, path: string) {
  return repo && sha ? `https://github.com/${repo}/blob/${sha}/${path}` : null
}

function Detail({ frame, repo, sha }: { frame: FrameMapping; repo: string | null; sha: string | null }) {
  const href = fileUrl(repo, sha, frame.code_path)
  const fileName = frame.code_path.slice(frame.code_path.lastIndexOf('/') + 1)
  return (
    <section className={`${CARD} rounded-2xl p-5`}>
      <div className="flex h-6 items-center gap-2">
        <h2 className="text-base font-bold text-black">선택 화면 · 매핑 상세</h2>
        <MappedBadge />
      </div>
      <div className="mt-4 text-xl font-bold leading-7 text-black">{frame.frame_name}</div>

      <div className="mt-6 text-xs font-medium text-qa-text-sub">연결된 구현 파일</div>
      <div className="mt-3 flex items-center gap-3 rounded-lg bg-qa-surface px-4 py-3">
        <Icon name="file-code" className="h-5 w-5 flex-none text-gray-900" />
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium text-black">{fileName}</div>
          <div className="truncate text-xs text-qa-text-sub">{frame.code_path}</div>
        </div>
        {href && (
          <a href={href} target="_blank" rel="noreferrer" className="flex flex-none items-center gap-1 text-xs font-medium text-qa-primary-hover hover:underline">
            GitHub에서 보기<Icon name="external-link" className="h-3.5 w-3.5" />
          </a>
        )}
      </div>

      <div className="mt-6 h-px bg-qa-border" />
      <div className="mt-4 text-xs font-medium text-qa-text-sub">화면 안 요소 ↔ 컴포넌트 ({frame.nodes.length}개)</div>
      {frame.nodes.length === 0 ? (
        <p className="mt-3 rounded-lg border border-qa-border px-4 py-3 text-sm text-qa-text-sub">이 화면 안에서 컴포넌트와 짝을 찾은 요소가 없어요.</p>
      ) : (
        <ul className="mt-3 flex flex-col gap-2">
          {frame.nodes.map((n) => {
            const nodeHref = fileUrl(repo, sha, n.code_path)
            return (
              <li key={n.node_id} className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-lg border border-qa-border px-4 py-3">
                <span className="min-w-[160px] flex-1 text-sm font-medium text-black">{n.node_name}</span>
                <span className="font-mono text-sm text-black">{n.code_symbol}</span>
                {nodeHref ? (
                  <a href={nodeHref} target="_blank" rel="noreferrer" className="w-full truncate text-xs text-qa-text-sub hover:underline sm:w-auto">{n.code_path}</a>
                ) : (
                  <span className="w-full truncate text-xs text-qa-text-sub sm:w-auto">{n.code_path}</span>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
