import { useRef } from 'react'
import { captureImageUrl, PLATFORM_LABELS, SEVERITY_LABELS, type AppPlatform, type Severity } from '../../lib/api'
import { Icon } from '../common/Icon'
import type { Draft } from './draft'

const SEVERITIES: Severity[] = ['critical', 'high', 'medium', 'low']

const INPUT = 'w-full rounded-lg border bg-white px-3 py-2 text-sm text-black outline-none focus:border-qa-primary'

/** 제목·설명(필수) — Popover와 상세 입력 패널이 같은 값을 쓴다(시안 M-04·M-04D) */
export function RequiredFields({ draft, onChange, autoFocus }: {
  draft: Draft
  onChange: (patch: Partial<Draft>) => void
  autoFocus?: boolean
}) {
  return (
    <>
      <label className="block">
        <span className="text-xs font-medium text-black">QA 제목 <span className="text-qa-warning">*</span></span>
        <input id="mq-title" value={draft.title} maxLength={100} autoFocus={autoFocus} disabled={draft.saving}
          onChange={(e) => onChange({ title: e.target.value, errors: { ...draft.errors, title: undefined } })}
          aria-invalid={!!draft.errors.title} aria-describedby={draft.errors.title ? 'mq-title-error' : undefined}
          placeholder="예: 로그인 버튼 클릭 시 화면이 이동하지 않음"
          className={`${INPUT} mt-1 ${draft.errors.title ? 'border-qa-warning' : 'border-qa-border'}`} />
        {draft.errors.title && <span id="mq-title-error" className="mt-1 block text-xs text-qa-warning">{draft.errors.title}</span>}
      </label>
      <label className="mt-3 block">
        <span className="text-xs font-medium text-black">문제 설명 <span className="text-qa-warning">*</span></span>
        <textarea id="mq-description" value={draft.description} maxLength={2000} rows={3} disabled={draft.saving}
          onChange={(e) => onChange({ description: e.target.value, errors: { ...draft.errors, description: undefined } })}
          aria-invalid={!!draft.errors.description} aria-describedby={draft.errors.description ? 'mq-description-error' : undefined}
          placeholder="무엇이 어떻게 다른지 적어 주세요"
          className={`${INPUT} mt-1 resize-none ${draft.errors.description ? 'border-qa-warning' : 'border-qa-border'}`} />
        {draft.errors.description && <span id="mq-description-error" className="mt-1 block text-xs text-qa-warning">{draft.errors.description}</span>}
      </label>
    </>
  )
}

