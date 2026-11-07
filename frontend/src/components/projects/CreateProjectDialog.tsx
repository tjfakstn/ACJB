import { useState } from 'react'
import { createProject, formatDate, type Project } from '../../lib/api'
import { Spinner } from '../common/Spinner'
import { Icon } from '../common/Icon'
import { CloseButton, Kv, NextStep, Overlay, Stepper } from './WizardParts'
import { InviteStep } from './InviteStep'
import { ConnectStep } from './ConnectStep'

// 새 프로젝트 만들기 마법사 — 시안 "전체 서비스 최종 통합 UI"의 PRJ-02(입력) → PRJ-03(처리 중) → PRJ-04(생성 완료)
// → 2단계 멤버 초대(InviteStep, INV-01~05) → 3단계 Figma·Git 연결(ConnectStep, CON-01~05). 한 팝업 안에서 이어지고 뒤 화면은 목록 그대로다.
// 예외 PRJ-02V1(이름 미입력)·PRJ-02V2(입력값 오류)·PRJ-03F(생성 실패). 입력은 이름·설명뿐이다(SPEC AC16, 2026-11-07).
const NAME_MIN = 2
const NAME_MAX = 40
const DESCRIPTION_MAX = 200
const ALLOWED_CHAR = /[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 _-]/

type Phase = 'form' | 'creating' | 'failed' | 'done' | 'invite' | 'connect'

interface CreateProjectDialogProps {
  onClose: () => void
  /** 만들어진 뒤 목록을 다시 불러오게 한다 */
  onCreated: (project: Project) => void
  /** INV-05 [멤버 관리] — 마법사를 닫고 멤버 관리로 간다 */
  onManageMembers: (project: Project) => void
  /** CON-05 [프로젝트 시작하기] — 비교 분석(CON-06)이 아직 없어 작업공간 홈으로 간다 */
  onStart: (project: Project) => void
}

/** 이름 검사 — 시안 PRJ-02V1·V2 문구. invalidChars는 "사용할 수 없는 문자" 줄에 보여준다 */
function checkName(name: string) {
  const trimmed = name.trim()
  const invalidChars = [...new Set([...name].filter((ch) => !ALLOWED_CHAR.test(ch)))]
  const tooLong = name.length > NAME_MAX
  let message: string | null = null
  if (!trimmed) message = '프로젝트 이름을 입력해 주세요'
  else if (tooLong && invalidChars.length) message = `${NAME_MAX}자 이하로 입력하고, 특수문자(${invalidChars.join('')})는 제외해 주세요`
  else if (tooLong) message = `${NAME_MAX}자 이하로 입력해 주세요`
  else if (invalidChars.length) message = `특수문자(${invalidChars.join('')})는 제외해 주세요`
  else if (trimmed.length < NAME_MIN) message = `${NAME_MIN}자 이상 입력해 주세요`
  return { message, invalidChars, tooLong }
}

