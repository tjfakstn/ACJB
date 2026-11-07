import { ROLE_LABELS, type MemberRole } from '../../lib/api'

// 프로젝트별 역할 선택(designer/frontend/backend/planner — SPEC AC16·AC18).
// 시안은 초대하는 사람이 역할을 정하지만, 지금 스펙은 본인이 고른다(만들 때·수락할 때). 그래서 본인 화면에만 쓴다.
const ROLE_HINTS: Record<MemberRole, string> = {
  designer: 'Figma 시안과 디자인 시스템 담당',
  frontend: '화면 구현과 UI 이슈 수정 담당',
  backend: 'API·데이터 구현과 서버 이슈 수정 담당',
  planner: '기획·요구사항과 QA 진행 관리',
}

interface RoleSelectProps {
  value: MemberRole | null
  onChange: (role: MemberRole) => void
  invalid?: boolean
}

export function RoleSelect({ value, onChange, invalid }: RoleSelectProps) {
  return (
    <div role="radiogroup" aria-label="내 역할" className="grid grid-cols-2 gap-2">
      {(Object.keys(ROLE_LABELS) as MemberRole[]).map((role) => {
        const selected = value === role
        return (
          <button
            key={role}
            type="button"
            role="radio"
            aria-checked={selected}
            aria-label={ROLE_LABELS[role]}
            onClick={() => onChange(role)}
            className={`rounded-lg border px-3 py-2.5 text-left transition-colors ${
              selected
                ? 'border-qa-primary bg-[#eef4ff]'
                : invalid
                  ? 'border-red-300 bg-white hover:bg-gray-50'
                  : 'border-qa-border bg-white hover:bg-gray-50'
            }`}
          >
            <div className={`text-sm font-semibold ${selected ? 'text-qa-primary' : 'text-gray-900'}`}>{ROLE_LABELS[role]}</div>
            <div className="mt-0.5 text-[11px] leading-4 text-qa-sub">{ROLE_HINTS[role]}</div>
          </button>
        )
      })}
    </div>
  )
}
