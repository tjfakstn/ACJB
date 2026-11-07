import { useState } from 'react'
import { ApiError, deleteProject, type ProjectDetail } from '../../lib/api'
import { Icon } from '../common/Icon'
import { Spinner } from '../common/Spinner'
import { CloseButton, Overlay } from './WizardParts'
import { SECONDARY_BUTTON } from './wizardStyles'

// 프로젝트 삭제 확인(SPEC AC26) — 되돌릴 수 없으므로 프로젝트 이름을 그대로 입력해야 [삭제]가 켜진다(decision_log 2026-11-07).
// 시안에 없는 화면이라 마법사 창과 같은 틀·버튼을 쓴다.
interface DeleteProjectDialogProps {
  project: ProjectDetail
  onClose: () => void
  onDeleted: () => void
}

export function DeleteProjectDialog({ project, onClose, onDeleted }: DeleteProjectDialogProps) {
  const [typed, setTyped] = useState('')
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const matches = typed === project.name
  const issueTotal = Object.values(project.issue_counts).reduce((a, b) => a + b, 0)

  const submit = async () => {
    if (!matches) return
    setDeleting(true)
    setError(null)
    try {
      await deleteProject(project.id)
      onDeleted()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '프로젝트를 삭제하지 못했어요. 잠시 뒤 다시 시도해 주세요.')
      setDeleting(false)
    }
  }

  return (
    <Overlay onClose={deleting ? undefined : onClose}>
      <form onSubmit={(e) => { e.preventDefault(); void submit() }} className="flex flex-col gap-5">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 id="create-project-title" className="text-xl font-bold text-black">프로젝트를 삭제할까요?</h2>
            <p className="mt-1 text-sm leading-5 text-qa-text-sub">삭제하면 되돌릴 수 없어요. 멤버들도 이 프로젝트에 더 이상 들어올 수 없습니다.</p>
          </div>
          <CloseButton onClick={onClose} disabled={deleting} />
        </div>

        <div className="flex gap-3 rounded-xl bg-qa-warning/10 px-4 py-3">
          <Icon name="alert" className="mt-px h-5 w-5 flex-none text-qa-warning" />
          <div className="text-sm text-black">
            <div className="font-medium">‘{project.name}’과 그 안의 기록이 모두 지워져요</div>
            <ul className="mt-1 list-disc pl-4 text-xs leading-5 text-qa-text-sub">
              <li>QA 이슈 {issueTotal}건과 상태 이력·재검증·알림·스타일 차이</li>
              <li>멤버 {project.member_count}명의 참여 정보와 대기 중 초대 {project.pending_invitation_count}건</li>
              <li>Figma·GitHub 연결과 분석 기록</li>
            </ul>
            <div className="mt-1 text-xs text-qa-text-sub">멤버들의 QAting 계정과 다른 프로젝트는 그대로예요.</div>
          </div>
        </div>

        <div className="flex flex-col gap-1.5">
          <label htmlFor="delete-confirm" className="text-xs font-medium text-black">
            확인을 위해 프로젝트 이름 <span className="font-bold">{project.name}</span>을(를) 입력해 주세요
          </label>
          <input id="delete-confirm" autoFocus value={typed} onChange={(e) => setTyped(e.target.value)} disabled={deleting}
            placeholder={project.name} autoComplete="off"
            className="h-10 rounded-lg border border-qa-sub bg-white px-3 text-sm outline-none placeholder:text-qa-sub focus:border-qa-warning disabled:bg-qa-surface" />
          {error && <span role="alert" className="text-xs text-qa-warning">{error}</span>}
        </div>

        <div className="h-px bg-qa-border" />
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} disabled={deleting} className={SECONDARY_BUTTON}>취소</button>
          <button type="submit" disabled={!matches || deleting} aria-busy={deleting}
            className="inline-flex h-10 flex-none items-center gap-2 whitespace-nowrap rounded-lg bg-qa-warning px-4 text-sm font-medium text-white hover:bg-[#d9161a] disabled:bg-[#e8e8e8] disabled:text-qa-sub">
            {deleting ? <><Spinner />삭제하는 중…</> : '프로젝트 삭제'}
          </button>
        </div>
      </form>
    </Overlay>
  )
}
