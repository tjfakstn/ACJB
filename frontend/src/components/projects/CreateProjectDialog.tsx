import { useState } from 'react'
import { ApiError, createProject, ROLE_LABELS, type MemberRole, type Project } from '../../lib/api'
import { RoleSelect } from '../common/RoleSelect'

// 새 프로젝트 만들기 팝업 — 시안 U01-04(입력) → U01-05(생성 중) → U01-06(완료), 분기 U01-B1(이름 미입력)·B2(입력값 오류)·B3(생성 실패).
// 시안과 다른 점(스펙에 맞춤 — 오단비·최유정 확인 필요):
//   - "내 역할"을 받는다: POST /projects는 만든 사람의 role이 필수다(SPEC AC16). 시안은 소유자 역할이 비어 있다
//   - "프로젝트 설명"은 받지 않는다: 서버에 설명 필드가 없다 — 받아도 저장되지 않는 값을 입력하게 하지 않는다
//   - 단계 표시에서 "Figma·Git 연결"을 뺐다: GitHub 연동은 v1 비포함
const NAME_MIN = 2
const NAME_MAX = 40
const NAME_PATTERN = /^[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 _-]+$/

type Phase = 'form' | 'creating' | 'done'

// 열려 있을 때만 그린다(부모가 {open && <CreateProjectDialog/>}) — 다시 열면 입력이 처음부터 시작된다
interface CreateProjectDialogProps {
  onClose: () => void
  /** 만들어진 뒤 목록을 다시 불러오게 한다 */
  onCreated: (project: Project) => void
  /** 완료 화면의 [멤버 초대하기] */
  onInvite: (project: Project) => void
}

function nameError(name: string): string | null {
  const v = name.trim()
  if (!v) return '프로젝트 이름을 입력해 주세요'
  if (v.length < NAME_MIN || v.length > NAME_MAX) return `${NAME_MIN}~${NAME_MAX}자로 입력해 주세요`
  if (!NAME_PATTERN.test(v)) return '한글, 영문, 숫자, 공백, - _ 만 사용할 수 있어요'
  return null
}

