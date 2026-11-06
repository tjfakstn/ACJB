// 프로젝트 목록 — 빈 상태(시안 U01-A1).
// 단계 안내는 지금 만들 수 있는 흐름(프로젝트 만들기 → 멤버 초대 → QA 이슈 관리)으로 바꿨다 — GitHub 연동은 v1 비포함.
interface EmptyStateProps {
  onCreate: () => void
}

const STEPS = [
  { n: 1, label: '프로젝트 만들기' },
  { n: 2, label: '멤버 초대' },
  { n: 3, label: 'QA 이슈 관리' },
]

export function EmptyState({ onCreate }: EmptyStateProps) {
  return (
    <section className="flex flex-col items-center rounded-2xl border-[1.5px] border-dashed border-[#c7cfdb] bg-white px-6 pb-12 pt-14">
      <div className="mb-[22px] flex h-16 w-16 items-center justify-center rounded-xl bg-[#f2f4f8] text-[#7a8294]">
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
          <path d="M12 11v4M10 13h4" />
        </svg>
      </div>
      <div className="text-lg font-bold text-gray-900">아직 참여 중인 프로젝트가 없어요</div>
      <p className="mt-2 max-w-[420px] text-center text-sm leading-relaxed text-gray-600">
        프로젝트를 만들고 팀원을 초대하면 QA 이슈를 함께 관리할 수 있어요. 초대를 받았다면 메일의 링크로 참여해 주세요.
      </p>
      <button
        type="button"
        onClick={onCreate}
        className="mt-[22px] inline-flex h-10 items-center gap-2 rounded-lg bg-qa-primary px-[18px] text-sm font-semibold text-white transition-colors hover:bg-qa-primary-hover"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
          <path d="M12 5v14M5 12h14" />
        </svg>
        새 프로젝트 만들기
      </button>

      <div className="mt-10 flex items-center">
        {STEPS.map((s, i) => (
          <div key={s.n} className="flex items-center">
            <div className="flex items-center gap-2">
              <span
                className={`flex h-6 w-6 items-center justify-center rounded-full border-[1.5px] text-xs font-bold ${
                  i === 0 ? 'border-qa-primary bg-qa-primary text-white' : 'border-qa-border bg-white text-qa-sub'
                }`}
              >
                {s.n}
              </span>
              <span className={`text-sm ${i === 0 ? 'font-semibold text-gray-900' : 'font-medium text-gray-600'}`}>{s.label}</span>
            </div>
            {i < STEPS.length - 1 && <span className="mx-4 h-px w-12 bg-[#d4dae3]" />}
          </div>
        ))}
      </div>
    </section>
  )
}
