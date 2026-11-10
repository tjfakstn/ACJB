import { Icon } from '../common/Icon'

// 프로젝트가 하나도 없을 때(시안 PRJ-01E Empty Area).
export function EmptyState({ onCreate }: { onCreate: () => void }) {
  const steps = ['프로젝트 정보 입력', 'Figma · GitHub 연결', 'AI 분석 & 디자인 QA']
  return (
    <section className="flex flex-col items-center rounded-2xl border border-qa-border bg-white px-6 pb-[98px] pt-[97px]">
      <span className="flex h-12 w-12 items-center justify-center rounded-xl border border-qa-border bg-qa-surface text-black">
        <Icon name="folder-plus" className="h-6 w-6" />
      </span>
      <h2 className="mt-4 text-base font-bold text-black">아직 연결된 프로젝트가 없어요</h2>
      <p className="mt-1 max-w-[400px] text-center text-sm leading-5 text-qa-text-sub">
        Figma 파일과 GitHub 저장소를 연결하면 디자인 QA를 시작할 수 있어요.
      </p>
      <button type="button" onClick={onCreate}
        className="mt-4 inline-flex h-10 items-center gap-2 rounded-lg bg-qa-primary-hover px-4 text-sm font-medium text-white hover:bg-[#0062bd]">
        <Icon name="plus" className="h-5 w-5" />새 프로젝트 만들기
      </button>
      <ol className="mt-10 flex flex-wrap items-center justify-center gap-4">
        {steps.map((label, i) => (
          <li key={label} className="flex items-center gap-4">
            {i > 0 && <span className="h-px w-10 bg-qa-border" aria-hidden="true" />}
            <span className="flex items-center gap-2">
              <span className={`flex h-6 w-6 items-center justify-center rounded-full text-xs font-medium ${
                i === 0 ? 'bg-qa-primary-hover text-white' : 'border border-qa-border bg-qa-surface text-qa-text-sub'}`}>{i + 1}</span>
              <span className={`text-sm font-medium ${i === 0 ? 'text-black' : 'text-qa-text-sub'}`}>{label}</span>
            </span>
          </li>
        ))}
      </ol>
    </section>
  )
}
