import type { ReactNode } from 'react'
import { Icon } from '../common/Icon'

// 새 프로젝트 마법사(시안 PRJ-02~04 → INV-01~05 → CON-01~05)가 함께 쓰는 틀 — 창·단계 표시·닫기 버튼·버튼 모양.

const STEPS = ['프로젝트 정보', '멤버 초대', 'Figma·Git 연결']

/** 시안 Dialog Stepper — 지난 단계는 체크, 지금 단계는 파란 점, 남은 단계는 회색 원 */
export function Stepper({ current }: { current: number }) {
  return (
    <ol className="flex items-center gap-2" aria-label="진행 단계">
      {STEPS.map((label, i) => (
        <li key={label} className="flex items-center gap-2" aria-current={i === current ? 'step' : undefined}>
          {i > 0 && <span className="h-px w-7 bg-qa-border" aria-hidden="true" />}
          {i < current ? (
            <span className="flex h-5 w-5 items-center justify-center rounded-full bg-qa-success text-white"><Icon name="check" className="h-3 w-3" /></span>
          ) : i === current ? (
            <span className="flex h-5 w-5 items-center justify-center rounded-full border border-qa-primary"><span className="h-1.5 w-1.5 rounded-full bg-qa-primary" /></span>
          ) : (
            <span className="h-5 w-5 rounded-full border border-qa-sub" />
          )}
          <span className={`text-xs ${i <= current ? 'font-medium text-black' : 'text-qa-text-sub'}`}>{label}</span>
        </li>
      ))}
    </ol>
  )
}

export function NextStep({ n, title, body, badge, active }: { n: number; title: string; body: string; badge?: string; active?: boolean }) {
  return (
    <div className="flex items-center gap-3 rounded-lg border border-qa-border px-3 py-3">
      <span className={`flex h-6 w-6 flex-none items-center justify-center rounded-full text-xs font-medium ${
        active ? 'bg-qa-primary-hover text-white' : 'border border-qa-border bg-qa-surface text-qa-text-sub'}`}>{n}</span>
      <div className="min-w-0 flex-1">
        <div className="text-sm font-medium text-black">{title}</div>
        <div className="text-xs text-qa-text-sub">{body}</div>
      </div>
      {badge && (
        <span className="inline-flex flex-none items-center gap-1.5 rounded-full bg-qa-primary/10 px-2.5 py-1 text-xs font-medium text-black">
          <span className="h-1.5 w-1.5 rounded-full bg-qa-primary" />{badge}
        </span>
      )}
    </div>
  )
}

export function Kv({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-4">
      <dt className="w-24 flex-none text-qa-text-sub">{label}</dt>
      <dd className="min-w-0 flex-1 break-words font-medium text-black">{value}</dd>
    </div>
  )
}

export function CloseButton({ onClick, disabled }: { onClick: () => void; disabled?: boolean }) {
  return (
    <button type="button" onClick={onClick} disabled={disabled} aria-label="닫기"
      className="flex h-8 w-8 flex-none items-center justify-center rounded-lg text-black hover:bg-gray-100 disabled:text-qa-sub disabled:hover:bg-transparent">
      <Icon name="x" className="h-5 w-5" />
    </button>
  )
}

// 시안 Dialog 너비 — 확인 창(GST-02C) 480, 기본 560, 비교 분석 640, 초대 입력·확인(INV-01~04) 760, 초대 완료(INV-05) 600
const WIDTHS = { 480: 'max-w-[480px]', 560: 'max-w-[560px]', 600: 'max-w-[600px]', 640: 'max-w-[640px]', 760: 'max-w-[760px]' } as const
export type OverlayWidth = keyof typeof WIDTHS

/** 화면 전체를 덮는 반투명 배경 + 가운데 창. onClose가 없으면(처리 중) 배경·Esc로 닫히지 않는다 */
export function Overlay({ children, onClose, width = 560 }: { children: ReactNode; onClose?: () => void; width?: OverlayWidth }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4"
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose?.() }}
      onKeyDown={(e) => { if (e.key === 'Escape') onClose?.() }}>
      <div role="dialog" aria-modal="true" aria-labelledby="create-project-title"
        className={`max-h-[calc(100vh-32px)] w-full ${WIDTHS[width]} overflow-y-auto rounded-2xl bg-white p-6 shadow-[0_20px_48px_rgba(15,23,41,0.18)]`}>
        {children}
      </div>
    </div>
  )
}