export function CreateProjectDialog({ onClose, onCreated, onManageMembers, onStart }: CreateProjectDialogProps) {
  const [phase, setPhase] = useState<Phase>('form')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [touched, setTouched] = useState(false)
  const [created, setCreated] = useState<Project | null>(null)

  const nameCheck = checkName(name)
  // 빈 이름은 건드린 뒤에만 오류로 보여주고(V1), 길이·문자 오류는 입력하는 즉시 보여준다(V2)
  const showNameError = nameCheck.message !== null && (touched || nameCheck.tooLong || nameCheck.invalidChars.length > 0)
  const descriptionTooLong = description.length > DESCRIPTION_MAX
  const canSubmit = nameCheck.message === null && !descriptionTooLong
  const busy = phase === 'creating'

  const submit = async () => {
    setTouched(true)
    if (!canSubmit) return
    setPhase('creating')
    try {
      const project = await createProject(name.trim(), description.trim() || null)
      setCreated(project)
      setPhase('done')
      onCreated(project)
    } catch {
      // 입력은 그대로 두고 다시 시도할 수 있게 한다(PRJ-03F)
      setPhase('failed')
    }
  }

  if (created && (phase === 'done' || phase === 'invite' || phase === 'connect')) {
    // 시안 PRJ-04·INV의 ✕·[나중에 …]는 연결 단계(CON-01)로 간다. 연결 단계의 ✕만 마법사를 닫는다(PRJ-01)
    const toConnect = () => setPhase('connect')
    return (
      <Overlay onClose={phase === 'connect' ? onClose : toConnect}>
        {phase === 'done' && <DoneView project={created} onInvite={() => setPhase('invite')} onLater={toConnect} />}
        {/* 연결 단계에서 [이전]으로 돌아왔을 때 보낸 초대 결과가 남도록 초대 단계는 숨기기만 한다 */}
        {phase !== 'done' && (
          <div hidden={phase !== 'invite'}>
            <InviteStep project={created} onClose={toConnect} onNext={toConnect} onManageMembers={() => onManageMembers(created)} />
          </div>
        )}
        {phase === 'connect' && <ConnectStep project={created} onClose={onClose} onBack={() => setPhase('invite')} onStart={() => onStart(created)} />}
      </Overlay>
    )
  }

  return (
    <Overlay onClose={busy ? undefined : onClose}>
      <form onSubmit={(e) => { e.preventDefault(); void submit() }} noValidate className="flex flex-col gap-5">
        <div className="flex flex-col gap-3">
          <Stepper current={0} />
          <div className="flex items-start justify-between gap-4">
            <div>
              <h2 id="create-project-title" className="text-xl font-bold text-black">새 프로젝트 만들기</h2>
              <p className="mt-1 text-sm leading-5 text-qa-text-sub">프로젝트를 만든 뒤 멤버 초대 → Figma·Git 연결 → 프로젝트 시작 순서로 진행합니다.</p>
            </div>
            <CloseButton onClick={onClose} disabled={busy} />
          </div>
        </div>

        {phase === 'failed' && (
          <div role="alert" className="flex gap-3 rounded-xl bg-[#fc1b1f]/10 px-4 py-3">
            <Icon name="alert" className="mt-px h-5 w-5 flex-none text-qa-warning" />
            <div>
              <div className="text-sm font-medium text-black">프로젝트를 만들지 못했어요</div>
              <div className="mt-0.5 text-xs text-qa-text-sub">일시적인 서버 오류가 발생했어요. 입력한 내용은 그대로 유지되니 다시 시도해 주세요.</div>
            </div>
          </div>
        )}

        <fieldset disabled={busy} className="flex flex-col gap-4 rounded-xl border border-qa-border bg-qa-surface p-4">
          <legend className="sr-only">프로젝트 정보</legend>
          <div className="text-xs font-medium text-qa-text-sub">프로젝트 정보</div>

          <div className="flex flex-col gap-1.5">
            <label htmlFor="project-name" className={`text-xs font-medium ${busy ? 'text-qa-sub' : 'text-black'}`}>프로젝트 이름 *</label>
            <input
              id="project-name"
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              onBlur={() => setTouched(true)}
              placeholder="예: 쇼핑몰 앱 리디자인"
              aria-invalid={showNameError}
              aria-describedby="project-name-hint"
              className={`h-10 rounded-lg border px-3 text-sm outline-none placeholder:text-qa-sub disabled:bg-qa-surface disabled:text-qa-sub ${
                showNameError ? 'border-qa-warning bg-white' : busy ? 'border-qa-border' : 'border-qa-sub bg-white focus:border-qa-primary'
              }`}
            />
            {showNameError && (
              <div className="flex items-center gap-1 text-xs text-black">
                <Icon name="alert-circle" className="h-4 w-4 flex-none text-qa-warning" />
                {nameCheck.message}
              </div>
            )}
            <div id="project-name-hint" className="flex justify-between gap-3 text-xs">
              {nameCheck.invalidChars.length > 0
                ? <span className="text-qa-warning">사용할 수 없는 문자: {nameCheck.invalidChars.join(' ')}</span>
                : <span className="text-qa-text-sub">2~40자 · 한글, 영문, 숫자, 공백, - _ 사용 가능</span>}
              <span className={nameCheck.tooLong ? 'text-qa-warning' : 'text-qa-sub'}>{name.length} / {NAME_MAX}</span>
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <label htmlFor="project-description" className={`text-xs font-medium ${busy ? 'text-qa-sub' : 'text-black'}`}>프로젝트 설명 (선택)</label>
            <textarea
              id="project-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="프로젝트의 목적이나 QA 범위를 간단히 적어 주세요"
              rows={4}
              aria-invalid={descriptionTooLong}
              className={`h-[88px] resize-none rounded-lg border px-3 py-2.5 text-sm outline-none placeholder:text-qa-sub disabled:bg-qa-surface disabled:text-qa-sub ${
                descriptionTooLong ? 'border-qa-warning bg-white' : busy ? 'border-qa-border' : 'border-qa-sub bg-white focus:border-qa-primary'
              }`}
            />
            <div className="flex justify-between gap-3 text-xs">
              <span className={descriptionTooLong ? 'text-qa-warning' : 'text-qa-text-sub'}>
                {descriptionTooLong ? `${DESCRIPTION_MAX}자 이하로 입력해 주세요` : '설명은 초대받은 멤버에게도 표시됩니다'}
              </span>
              <span className={descriptionTooLong ? 'text-qa-warning' : 'text-qa-sub'}>{description.length} / {DESCRIPTION_MAX}</span>
            </div>
          </div>
        </fieldset>

        <div className="flex items-center gap-2 text-xs text-qa-text-sub">
          <Icon name="link" className="h-4 w-4 flex-none" />
          Figma·Git 연결은 멤버 초대 다음 단계에서 진행해요. 프로젝트 정보 입력에는 필요하지 않습니다.
        </div>

        <div className="h-px bg-qa-border" />

        <div className="flex items-center justify-between gap-3">
          {busy ? (
            <span className="flex items-center gap-1.5 text-xs text-qa-text-sub">
              <Icon name="clock" className="h-4 w-4" />프로젝트를 만드는 중이에요… 창을 닫지 마세요
            </span>
          ) : touched && !canSubmit ? (
            <span className="flex items-center gap-1.5 text-xs text-qa-warning">
              <Icon name="alert" className="h-4 w-4" />필수 항목을 확인해 주세요
            </span>
          ) : (
            <span className="text-xs text-qa-text-sub">* 필수 입력 항목</span>
          )}
          <div className="flex gap-2">
            <button type="button" onClick={onClose} disabled={busy}
              className="h-10 rounded-lg border border-qa-primary bg-white px-4 text-sm font-medium text-qa-primary-hover hover:bg-[#eef4ff] disabled:border-qa-border disabled:text-qa-sub disabled:hover:bg-white">
              취소
            </button>
            <button type="submit" disabled={busy || !canSubmit}
              className="inline-flex h-10 items-center gap-2 rounded-lg bg-qa-primary-hover px-4 text-sm font-medium text-white hover:bg-[#0062bd] disabled:bg-[#e8e8e8] disabled:text-qa-sub [&:disabled[aria-busy=true]]:bg-qa-primary-hover [&:disabled[aria-busy=true]]:text-white"
              aria-busy={busy}>
              {busy ? <><Spinner />만드는 중…</> : phase === 'failed' ? '다시 시도' : '프로젝트 만들기'}
            </button>
          </div>
        </div>
      </form>
    </Overlay>
  )
}

