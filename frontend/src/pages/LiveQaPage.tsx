import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  ApiError, captureScreen, createManualQa, formatDateTime, getDeployTarget, getFirstQa, listIssues, listMembers, manualQaId,
  TEAM_LABELS, updateDeployUrl, uploadCapture, type DeployTarget, type ManualQaInput, type ProjectMember, type ProjectRun,
  type QaCapture, type QaIssue, type Team, type Viewport,
} from '../lib/api'
import { Icon } from '../components/common/Icon'
import { WorkflowStepper } from '../components/workspace/WorkspaceCards'
import { currentStep } from '../components/workspace/workflow'
import { AssignPanel } from '../components/qa/AssignPanel'
import { ErrorCard, Loading, OUTLINE_BTN, PageTitle, PRIMARY_BTN, QaShell, loadErrorMessage, useProject } from '../components/qa/QaParts'
import { LivePreview, type PreviewSurface } from '../components/liveqa/LivePreview'
import { QaItemList } from '../components/liveqa/QaItemList'
import { ManualQaDetail } from '../components/liveqa/ManualQaDetail'
import { CaptureStatusBox, OptionalFields, RequiredFields } from '../components/liveqa/DraftFields'
import { hasContent, newRequestId, type Draft } from '../components/liveqa/draft'