/** 캡처 연결 상태 — 캡처는 입력을 막지 않고 뒤에서 진행된다(M-03). 실패를 숨기지 않는다(M-09A, N19) */
export function CaptureStatusBox({ draft, projectId, onRetry, onAttach }: {
  draft: Draft
  projectId: string
  onRetry: () => void
  onAttach: (file: File) => void
}) {
  const fileRef = useRef<HTMLInputElement>(null)
  const c = draft.capture
  const picker = (
    <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/webp" className="hidden"
      onChange={(e) => { const f = e.target.files?.[0]; if (f) onAttach(f); e.target.value = '' }} />
  )
  if (c.state === 'loading') {
    return (
      <div className="flex items-center gap-2 rounded-lg bg-qa-surface px-3 py-2 text-xs text-qa-text-sub" role="status">
        <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-qa-primary border-t-transparent" />
        배포 화면을 캡처하는 중이에요 · 입력은 계속할 수 있어요
      </div>
    )
  }
  if (c.state === 'ok') {
    const isUpload = c.capture.kind === 'upload'
    return (
      <div className="flex items-center gap-3 rounded-lg bg-qa-surface px-3 py-2">
        <span className="relative h-10 w-16 flex-none overflow-hidden rounded border border-qa-border bg-white">
          <img src={captureImageUrl(projectId, c.capture.id)} alt="" className="h-full w-full object-cover" />
        </span>
        <span className="min-w-0 text-xs">
          <span className="block font-medium text-black">{isUpload ? '첨부 이미지 연결됨' : `위치 캡처 연결됨 · ${new Date(c.capture.created_at).toLocaleTimeString('ko-KR')}`}</span>
          <span className="block text-qa-text-sub">{c.capture.width}×{c.capture.height} · {isUpload ? '사용자 첨부' : '서버 캡처'}</span>
        </span>
      </div>
    )
  }
  if (c.state === 'failed') {
    return (
      <div className="rounded-lg bg-qa-warning/10 px-3 py-2" role="alert">
        <div className="flex items-center gap-1.5 text-xs font-medium text-[#c8161a]"><Icon name="alert-triangle" className="h-3.5 w-3.5" />이 화면은 자동으로 캡처하지 못했어요</div>
        <p className="mt-0.5 text-xs text-qa-text-sub">{c.message} 이미지를 첨부하거나 위치 정보만으로 등록할 수 있어요.</p>
        <div className="mt-2 flex gap-2">
          <button type="button" onClick={() => fileRef.current?.click()} disabled={draft.saving}
            className="inline-flex h-8 items-center gap-1 rounded-lg border border-qa-border bg-white px-2.5 text-xs font-medium text-black hover:bg-qa-surface">
            <Icon name="image" className="h-3.5 w-3.5" />이미지 첨부
          </button>
          {draft.basis === 'live_overlay' && (
            <button type="button" onClick={onRetry} disabled={draft.saving}
              className="inline-flex h-8 items-center gap-1 rounded-lg px-2.5 text-xs font-medium text-qa-primary-hover hover:bg-qa-primary/5">
              <Icon name="refresh" className="h-3.5 w-3.5" />캡처 재시도
            </button>
          )}
        </div>
        {picker}
      </div>
    )
  }
  return (
    <div className="flex items-center justify-between gap-2 rounded-lg bg-qa-surface px-3 py-2 text-xs text-qa-text-sub">
      캡처 없음
      <button type="button" onClick={() => fileRef.current?.click()} className="inline-flex items-center gap-1 font-medium text-qa-primary-hover">
        <Icon name="image" className="h-3.5 w-3.5" />이미지 첨부
      </button>
      {picker}
    </div>
  )
}

/** 상세 입력(M-04D) — 심각도·페이지 경로·기대/실제 결과는 선택. 입력하지 않으면 비워 둔다 */
export function OptionalFields({ draft, onChange }: { draft: Draft; onChange: (patch: Partial<Draft>) => void }) {
  return (
    <>
      <fieldset className="mt-3">
        <legend className="text-xs font-medium text-black">심각도 <span className="font-normal text-qa-text-sub">선택</span></legend>
        <div className="mt-1 grid grid-cols-4 gap-1.5">
          {SEVERITIES.map((s) => (
            <button key={s} type="button" aria-pressed={draft.severity === s} disabled={draft.saving}
              onClick={() => onChange({ severity: draft.severity === s ? null : s })}
              className={`h-8 rounded-lg border text-xs ${draft.severity === s ? 'border-qa-primary bg-qa-primary/10 font-medium text-qa-primary-hover' : 'border-qa-border bg-white text-black hover:bg-qa-surface'}`}>
              {SEVERITY_LABELS[s]}
            </button>
          ))}
        </div>
      </fieldset>
      <label className="mt-3 block">
        <span className="text-xs font-medium text-black">기대 결과 <span className="font-normal text-qa-text-sub">선택</span></span>
        <textarea value={draft.expected} maxLength={1000} rows={2} disabled={draft.saving} onChange={(e) => onChange({ expected: e.target.value })}
          className={`${INPUT} mt-1 resize-none border-qa-border`} />
      </label>
      <label className="mt-3 block">
        <span className="text-xs font-medium text-black">실제 결과 <span className="font-normal text-qa-text-sub">선택</span></span>
        <textarea value={draft.actual} maxLength={1000} rows={2} disabled={draft.saving} onChange={(e) => onChange({ actual: e.target.value })}
          className={`${INPUT} mt-1 resize-none border-qa-border`} />
      </label>
      <PlatformField platform={draft.platform} only={draft.platformOnly} disabled={draft.saving}
        onChange={(platform, platformOnly) => onChange({ platform, platformOnly })} />
    </>
  )
}

