import { useState } from 'react'
import type { Invitation, ProjectMember } from '../../lib/api'
import { InviteStep } from '../projects/InviteStep'
import { Overlay } from '../projects/WizardParts'

// 운영 중인 프로젝트의 [멤버 초대] — 시안 MEM-03~05. 화면은 새 프로젝트 마법사의 초대 단계(InviteStep)와 같은 것을 쓰고,
// 단계 표시 없이 현재 멤버·대기 중 초대와 겹치는 이메일을 입력 단계에서 바로 막는다(MEM-03V2).
// 입력·확인은 760px, 완료(MEM-05)는 600px 창. 열려 있을 때만 그린다 — 다시 열면 입력이 처음부터 시작된다(취소 시 입력 폐기).
interface InviteDialogProps {
  projectId: string
  projectName: string
  ownerId: string
  meId?: string
  members: ProjectMember[]
  /** 대기 중(pending) 초대만 */
  invitations: Invitation[]
  onClose: () => void
  /** 초대·재전송이 한 건 성공할 때마다 */
  onInvited: () => void
  /** MEM-05 [멤버 관리에서 확인] */
  onManageMembers: () => void
  /** MEM-05 [작업공간으로 돌아가기] */
  onBackToWorkspace: () => void
}

export function InviteDialog(props: InviteDialogProps) {
  const [sent, setSent] = useState(false)
  return (
    <Overlay width={sent ? 600 : 760} onClose={props.onClose}>
      <InviteStep variant="manage" {...props} onSent={() => setSent(true)} />
    </Overlay>
  )
}