// 실시간 QA 워크스페이스(시안 W-01~05, M-01~M-10C · SPEC AC29~AC31). 지금까지의 목록형 QA 결과 화면을 대신한다.
//  위: 배포 화면(Live Preview) — 배포 주소는 소유자가 입력한 값, 없으면 GitHub 최근 성공 배포 주소(AC29)
//  아래 왼쪽: QA 항목 — 자동 QA(첫 QA 불일치)와 수동 QA(배포 화면 위에서 찍어 등록)
//  아래 오른쪽: 상세 — 자동 QA는 불일치 근거, 수동 QA는 캡처·위치·설명. 담당 팀은 둘 다 사람이 직접 배정한다
// 시안과 다른 점:
//  - 시안 W의 "클릭 동작 자동 검사"(로그인 버튼 → /login 확인)는 E2E 자동화라 v1에 없다(N13) — 자동 QA 자리에는 첫 QA 결과가 온다
//  - "검사 오류"·"대기" 상태, 항목별 실행 진행, 유사 항목 안내, 겹친 핀 묶음·화면 밖 핀 칩(M-07)은 v1에서 뺐다(decision_log 2026-11-10)
//  - 핀 번호·QA-M ID는 저장에 성공할 때 서버가 발급한다(M-03 "확인 필요" 권장안). 작성 중에는 다음 번호를 임시로 보여 준다
export function LiveQaPage() {
  const { projectId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const { me, detail, error, reload } = useProject(projectId)
  const [issues, setIssues] = useState<QaIssue[] | null>(null)
  const [members, setMembers] = useState<ProjectMember[]>([])
  const [target, setTarget] = useState<DeployTarget | null>(null)
  const [run, setRun] = useState<ProjectRun | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [path, setPath] = useState('/')
  const [reloadKey, setReloadKey] = useState(0)
  const [registerMode, setRegisterMode] = useState(false)
  const [surface, setSurface] = useState<PreviewSurface>({ kind: 'live', attachment: null })
  const [attachBusy, setAttachBusy] = useState(false)
  const [attachError, setAttachError] = useState<string | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const [editingUrl, setEditingUrl] = useState(false)
  const [toast, setToast] = useState<{ text: string; sub: string } | null>(null)
  const selectedId = params.get('issue')
  const draftRef = useRef<Draft | null>(null)
  draftRef.current = draft

  const load = useCallback(async () => {
    try {
      const [list, mem, t, f] = await Promise.all([listIssues(projectId), listMembers(projectId), getDeployTarget(projectId), getFirstQa(projectId)])
      setIssues(list)
      setMembers(mem)
      setTarget(t)
      setRun(f)
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

  const patchDraft = useCallback((patch: Partial<Draft>) => setDraft((d) => (d ? { ...d, ...patch } : d)), [])

  /** 작성 중 내용이 있으면 확인을 받고, 없으면 바로 닫는다(M-02C) */
  const requestClose = useCallback(() => {
    const d = draftRef.current
    if (d && hasContent(d) && !d.saving) setConfirmDiscard(true)
    else if (d && !d.saving) setDraft(null)
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || confirmDiscard) return
      if (registerMode) setRegisterMode(false)
      else if (draftRef.current) requestClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [registerMode, confirmDiscard, requestClose])

  const isOwner = !!me && !!detail && detail.owner_id === me.id
  const manualPins = useMemo(() => (issues ?? []).filter((i) => i.manual_qa), [issues])
  const nextPin = useMemo(() => manualPins.reduce((max, i) => Math.max(max, i.manual_qa!.pin_number), 0) + 1, [manualPins])

  if (!me) return null
  const selected = issues?.find((i) => i.id === selectedId) ?? null
  const select = (id: string | null) => setParams(id ? { issue: id } : {}, { replace: true })

  // 서버 캡처는 입력을 막지 않고 뒤에서 진행한다. 결과는 같은 작성 건(requestId)일 때만 반영한다
  const startCapture = (requestId: string, pagePath: string, viewport: Viewport) => {
    patchDraft({ capture: { state: 'loading' } })
    captureScreen(projectId, pagePath, viewport)
      .then((capture) => setDraft((d) => (d && d.requestId === requestId ? { ...d, capture: { state: 'ok', capture } } : d)))
      .catch((e) => setDraft((d) => (d && d.requestId === requestId
        ? { ...d, capture: { state: 'failed', message: e instanceof ApiError ? e.message : '캡처하지 못했어요.' } } : d)))
  }

  const onPick = (x: number, y: number, viewport: Viewport) => {
    setRegisterMode(false)
    const basis = surface.kind === 'image' ? 'capture_image' as const : 'live_overlay' as const
    const requestId = newRequestId()
    const attachment = surface.kind === 'image' ? surface.attachment : null
    setDraft({
      requestId, basis, x, y, viewport, pagePath: path, title: '', description: '', severity: null, expected: '', actual: '',
      capture: attachment ? { state: 'ok', capture: attachment } : { state: 'loading' },
      expanded: false, saving: false, errors: {}, saveError: null,
    })
    select(null)
    if (basis === 'live_overlay') startCapture(requestId, path, viewport)
  }

  const attachToDraft = async (file: File) => {
    const d = draftRef.current
    if (!d) return
    patchDraft({ capture: { state: 'loading' } })
    try {
      const capture = await uploadCapture(projectId, file)
      setDraft((cur) => (cur && cur.requestId === d.requestId ? { ...cur, capture: { state: 'ok', capture } } : cur))
    } catch (e) {
      setDraft((cur) => (cur && cur.requestId === d.requestId
        ? { ...cur, capture: { state: 'failed', message: e instanceof ApiError ? e.message : '이미지를 올리지 못했어요.' } } : cur))
    }
  }

  const attachForImage = async (file: File) => {
    setAttachBusy(true)
    setAttachError(null)
    try {
      const capture: QaCapture = await uploadCapture(projectId, file)
      setSurface({ kind: 'image', attachment: capture })
    } catch (e) {
      setAttachError(e instanceof ApiError ? e.message : '이미지를 올리지 못했어요.')
    } finally {
      setAttachBusy(false)
    }
  }

  const submit = async () => {
    const d = draftRef.current
    if (!d || d.saving) return
    const errors: Draft['errors'] = {}
    if (!d.title.trim()) errors.title = 'QA 제목을 입력해 주세요.'
    if (!d.description.trim()) errors.description = '문제 설명을 입력해 주세요.'
    if (errors.title || errors.description) {
      patchDraft({ errors })
      // 첫 오류 항목으로 포커스(M-08A)
      setTimeout(() => document.getElementById(errors.title ? 'mq-title' : 'mq-description')?.focus(), 0)
      return
    }
    patchDraft({ saving: true, saveError: null })
    const input: ManualQaInput = {
      client_request_id: d.requestId,
      title: d.title.trim(),
      description: d.description.trim(),
      page_path: d.pagePath,
      anchor: { x_px: d.x, y_px: d.y },
      viewport: d.viewport,
      location_basis: d.basis,
      ...(d.capture.state === 'ok' ? { capture_id: d.capture.capture.id } : { capture_status: d.capture.state === 'failed' ? 'failed' as const : 'none' as const }),
      ...(d.severity ? { severity: d.severity } : {}),
      ...(d.expected.trim() ? { expected_result: d.expected.trim() } : {}),
      ...(d.actual.trim() ? { actual_result: d.actual.trim() } : {}),
    }
    try {
      const created = await createManualQa(projectId, input)
      setIssues((list) => [created, ...(list ?? []).filter((i) => i.id !== created.id)])
      setDraft(null)
      select(created.id)
      void reload()
      setToast({ text: `${manualQaId(created.manual_qa!.pin_number)} 수동 QA를 등록했어요`, sub: '목록·핀·상세에 반영했어요 · 담당 팀은 직접 배정해 주세요' })
    } catch (e) {
      // 입력은 그대로 두고 같은 요청 id로 다시 시도한다(M-08B)
      patchDraft({ saving: false, saveError: e instanceof ApiError ? e.message : 'QA를 저장하지 못했어요. 입력 내용은 그대로 있어요.' })
    }
  }

  const onAssigned = (updated: QaIssue, previous: Team | null) => {
    setIssues((list) => list?.map((i) => (i.id === updated.id ? updated : i)) ?? null)
    void reload()
    const team = TEAM_LABELS[updated.assigned_team!]
    const count = members.filter((m) => m.role === updated.assigned_team).length
    const notice = count > 0 ? `${team} 멤버 ${count}명에게 알림을 보냈어요` : `${team} 역할 멤버가 없어 알림은 보내지 않았어요`
    setToast(previous
      ? { text: `담당 팀을 ${TEAM_LABELS[previous]}에서 ${team}으로 변경했어요`, sub: notice }
      : { text: `${team}에 배정했어요`, sub: `${notice} · QA Queue에서 수정 진행을 관리해요` })
  }

  const failMessage = error ?? loadError
  const ready = detail && issues && target
  const runLabel = run?.status === 'succeeded'
    ? <span className="rounded-full bg-qa-success/10 px-2 py-0.5 font-medium text-qa-success">첫 QA 완료</span>
    : run && (run.status === 'running' || run.status === 'queued')
      ? <span className="rounded-full bg-qa-primary/10 px-2 py-0.5 font-medium text-qa-primary-hover">실행 중</span>
      : <span className="rounded-full bg-qa-surface px-2 py-0.5 font-medium text-qa-text-sub ring-1 ring-qa-border">첫 QA 전</span>
  const autoCount = issues?.filter((i) => i.source === 'first_qa').length ?? 0
  const manualCount = issues?.filter((i) => i.source === 'manual').length ?? 0

  const draftPanel = draft && (
    <>
      <div className="mt-3 flex items-center gap-1.5 text-xs text-qa-text-sub">
        <Icon name="map-pin" className="h-3.5 w-3.5" />
        {draft.basis === 'capture_image' ? '첨부 이미지 기준' : `위치 ${draft.pagePath}`} · x {draft.x}, y {draft.y} · 기준 {draft.viewport.width}×{draft.viewport.height}
      </div>
      {draft.saveError && (
        <div role="alert" className="mt-3 rounded-lg bg-qa-warning/10 px-3 py-2 text-xs">
          <div className="font-medium text-[#c8161a]">저장하지 못했어요</div>
          <div className="text-qa-text-sub">{draft.saveError} 입력 내용은 그대로 있어요 — 다시 시도해도 두 번 등록되지 않아요.</div>
        </div>
      )}
    </>
  )
  const submitLabel = draft?.saving ? '등록 중…' : draft?.capture.state === 'loading' ? '캡처 기다리는 중…'
    : draft?.capture.state === 'failed' || draft?.capture.state === 'none' ? '캡처 없이 QA 등록하기' : 'QA 등록하기'
  const draftButtons = draft && (
    <div className="flex items-center justify-end gap-2">
      <button type="button" onClick={requestClose} disabled={draft.saving} className={`${OUTLINE_BTN} h-9`}>취소</button>
      <button type="button" onClick={() => void submit()} disabled={draft.saving || draft.capture.state === 'loading'} aria-busy={draft.saving}
        className={`${PRIMARY_BTN} h-9`}>
        {draft.saving && <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white border-t-transparent" />}{submitLabel}
      </button>
    </div>
  )

  return (
    <QaShell projectId={projectId}>
      <PageTitle
        crumbs={[{ label: detail?.name ?? '프로젝트', to: `/app/projects/${projectId}` }, { label: '디자인 QA', to: `/app/projects/${projectId}/qa` }, { label: '실시간 QA 워크스페이스' }]}
        title="실시간 QA 워크스페이스"
        description={ready
          ? `${target.url ? '배포 화면 연결됨' : '배포 주소 없음'} · 자동 QA ${autoCount}건 · 수동 QA ${manualCount}건${run?.status === 'succeeded' ? ` · 첫 QA ${formatDateTime(run.finished_at ?? run.created_at)} 완료` : ' · 첫 QA 전'}`
          : undefined}
        actions={<Link to={`/app/projects/${projectId}/queue`} className={PRIMARY_BTN}>QA Queue로 이동</Link>}
      />
      {failMessage ? <ErrorCard message={failMessage} projectId={projectId} /> : !ready ? <Loading /> : (
        <>
          <WorkflowStepper current={currentStep(detail)} />
          <div className="mt-6">
            <LivePreview
              projectId={projectId} target={target} path={path}
              onPathChange={(p) => { setPath(p); setSurface({ kind: 'live', attachment: null }) }}
              reloadKey={reloadKey} onReload={() => setReloadKey((k) => k + 1)}
              surface={surface} registerMode={registerMode}
              onToggleRegister={() => { if (draft) requestClose(); else setRegisterMode((v) => !v) }}
              onPick={onPick} pins={manualPins}
              draftPin={draft ? { x: draft.x, y: draft.y, viewport: draft.viewport, number: nextPin } : null}
              selectedId={selectedId} onSelectPin={(id) => { if (!draft) select(id) }}
              onAttachForImage={(f) => void attachForImage(f)} attachBusy={attachBusy} attachError={attachError}
              isOwner={isOwner} onEditUrl={() => setEditingUrl(true)} runLabel={runLabel}>
              {draft && !draft.expanded && (
                <Popover draft={draft}>
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2 text-sm font-bold text-black">
                      <span className="flex h-5 w-5 items-center justify-center rounded-full border-2 border-dashed border-[#7c3aed] text-[10px] text-[#7c3aed]">{nextPin}</span>
                      새 QA 항목
                      <span className="rounded bg-[#7c3aed]/10 px-1.5 text-xs font-medium text-[#7c3aed]">수동 QA</span>
                    </div>
                    <button type="button" onClick={requestClose} aria-label="작성 닫기" className="rounded p-1 text-qa-text-sub hover:bg-qa-surface"><Icon name="x" className="h-4 w-4" /></button>
                  </div>
                  {draftPanel}
                  <div className="mt-3"><RequiredFields draft={draft} onChange={patchDraft} autoFocus /></div>
                  <div className="mt-3"><CaptureStatusBox draft={draft} projectId={projectId} onAttach={(f) => void attachToDraft(f)}
                    onRetry={() => startCapture(draft.requestId, draft.pagePath, draft.viewport)} /></div>
                  <div className="mt-3 flex items-center justify-between gap-2">
                    <button type="button" onClick={() => patchDraft({ expanded: true })} disabled={draft.saving}
                      className="inline-flex items-center gap-1 text-xs font-medium text-qa-primary-hover hover:underline">
                      <Icon name="maximize" className="h-3.5 w-3.5" />상세 입력
                    </button>
                    {draftButtons}
                  </div>
                </Popover>
              )}
            </LivePreview>
          </div>

          <div className="mt-6 grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(320px,440px)_minmax(0,1fr)]">
            <QaItemList issues={issues} members={members} selectedId={draft ? null : selectedId} onSelect={(id) => { if (!draft) select(id) }} />
            {draft?.expanded ? (
              <section className="rounded-xl border border-[#7c3aed]/50 bg-white p-5" aria-label="수동 QA 작성">
                <div className="flex items-center justify-between gap-2">
                  <h2 className="flex items-center gap-2 text-base font-bold text-black">
                    <span className="flex h-5 w-5 items-center justify-center rounded-full border-2 border-dashed border-[#7c3aed] text-[10px] text-[#7c3aed]">{nextPin}</span>
                    수동 QA 작성 <span className="rounded bg-[#7c3aed]/10 px-1.5 text-xs font-medium text-[#7c3aed]">수동 QA</span>
                  </h2>
                  <button type="button" onClick={requestClose} aria-label="작성 닫기" className="rounded p-1 text-qa-text-sub hover:bg-qa-surface"><Icon name="x" className="h-4 w-4" /></button>
                </div>
                {draftPanel}
                <div className="mt-4 grid grid-cols-1 gap-5 2xl:grid-cols-2">
                  <div>
                    <div className="mb-2 flex items-center gap-1.5 text-xs font-medium text-black"><Icon name="camera" className="h-4 w-4" />등록 당시 캡처 · 증거 이미지</div>
                    <CaptureStatusBox draft={draft} projectId={projectId} onAttach={(f) => void attachToDraft(f)}
                      onRetry={() => startCapture(draft.requestId, draft.pagePath, draft.viewport)} />
                  </div>
                  <div>
                    <RequiredFields draft={draft} onChange={patchDraft} autoFocus />
                    <OptionalFields draft={draft} onChange={patchDraft} />
                  </div>
                </div>
                <div className="mt-5 flex items-center justify-between gap-2 border-t border-qa-border pt-4">
                  <span className="text-xs text-qa-text-sub">담당 팀은 등록 후 사람이 직접 배정해요.</span>
                  {draftButtons}
                </div>
              </section>
            ) : selected?.manual_qa ? (
              <ManualQaDetail issue={selected} projectId={projectId} members={members} onClose={() => select(null)} onAssigned={onAssigned}
                onGoToPath={(p) => { setPath(p); setSurface({ kind: 'live', attachment: null }) }} currentPath={path} />
            ) : selected ? (
              <AssignPanel issue={selected} projectId={projectId} members={members} onClose={() => select(null)} onAssigned={onAssigned} />
            ) : (
              <aside className="flex min-h-[420px] flex-col items-center justify-center rounded-xl border border-qa-border bg-white px-6 py-12 text-center">
                <Icon name="list-checks" className="h-6 w-6 text-gray-900" />
                <div className="mt-3 text-sm font-bold text-black">확인할 QA 항목을 선택하세요</div>
                <p className="mt-2 max-w-[420px] text-xs leading-4 text-qa-text-sub">
                  자동 QA 항목은 Figma 기준값과 구현값의 차이를, 수동 QA 항목은 등록 당시 캡처와 위치를 보여 줘요.
                  배포 화면에서 문제를 발견했다면 [QA 등록]을 누르고 위치를 클릭해 바로 등록할 수 있어요.
                </p>
                <div className="mt-4 flex gap-6 text-center text-xs text-qa-text-sub">
                  <span><b className="block text-base text-black">{autoCount}</b>자동 QA</span>
                  <span><b className="block text-base text-black">{manualCount}</b>수동 QA</span>
                  <span><b className="block text-base text-black">{issues.filter((i) => i.assigned_team === null && i.status !== 'resolved').length}</b>담당 팀 미배정</span>
                </div>
              </aside>
            )}
          </div>
        </>
      )}

      {confirmDiscard && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" role="dialog" aria-modal="true" aria-labelledby="discard-title">
          <div className="w-[400px] max-w-full rounded-2xl bg-white p-6">
            <h2 id="discard-title" className="text-lg font-bold text-black">작성 중인 QA를 닫을까요?</h2>
            <p className="mt-1 text-sm text-qa-text-sub">입력한 제목·설명과 위치가 저장되지 않고 사라져요.</p>
            <div className="mt-5 grid grid-cols-2 gap-2">
              <button type="button" className={OUTLINE_BTN} autoFocus onClick={() => setConfirmDiscard(false)}>계속 작성</button>
              <button type="button" className="inline-flex h-10 items-center justify-center rounded-lg bg-qa-warning px-4 text-sm font-medium text-white hover:opacity-90"
                onClick={() => { setConfirmDiscard(false); setDraft(null) }}>버리고 닫기</button>
            </div>
          </div>
        </div>
      )}

      {editingUrl && target && (
        <DeployUrlDialog projectId={projectId} target={target} onClose={() => setEditingUrl(false)}
          onSaved={(t) => { setTarget(t); setEditingUrl(false); setSurface({ kind: 'live', attachment: null }); setReloadKey((k) => k + 1) }} />
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

/** 찍은 위치 근처에 띄우는 작성 창(M-03·M-04). 캔버스 밖으로 나가지 않게 좌우·위아래를 바꾼다 */
function Popover({ draft, children }: { draft: Draft; children: React.ReactNode }) {
  const rx = draft.x / draft.viewport.width
  const ry = draft.y / draft.viewport.height
  return (
    <div className="absolute z-30 w-[360px] max-w-[calc(100%-16px)] rounded-xl border border-qa-border bg-white p-4 shadow-xl"
      role="dialog" aria-label="새 QA 항목 작성"
      style={{
        left: rx > 0.6 ? undefined : `calc(${rx * 100}% + 16px)`,
        right: rx > 0.6 ? `calc(${(1 - rx) * 100}% + 16px)` : undefined,
        top: ry > 0.5 ? undefined : `calc(${ry * 100}% + 8px)`,
        bottom: ry > 0.5 ? `calc(${(1 - ry) * 100}% + 8px)` : undefined,
      }}>
      {children}
    </div>
  )
}

/** 배포 주소 입력·변경 — 소유자만(AC29). 연결 관리 화면(LNK)이 아직 없어 여기서 바꾼다 */
function DeployUrlDialog({ projectId, target, onClose, onSaved }: {
  projectId: string
  target: DeployTarget
  onClose: () => void
  onSaved: (t: DeployTarget) => void
}) {
  const [value, setValue] = useState(target.manual_url ?? '')
  const [saving, setSaving] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const save = async (url: string | null) => {
    setSaving(true)
    setErr(null)
    try {
      onSaved(await updateDeployUrl(projectId, url))
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '저장하지 못했어요.')
    } finally {
      setSaving(false)
    }
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" role="dialog" aria-modal="true" aria-labelledby="deploy-url-title">
      <form className="w-[520px] max-w-full rounded-2xl bg-white p-6" onSubmit={(e) => { e.preventDefault(); void save(value.trim() || null) }}>
        <h2 id="deploy-url-title" className="text-lg font-bold text-black">배포 주소</h2>
        <p className="mt-1 text-sm text-qa-text-sub">
          실시간 QA 워크스페이스에 띄울 배포 화면 주소예요. 비워 두면 연결한 GitHub 저장소의 최근 성공 배포 주소를 써요.
        </p>
        <label className="mt-4 block">
          <span className="text-xs font-medium text-black">배포 URL</span>
          <input value={value} onChange={(e) => setValue(e.target.value)} placeholder="https://my-app.vercel.app" autoFocus disabled={saving}
            className="mt-1 h-10 w-full rounded-lg border border-qa-border px-3 text-sm outline-none focus:border-qa-primary" />
        </label>
        {target.source === 'github_deployment' && !target.manual_url && (
          <p className="mt-2 text-xs text-qa-text-sub">지금은 GitHub 배포 주소({target.url})를 쓰고 있어요.</p>
        )}
        {err && <p role="alert" className="mt-2 text-xs text-qa-warning">{err}</p>}
        <div className="mt-5 flex items-center justify-between gap-2">
          {target.manual_url
            ? <button type="button" onClick={() => void save(null)} disabled={saving} className="text-xs font-medium text-qa-warning hover:underline">입력한 주소 지우기</button>
            : <span />}
          <div className="flex gap-2">
            <button type="button" onClick={onClose} disabled={saving} className={OUTLINE_BTN}>취소</button>
            <button type="submit" disabled={saving || !value.trim()} className={PRIMARY_BTN}>{saving ? '저장 중…' : '저장'}</button>
          </div>
        </div>
      </form>
    </div>
  )
}