export function CreateProjectDialog({ onClose, onCreated, onInvite }: CreateProjectDialogProps) {
  const [phase, setPhase] = useState<Phase>('form')
  const [name, setName] = useState('')
  const [role, setRole] = useState<MemberRole | null>(null)
  const [touched, setTouched] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [created, setCreated] = useState<Project | null>(null)

  const nameMsg = touched ? nameError(name) : null
  const roleMissing = touched && role === null

  const submit = async () => {
    setTouched(true)
    if (nameError(name) || role === null) return
    setFailure(null)
    setPhase('creating')
    try {
      const project = await createProject(name.trim(), role)
      setCreated(project)
      setPhase('done')
      onCreated(project)
    } catch (e) {
      // 입력은 그대로 두고 다시 시도할 수 있게 폼으로 돌아간다(U01-B3)
      setFailure(e instanceof ApiError ? e.message : '일시적인 서버 오류가 발생했어요.')
      setPhase('form')
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-[#0f141e]/45 px-5 py-10"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && phase !== 'creating') onClose()
      }}
    >
      <div role="dialog" aria-modal="true" aria-labelledby="create-project-title"
        className="mt-[80px] w-[560px] max-w-full rounded-2xl bg-white shadow-[0_8px_24px_rgba(0,0,0,0.12)]">
        {phase === 'done' && created ? (
          <DoneView project={created} role={role} onClose={onClose} onInvite={() => onInvite(created)} />
        ) : (
          <>
            <div className="relative px-6 pt-6">
              <Stepper current={0} />
              <h2 id="create-project-title" className="mt-5 text-xl font-extrabold tracking-tight">새 프로젝트 만들기</h2>
              <p className="mt-2 text-sm leading-5 text-gray-600">프로젝트를 만든 뒤 멤버 초대 순서로 진행합니다.</p>
              {phase !== 'creating' && <CloseButton onClick={onClose} />}
            </div>

            <div className="px-6 pt-5">
              {failure && (
                <div role="alert" className="mb-4 flex gap-3 rounded-xl border border-[#f7c9c9] bg-[#fdecec] px-4 py-3">
                  <span className="mt-0.5 text-qa-warning">!</span>
                  <div>
                    <div className="text-sm font-semibold text-qa-warning">프로젝트를 만들지 못했어요</div>
                    <div className="mt-0.5 text-xs text-gray-600">{failure} 입력한 내용은 그대로 유지되니 다시 시도해 주세요.</div>
                  </div>
                </div>
              )}

              <section className="rounded-xl border border-qa-border bg-qa-surface p-4">
                <div className="text-xs font-medium text-gray-500">프로젝트 정보</div>

                <label className="mt-4 block text-xs font-medium text-gray-800" htmlFor="project-name">
                  프로젝트 이름 <span className="text-qa-warning">*</span>
                </label>
                <input
                  id="project-name"
                  value={name}
                  maxLength={NAME_MAX}
                  disabled={phase === 'creating'}
                  onChange={(e) => setName(e.target.value)}
                  onBlur={() => setTouched(true)}
                  placeholder="예: 쇼핑몰 앱 리디자인"
                  aria-invalid={!!nameMsg}
                  className={`mt-1.5 h-10 w-full rounded-lg border bg-white px-3 text-sm outline-none focus:border-qa-primary ${
                    nameMsg ? 'border-red-300' : 'border-qa-border'
                  }`}
                />
                <div className="mt-1.5 flex justify-between text-xs">
                  <span className={nameMsg ? 'text-qa-warning' : 'text-qa-sub'}>
                    {nameMsg ?? `${NAME_MIN}~${NAME_MAX}자 · 한글, 영문, 숫자, 공백, - _ 사용 가능`}
                  </span>
                  <span className="text-qa-sub">{name.trim().length} / {NAME_MAX}</span>
                </div>

                <div className="mt-4 text-xs font-medium text-gray-800">
                  이 프로젝트에서 내 역할 <span className="text-qa-warning">*</span>
                </div>
                <div className="mt-1.5">
                  <RoleSelect value={role} onChange={setRole} invalid={roleMissing} />
                </div>
                <div className={`mt-1.5 text-xs ${roleMissing ? 'text-qa-warning' : 'text-qa-sub'}`}>
                  {roleMissing ? '역할을 선택해 주세요' : '역할은 이 프로젝트에서만 쓰여요. 만든 사람은 소유자가 됩니다.'}
                </div>
              </section>
            </div>

            <div className="mt-5 flex items-center justify-between border-t border-qa-border px-6 py-4">
              <span className={`text-xs ${touched && (nameError(name) || role === null) ? 'text-qa-warning' : 'text-qa-sub'}`}>
                {touched && (nameError(name) || role === null) ? '필수 항목을 확인해 주세요' : '* 필수 입력 항목'}
              </span>
              <div className="flex gap-2">
                <button type="button" onClick={onClose} disabled={phase === 'creating'}
                  className="h-10 rounded-lg border border-qa-border px-4 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50">
                  취소
                </button>
                <button type="button" onClick={submit} disabled={phase === 'creating'}
                  className="inline-flex h-10 items-center gap-2 rounded-lg bg-qa-primary px-4 text-sm font-semibold text-white hover:bg-qa-primary-hover disabled:opacity-70">
                  {phase === 'creating' && <Spinner />}
                  {phase === 'creating' ? '프로젝트 만드는 중' : failure ? '다시 시도' : '프로젝트 만들기'}
                </button>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function DoneView({ project, role, onClose, onInvite }: { project: Project; role: MemberRole | null; onClose: () => void; onInvite: () => void }) {
  return (
    <div className="relative px-6 pb-5 pt-6">
      <div className="flex h-11 w-11 items-center justify-center rounded-full bg-[#e8f5f3] text-qa-success">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
          <path d="m5 12 5 5L20 7" />
        </svg>
      </div>
      <div className="mt-4"><Stepper current={1} /></div>
      <h2 id="create-project-title" className="mt-5 text-lg font-extrabold tracking-tight">‘{project.name}’ 프로젝트를 만들었어요</h2>
      <p className="mt-1 text-sm text-gray-600">함께 일할 팀원을 초대해 보세요. 초대받은 사람은 수락할 때 자기 역할을 고릅니다.</p>
      <CloseButton onClick={onClose} />

      <dl className="mt-4 grid grid-cols-[96px_1fr] gap-y-2 rounded-xl border border-qa-border bg-qa-surface p-4 text-sm">
        <dt className="text-gray-500">프로젝트 이름</dt>
        <dd className="font-medium text-gray-900">{project.name}</dd>
        <dt className="text-gray-500">내 역할</dt>
        <dd className="text-gray-900">{role ? ROLE_LABELS[role] : '-'}</dd>
        <dt className="text-gray-500">내 접근 권한</dt>
        <dd className="text-gray-900">소유자 · 멤버 초대와 내보내기 가능</dd>
      </dl>

      <div className="mt-5 flex items-center justify-between border-t border-qa-border pt-4">
        <span className="text-xs text-qa-sub">초대는 나중에 멤버 관리에서도 할 수 있어요</span>
        <div className="flex gap-2">
          <button type="button" onClick={onClose}
            className="h-10 rounded-lg border border-qa-border px-4 text-sm font-medium text-gray-700 hover:bg-gray-50">
            초대 건너뛰기
          </button>
          <button type="button" onClick={onInvite}
            className="h-10 rounded-lg bg-qa-primary px-4 text-sm font-semibold text-white hover:bg-qa-primary-hover">
            멤버 초대하기
          </button>
        </div>
      </div>
    </div>
  )
}

const STEPS = ['프로젝트 정보', '멤버 초대']

function Stepper({ current }: { current: number }) {
  return (
    <div className="flex items-center gap-2 text-xs">
      {STEPS.map((label, i) => (
        <div key={label} className="flex items-center gap-2">
          <span className={`flex h-4 w-4 items-center justify-center rounded-full border-2 ${
            i < current ? 'border-qa-success bg-qa-success' : i === current ? 'border-qa-primary' : 'border-gray-300'}`}>
            {i === current && <span className="h-1.5 w-1.5 rounded-full bg-qa-primary" />}
          </span>
          <span className={i === current ? 'font-medium text-gray-900' : 'text-gray-500'}>{label}</span>
          {i < STEPS.length - 1 && <span className="h-px w-8 bg-gray-200" />}
        </div>
      ))}
    </div>
  )
}

function CloseButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} aria-label="닫기"
      className="absolute right-[22px] top-[22px] flex h-8 w-8 items-center justify-center rounded-lg text-gray-500 hover:bg-gray-100">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
        <path d="M6 6l12 12M18 6 6 18" />
      </svg>
    </button>
  )
}

export function Spinner() {
  return <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white" aria-hidden="true" />
}
