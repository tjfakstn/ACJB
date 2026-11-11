import { useState } from 'react'
import type { Project } from '../../lib/api'
import { Icon } from '../common/Icon'
import { ConnectionCards, type ConnectionStatus } from './ConnectionCards'
import { CloseButton, Stepper } from './WizardParts'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from './wizardStyles'

// 새 프로젝트 마법사 3단계 — 연결 카드는 ConnectionCards(연결 관리 화면과 같이 쓴다).
// [프로젝트 시작하기]를 누르면 비교 분석(CON-06)으로 넘어간다. 여기서 닫아도 나중에 연결 관리 화면에서 연결할 수 있다

interface ConnectStepProps {
  project: Project
  onClose: () => void
  onBack: () => void
  /** 둘 다 연결된 뒤 [프로젝트 시작하기] */
  onStart: () => void
  /** 비교 분석이 Figma 한도로 실패해 [Figma 토큰 넣기]로 돌아왔으면 토큰 입력칸을 펼친다 */
  openToken?: boolean
}

export function ConnectStep({ project, onClose, onBack, onStart, openToken = false }: ConnectStepProps) {
  const [status, setStatus] = useState<ConnectionStatus>({ bothConnected: false, busy: false })
  const { bothConnected, busy } = status

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col gap-3">
        <Stepper current={2} />
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 id="create-project-title" className="text-xl font-bold text-black">Figma·Git 연결</h2>
            <p className="mt-1 text-sm leading-5 text-qa-text-sub">{project.name} · 분석할 GitHub 저장소와 Figma 파일 링크를 입력하세요. 접근 권한이 확인되면 연결이 완료됩니다.</p>
          </div>
          <CloseButton onClick={onClose} />
        </div>
      </div>

      <ConnectionCards projectId={project.id} onStatus={setStatus} openToken={openToken} />

      <div className="h-px bg-qa-border" />
      <div className="flex items-center justify-between gap-3">
        {bothConnected ? (
          <span className="flex items-center gap-1.5 text-xs text-qa-success"><Icon name="check" className="h-4 w-4" />연결 완료 · 시작해도 QA는 자동 실행되지 않아요</span>
        ) : (
          <span className="text-xs text-qa-text-sub">지금 건너뛰어도 작업공간의 연결 관리에서 연결할 수 있어요</span>
        )}
        <div className="flex gap-2">
          <button type="button" onClick={onBack} disabled={busy} className={SECONDARY_BUTTON}>이전</button>
          <button type="button" onClick={onStart} disabled={!bothConnected} className={PRIMARY_BUTTON}>프로젝트 시작하기</button>
        </div>
      </div>
    </div>
  )
}

