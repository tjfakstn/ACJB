import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  captureImageUrl, formatDateTime, manualQaId, SEVERITY_LABELS, type ProjectMember, type QaIssue, type Team,
} from '../../lib/api'
import { Icon } from '../common/Icon'
import { TeamAssignSection } from '../qa/AssignPanel'
import { KeyValue, ProgressChip } from '../qa/QaParts'

// 수동 QA 상세(시안 M-06·M-06Z·M-09D·M-10A·B). 수동 QA는 PASS/FAIL 판정이 없다 — 수정 진행 상태만 보여 준다.
// 기대·실제 결과는 입력된 경우에만 보인다. 캡처가 없으면 없다고 표시한다(N19).
export function ManualQaDetail({ issue, projectId, members, onClose, onAssigned, onGoToPath, currentPath }: {
  issue: QaIssue
  projectId: string
  members: ProjectMember[]
  onClose: () => void
  onAssigned: (updated: QaIssue, previous: Team | null) => void
  onGoToPath: (path: string) => void
  currentPath: string
}) {
  const m = issue.manual_qa!
  const [zoom, setZoom] = useState(false)
  const reporter = members.find((mem) => mem.id === issue.reported_by)?.name ?? '알 수 없음'
  // 캡처 위 표시는 그 캡처가 핀과 같은 화면 기준일 때만 — 서버 캡처(같은 주소·크기)이거나 첨부 이미지 위에서 찍은 위치
  const markerOnCapture = m.capture_id && (m.capture_status === 'captured' || m.location_basis === 'capture_image')

  useEffect(() => {
    if (!zoom) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setZoom(false) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [zoom])

  const capture = m.capture_id ? (
    <div className="relative overflow-hidden rounded-lg border border-qa-border bg-qa-surface">
      <img src={captureImageUrl(projectId, m.capture_id)} alt={`${manualQaId(m.pin_number)} 등록 당시 화면`} className="block w-full" />
      {markerOnCapture && m.anchor && <Marker n={m.pin_number} x={m.anchor.x_ratio} y={m.anchor.y_ratio} />}
    </div>
  ) : null

  return (
    <aside className="rounded-xl border border-qa-primary/50 bg-white" aria-label="수동 QA 상세">
      <div className="border-b border-qa-border p-5">
        <div className="flex items-start justify-between gap-2">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="rounded bg-[#7c3aed]/10 px-1.5 text-xs font-medium leading-5 text-[#7c3aed]">수동 QA</span>
            <ProgressChip status={issue.status} />
            {m.severity && <span className="text-xs font-medium text-black">{SEVERITY_LABELS[m.severity]}</span>}
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xs text-qa-text-sub">등록 시각 {formatDateTime(issue.created_at)}</span>
            <button type="button" onClick={onClose} aria-label="패널 닫기" className="rounded p-1 text-qa-text-sub hover:bg-qa-surface">
              <Icon name="x" className="h-4 w-4" />
            </button>
          </div>
        </div>
        <h2 className="mt-2 flex items-center gap-2 text-lg font-bold leading-7 text-black">
          <span className="flex h-6 w-6 flex-none items-center justify-center rounded-full bg-[#7c3aed] text-xs text-white">{m.pin_number}</span>
          {manualQaId(m.pin_number)} · {m.title}
        </h2>
        <div className="mt-1 text-xs text-qa-text-sub">
          등록자 {reporter} · 핀 {m.pin_number} · {m.location_basis === 'capture_image' ? '첨부 이미지 기준' : m.location_basis === 'none' ? `위치 없이 등록${m.page_path ? ` · 페이지 ${m.page_path}` : ''}` : `페이지 ${m.page_path}`} · 수동 QA
        </div>
        {m.location_basis === 'live_overlay' && m.page_path && m.page_path !== currentPath && (
          <button type="button" onClick={() => onGoToPath(m.page_path!)} className="mt-2 inline-flex items-center gap-1 text-xs font-medium text-qa-primary-hover hover:underline">
            <Icon name="map-pin" className="h-3.5 w-3.5" />배포 화면을 {m.page_path}로 바꿔 핀 보기
          </button>
        )}
      </div>

      <div className="grid grid-cols-1 gap-4 border-b border-qa-border p-5 2xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div>
          <div className="flex items-center gap-1.5 text-xs font-medium text-black"><Icon name="camera" className="h-4 w-4" />등록 당시 캡처 · 증거 이미지</div>
          <div className="mt-2">
            {capture ?? (
              <div className="flex h-32 flex-col items-center justify-center rounded-lg border border-dashed border-qa-border bg-qa-surface text-xs text-qa-text-sub">
                <Icon name="image" className="h-5 w-5" />
                <span className="mt-1">{m.capture_status === 'failed' ? '캡처 없음 · 등록할 때 자동 캡처에 실패했어요' : '캡처 없음'}</span>
              </div>
            )}
          </div>
          <div className="mt-1.5 flex items-center justify-between text-xs text-qa-text-sub">
            <span>{m.capture_status === 'user_attached' ? '사용자 첨부' : m.capture_status === 'captured' ? `서버 캡처${m.viewport ? ` · ${m.viewport.width}×${m.viewport.height}` : ''}` : ''}</span>
            {m.capture_id && (
              <button type="button" onClick={() => setZoom(true)} className="inline-flex items-center gap-1 font-medium text-qa-primary-hover">
                <Icon name="maximize" className="h-3.5 w-3.5" />확대
              </button>
            )}
          </div>
        </div>
        <div className="min-w-0">
          <KeyValue rows={[
            { label: '문제 설명', value: <span className="whitespace-pre-wrap">{issue.description}</span> },
            ...(m.expected_result ? [{ label: '기대 결과', value: m.expected_result }] : []),
            ...(m.actual_result ? [{ label: '실제 결과', value: <span className="text-qa-warning">{m.actual_result}</span> }] : []),
            { label: '연결 위치', value: m.anchor && m.viewport ? `x ${m.anchor.x_px}, y ${m.anchor.y_px} · 기준 ${m.viewport.width}×${m.viewport.height}` : '위치 없이 등록' },
          ]} />
          <p className="mt-3 rounded-lg bg-qa-surface px-3 py-2 text-xs text-qa-text-sub">
            수동 등록 항목은 사람이 확인한 문제라 자동 검사 PASS/FAIL 판정이 없어요. 수정 진행 상태로 관리해요.
          </p>
          <Link to={`/app/projects/${projectId}/issues/${issue.id}`} className="mt-2 inline-flex items-center gap-0.5 text-xs font-medium text-qa-primary-hover hover:underline">
            QA 이슈 상세에서 전체 보기<Icon name="chevron-right" className="h-3.5 w-3.5" />
          </Link>
        </div>
      </div>

      <TeamAssignSection issue={issue} projectId={projectId} members={members} onAssigned={onAssigned} />

      {zoom && m.capture_id && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-6" onClick={() => setZoom(false)} role="dialog" aria-modal="true" aria-label="캡처 확대">
          <div className="max-h-full max-w-[1280px] overflow-auto rounded-xl bg-white p-3" onClick={(e) => e.stopPropagation()}>
            <div className="mb-2 flex items-center justify-between gap-3 text-sm">
              <span className="font-bold text-black">{manualQaId(m.pin_number)} · {m.title}</span>
              <button type="button" onClick={() => setZoom(false)} aria-label="닫기" className="rounded p-1 text-qa-text-sub hover:bg-qa-surface"><Icon name="x" className="h-4 w-4" /></button>
            </div>
            <div className="relative">
              <img src={captureImageUrl(projectId, m.capture_id)} alt="" className="block max-w-full" />
              {markerOnCapture && m.anchor && <Marker n={m.pin_number} x={m.anchor.x_ratio} y={m.anchor.y_ratio} />}
            </div>
          </div>
        </div>
      )}
    </aside>
  )
}

function Marker({ n, x, y }: { n: number; x: number; y: number }) {
  return (
    <span className="pointer-events-none absolute flex h-6 w-6 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border-2 border-white bg-[#7c3aed] text-[11px] font-bold text-white shadow ring-4 ring-[#7c3aed]/30"
      style={{ left: `${x * 100}%`, top: `${y * 100}%` }}>
      {n}
    </span>
  )
}