/** 기준 Figma 프레임(AC33) — 찍은 경로에 연결된 프레임을 위에 두고, 사람이 확인해 고른다. 고르지 않으면 비워 둔다 */
export function FrameField({ draft, frames, suggested, onChange }: {
  draft: Draft
  frames: { frame_id: string; frame_name: string }[]
  suggested: { frame_id: string; frame_name: string }[]
  onChange: (patch: Partial<Draft>) => void
}) {
  const suggestedIds = new Set(suggested.map((f) => f.frame_id))
  const others = frames.filter((f) => !suggestedIds.has(f.frame_id))
  return (
    <label className="mt-3 block">
      <span className="flex items-center gap-1 text-xs font-medium text-black">
        <Icon name="figma" className="h-3.5 w-3.5" />기준 Figma 프레임 <span className="font-normal text-qa-text-sub">선택</span>
      </span>
      {frames.length === 0 ? (
        <span className="mt-1 block text-xs text-qa-text-sub">비교 분석 매핑이 없어 고를 프레임이 없어요.</span>
      ) : (
        <>
          <select value={draft.frameId ?? ''} disabled={draft.saving} onChange={(e) => onChange({ frameId: e.target.value || null })}
            className={`${INPUT} mt-1 border-qa-border`}>
            <option value="">선택 안 함</option>
            {suggested.length > 0 && (
              <optgroup label={`이 경로(${draft.pagePath})에 연결된 프레임`}>
                {suggested.map((f) => <option key={f.frame_id} value={f.frame_id}>{f.frame_name}</option>)}
              </optgroup>
            )}
            <optgroup label={suggested.length > 0 ? '다른 프레임' : '매핑된 프레임'}>
              {others.map((f) => <option key={f.frame_id} value={f.frame_id}>{f.frame_name}</option>)}
            </optgroup>
          </select>
          <span className="mt-1 block text-xs text-qa-text-sub">
            {suggested.length === 0 ? '이 경로에 연결된 프레임이 없어요 · 매핑 화면에서 경로를 연결할 수 있어요'
              : suggested.length > 1 ? `이 경로에 연결된 프레임이 ${suggested.length}개예요 · 해당하는 상태를 골라 주세요`
                : '이 경로에 연결된 프레임을 골라 뒀어요'}
          </span>
        </>
      )}
    </label>
  )
}

const PLATFORMS: AppPlatform[] = ['web', 'aos', 'ios']

/** 발생 환경(앱 지원, 2026-11-11) — 웹이 아니면 "이 플랫폼에서만"을 고를 수 있다. 고르지 않으면 공통 문제로 등록한다 */
export function PlatformField({ platform, only, disabled, onChange }: {
  platform: AppPlatform
  only: boolean
  disabled?: boolean
  onChange: (platform: AppPlatform, only: boolean) => void
}) {
  return (
    <fieldset className="mt-3">
      <legend className="text-xs font-medium text-black">발생 환경</legend>
      <div className="mt-1 grid grid-cols-3 gap-1.5">
        {PLATFORMS.map((p) => (
          <button key={p} type="button" aria-pressed={platform === p} disabled={disabled}
            onClick={() => onChange(p, p === 'web' ? false : only)}
            className={`h-8 rounded-lg border text-xs ${platform === p ? 'border-qa-primary bg-qa-primary/10 font-medium text-qa-primary-hover' : 'border-qa-border bg-white text-black hover:bg-qa-surface'}`}>
            {PLATFORM_LABELS[p]}
          </button>
        ))}
      </div>
      {platform !== 'web' && (
        <label className="mt-2 flex items-center gap-2 text-xs text-black">
          <input type="checkbox" checked={only} disabled={disabled} onChange={(e) => onChange(platform, e.target.checked)} />
          {PLATFORM_LABELS[platform]}에서만 생기는 문제예요
        </label>
      )}
    </fieldset>
  )
}

/** 서버로 보낼 발생 환경·적용 범위 — 웹·공통이면 보내지 않는다(서버 기본값) */
export function platformInput(platform: AppPlatform, only: boolean): { platform?: AppPlatform; platform_scope?: 'aos' | 'ios' } {
  if (platform === 'web') return {}
  return only ? { platform, platform_scope: platform } : { platform }
}