function DoneView({ project, onInvite, onLater }: { project: Project; onInvite: () => void; onLater: () => void }) {
  return (
    <div className="flex flex-col gap-5">
      <span className="flex h-12 w-12 items-center justify-center rounded-full bg-qa-success/10 text-qa-success">
        <Icon name="circle-check" className="h-6 w-6" />
      </span>
      <div className="flex flex-col gap-3">
        <Stepper current={1} />
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 id="create-project-title" className="text-xl font-bold text-black">‘{project.name}’ 프로젝트를 만들었어요</h2>
            <p className="mt-1 text-sm text-qa-text-sub">함께 일할 팀원을 초대하고, 각자 이 프로젝트에서 맡을 역할을 지정해 보세요.</p>
          </div>
          <CloseButton onClick={onLater} />
        </div>
      </div>

      <dl className="flex flex-col gap-3 rounded-xl border border-qa-border bg-qa-surface p-4 text-sm">
        <Kv label="프로젝트 이름" value={project.name} />
        <Kv label="설명" value={project.description ?? '-'} />
        <Kv label="생성일" value={formatDate(project.created_at)} />
        <Kv label="내 접근 권한" value="소유자 · 멤버 초대와 역할 관리 가능" />
      </dl>

      <div className="flex flex-col gap-2">
        <div className="text-xs font-medium text-qa-text-sub">다음 단계</div>
        <NextStep n={1} active title="멤버 초대 및 역할 지정" body="기획·디자인·프론트엔드·백엔드 중 역할을 지정해 초대합니다" badge="권장" />
        <NextStep n={2} title="Figma·GitHub 연결" body="멤버 초대 다음 단계 · 접근 권한 확인 후 연결 완료" />
        <NextStep n={3} title="첫 QA 실행" body="프로젝트 시작 후 작업공간에서 직접 실행 (자동 실행 없음)" />
      </div>

      <div className="h-px bg-qa-border" />

      <div className="flex items-center justify-between gap-3">
        <span className="text-xs text-qa-text-sub">초대는 나중에 작업공간에서도 할 수 있어요</span>
        <div className="flex gap-2">
          <button type="button" onClick={onLater}
            className="h-10 rounded-lg border border-qa-primary bg-white px-4 text-sm font-medium text-qa-primary-hover hover:bg-[#eef4ff]">
            나중에 초대하기
          </button>
          <button type="button" onClick={onInvite} autoFocus
            className="inline-flex h-10 items-center gap-2 rounded-lg bg-qa-primary-hover px-4 text-sm font-medium text-white hover:bg-[#0062bd]">
            <Icon name="user-plus" className="h-5 w-5" />멤버 초대하기
          </button>
        </div>
      </div>
    </div>
  )
}
