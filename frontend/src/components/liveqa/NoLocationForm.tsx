import { useMemo, useRef, useState } from 'react'
import {
  ApiError, captureImageUrl, createManualQa, linksForPath, SEVERITY_LABELS, uploadCapture,
  type QaCapture, type QaIssue, type ScreenFrameLink, type Severity,
} from '../../lib/api'
import { Icon } from '../common/Icon'
import { newRequestId } from './draft'
import { PlatformField, platformInput } from './DraftFields'
import type { AppPlatform } from '../../lib/api'

const INPUT = 'w-full rounded-lg border border-qa-border bg-white px-3 py-2 text-sm text-black outline-none focus:border-qa-primary'
const SEVERITIES: Severity[] = ['critical', 'high', 'medium', 'low']

/**
 * 위치 없이 수동 QA 등록(SPEC AC31 location_basis none, 2026-11-10) — 배포 화면이 없거나 위치를 찍지 않을 때.
 * 좌표가 없으니 화면(경로나 기준 Figma 프레임 중 하나 이상)과 재현 절차를 사람이 적는다. 스크린샷은 선택(증거 이미지로만 붙는다)
 */
export function NoLocationForm({ projectId, frames, links, nextPin, onCreated, onClose }: {
  projectId: string
  frames: { frame_id: string; frame_name: string }[]
  links: ScreenFrameLink[]
  nextPin: number
  onCreated: (issue: QaIssue) => void
  onClose: () => void
}) {
  const requestId = useRef(newRequestId())
  const fileRef = useRef<HTMLInputElement>(null)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [path, setPath] = useState('')
  const [frameId, setFrameId] = useState('')
  const [frameTouched, setFrameTouched] = useState(false)
  const [steps, setSteps] = useState('')
  const [severity, setSeverity] = useState<Severity | null>(null)
  // 발생 환경(앱 지원, 2026-11-11) — 앱이면 화면을 경로 대신 이름("홈 화면")으로 적을 수 있다
  const [platform, setPlatform] = useState<AppPlatform>('web')
  const [platformOnly, setPlatformOnly] = useState(false)
  const isApp = platform !== 'web'
  const [expected, setExpected] = useState('')
  const [actual, setActual] = useState('')
  const [capture, setCapture] = useState<QaCapture | null>(null)
  const [uploading, setUploading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saveError, setSaveError] = useState<string | null>(null)

  // 경로에 연결된 프레임(AC33)이 하나뿐이면 사람이 고르기 전까지 미리 골라 둔다
  const suggested = useMemo(() => (!isApp && path.trim().startsWith('/')
    ? linksForPath(links, path.trim()).filter((l) => frames.some((f) => f.frame_id === l.frame_id)) : []), [links, frames, path, isApp])
  const effectiveFrame = frameTouched ? frameId : (suggested.length === 1 ? suggested[0].frame_id : frameId)

  const attach = async (file: File) => {
    setUploading(true)
    setErrors((e) => ({ ...e, capture: '' }))
    try {
      setCapture(await uploadCapture(projectId, file))
    } catch (e) {
      setErrors((x) => ({ ...x, capture: e instanceof ApiError ? e.message : '이미지를 올리지 못했어요.' }))
    } finally {
      setUploading(false)
    }
  }

  const submit = async () => {
    const stepList = steps.split('\n').map((s) => s.trim()).filter(Boolean)
    const next: Record<string, string> = {}
    if (!title.trim()) next.title = 'QA 제목을 입력해 주세요.'
    if (!description.trim()) next.description = '문제 설명을 입력해 주세요.'
    if (!isApp && path.trim() && !path.trim().startsWith('/')) next.screen = '경로는 /로 시작해야 해요.'
    else if (!path.trim() && !effectiveFrame) next.screen = isApp ? '화면 이름이나 기준 Figma 프레임 중 하나는 알려 주세요.' : '화면 경로나 기준 Figma 프레임 중 하나는 알려 주세요.'
    if (stepList.length === 0) next.steps = '재현 절차를 한 줄 이상 적어 주세요.'
    setErrors(next)
    if (Object.keys(next).length > 0) return
    setSaving(true)
    setSaveError(null)
    try {
      const created = await createManualQa(projectId, {
        client_request_id: requestId.current,
        title: title.trim(),
        description: description.trim(),
        location_basis: 'none',
        repro_steps: stepList,
        ...(path.trim() ? { page_path: path.trim() } : {}),
        ...(effectiveFrame ? { figma_frame_id: effectiveFrame } : {}),
        ...(capture ? { capture_id: capture.id } : {}),
        ...(severity ? { severity } : {}),
        ...(expected.trim() ? { expected_result: expected.trim() } : {}),
        ...(actual.trim() ? { actual_result: actual.trim() } : {}),
        ...platformInput(platform, platformOnly),
      })
      onCreated(created)
    } catch (e) {
      // 같은 요청 id로 다시 보내면 두 번 등록되지 않는다
      setSaveError(e instanceof ApiError ? e.message : 'QA를 저장하지 못했어요. 입력 내용은 그대로 있어요.')
    } finally {
      setSaving(false)
    }
  }

  const err = (k: string) => errors[k] ? <span role="alert" className="mt-1 block text-xs text-qa-warning">{errors[k]}</span> : null
  return (
    <section className="rounded-xl border border-[#7c3aed]/50 bg-white p-5" aria-label="위치 없이 수동 QA 등록">
      <div className="flex items-center justify-between gap-2">
        <h2 className="flex items-center gap-2 text-base font-bold text-black">
          <span className="flex h-5 w-5 items-center justify-center rounded-full border-2 border-dashed border-[#7c3aed] text-[10px] text-[#7c3aed]">{nextPin}</span>
          수동 QA 등록 <span className="rounded bg-[#7c3aed]/10 px-1.5 text-xs font-medium text-[#7c3aed]">위치 없이</span>
        </h2>
        <button type="button" onClick={onClose} aria-label="작성 닫기" className="rounded p-1 text-qa-text-sub hover:bg-qa-surface"><Icon name="x" className="h-4 w-4" /></button>
      </div>
      <p className="mt-2 text-xs text-qa-text-sub">배포 화면 없이 등록해요. 위치를 찍지 않으니 어느 화면인지와 재현 절차를 직접 적어 주세요. 앱 화면이면 발생 환경을 Android·iOS로 고르고 스크린샷을 첨부해 주세요.</p>
      {saveError && <p role="alert" className="mt-3 rounded-lg bg-qa-warning/10 px-3 py-2 text-xs text-black">{saveError}</p>}

      <div className="mt-4 grid grid-cols-1 gap-5 2xl:grid-cols-2">
        <div>
          <label className="block">
            <span className="text-xs font-medium text-black">QA 제목 <span className="text-qa-warning">*</span></span>
            <input value={title} maxLength={100} autoFocus disabled={saving} onChange={(e) => setTitle(e.target.value)}
              placeholder="예: 카드 크기가 시안보다 작음" className={`${INPUT} mt-1`} />
            {err('title')}
          </label>
          <label className="mt-3 block">
            <span className="text-xs font-medium text-black">문제 설명 <span className="text-qa-warning">*</span></span>
            <textarea value={description} maxLength={2000} rows={3} disabled={saving} onChange={(e) => setDescription(e.target.value)}
              placeholder="무엇이 어떻게 다른지 적어 주세요" className={`${INPUT} mt-1 resize-none`} />
            {err('description')}
          </label>
          <PlatformField platform={platform} only={platformOnly} disabled={saving}
            onChange={(p, only) => { setPlatform(p); setPlatformOnly(only) }} />
          <fieldset className="mt-3">
            <legend className="text-xs font-medium text-black">화면 <span className="text-qa-warning">*</span> <span className="font-normal text-qa-text-sub">{isApp ? '화면 이름이나' : '경로나'} 기준 프레임 중 하나 이상</span></legend>
            <input value={path} maxLength={500} disabled={saving} onChange={(e) => setPath(e.target.value)} aria-label={isApp ? '앱 화면 이름' : '화면 경로'}
              placeholder={isApp ? '예: 홈 화면, 로그인 화면' : '/projects 또는 /app/projects/26'} className={`${INPUT} mt-1 ${isApp ? '' : 'font-mono'}`} />
            <select value={effectiveFrame} disabled={saving || frames.length === 0} aria-label="기준 Figma 프레임"
              onChange={(e) => { setFrameTouched(true); setFrameId(e.target.value) }} className={`${INPUT} mt-2`}>
              <option value="">{frames.length === 0 ? '비교 분석 매핑이 없어 고를 프레임이 없어요' : '기준 Figma 프레임 선택 안 함'}</option>
              {frames.map((f) => <option key={f.frame_id} value={f.frame_id}>{f.frame_name}</option>)}
            </select>
            {err('screen')}
          </fieldset>
          <label className="mt-3 block">
            <span className="text-xs font-medium text-black">재현 절차 <span className="text-qa-warning">*</span> <span className="font-normal text-qa-text-sub">한 줄에 한 단계</span></span>
            <textarea value={steps} rows={4} disabled={saving} onChange={(e) => setSteps(e.target.value)}
              placeholder={'프로젝트 목록 화면을 연다\n첫 번째 카드를 확인한다'} className={`${INPUT} mt-1 resize-none`} />
            {err('steps')}
          </label>
        </div>

        <div>
          <div className="text-xs font-medium text-black">스크린샷 <span className="font-normal text-qa-text-sub">선택</span></div>
          {capture ? (
            <div className="mt-1 flex items-center gap-3 rounded-lg bg-qa-surface px-3 py-2">
              <img src={captureImageUrl(projectId, capture.id)} alt="첨부한 스크린샷" className="h-10 w-16 rounded border border-qa-border object-cover" />
              <span className="min-w-0 flex-1 text-xs text-black">첨부됨 · {capture.width}×{capture.height}</span>
              <button type="button" onClick={() => setCapture(null)} disabled={saving} className="text-xs text-qa-text-sub hover:underline">빼기</button>
            </div>
          ) : (
            <button type="button" onClick={() => fileRef.current?.click()} disabled={saving || uploading}
              className="mt-1 inline-flex h-9 items-center gap-1 rounded-lg border border-qa-border bg-white px-3 text-xs font-medium text-black hover:bg-qa-surface">
              <Icon name="image" className="h-3.5 w-3.5" />{uploading ? '올리는 중…' : '이미지 첨부'}
            </button>
          )}
          <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/webp" className="hidden"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void attach(f); e.target.value = '' }} />
          {err('capture')}

          <fieldset className="mt-3">
            <legend className="text-xs font-medium text-black">심각도 <span className="font-normal text-qa-text-sub">선택</span></legend>
            <div className="mt-1 grid grid-cols-4 gap-1.5">
              {SEVERITIES.map((s) => (
                <button key={s} type="button" aria-pressed={severity === s} disabled={saving} onClick={() => setSeverity(severity === s ? null : s)}
                  className={`h-8 rounded-lg border text-xs ${severity === s ? 'border-qa-primary bg-qa-primary/10 font-medium text-qa-primary-hover' : 'border-qa-border bg-white text-black hover:bg-qa-surface'}`}>
                  {SEVERITY_LABELS[s]}
                </button>
              ))}
            </div>
          </fieldset>
          <label className="mt-3 block">
            <span className="text-xs font-medium text-black">기대 결과 <span className="font-normal text-qa-text-sub">선택</span></span>
            <textarea value={expected} maxLength={1000} rows={2} disabled={saving} onChange={(e) => setExpected(e.target.value)} className={`${INPUT} mt-1 resize-none`} />
          </label>
          <label className="mt-3 block">
            <span className="text-xs font-medium text-black">실제 결과 <span className="font-normal text-qa-text-sub">선택</span></span>
            <textarea value={actual} maxLength={1000} rows={2} disabled={saving} onChange={(e) => setActual(e.target.value)} className={`${INPUT} mt-1 resize-none`} />
          </label>
        </div>
      </div>

      <div className="mt-5 flex items-center justify-between gap-2 border-t border-qa-border pt-4">
        <span className="text-xs text-qa-text-sub">담당 팀은 등록 후 사람이 직접 배정해요.</span>
        <div className="flex gap-2">
          <button type="button" onClick={onClose} disabled={saving} className="h-9 rounded-lg border border-qa-border bg-white px-4 text-sm text-black hover:bg-qa-surface">취소</button>
          <button type="button" onClick={() => void submit()} disabled={saving || uploading} aria-busy={saving}
            className="h-9 rounded-lg bg-qa-primary px-4 text-sm font-medium text-white hover:bg-qa-primary-hover disabled:opacity-60">
            {saving ? '등록 중…' : 'QA 등록하기'}
          </button>
        </div>
      </div>
    </section>
  )
}
