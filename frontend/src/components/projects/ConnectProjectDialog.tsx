import { useEffect, useState } from 'react'
import { attemptConnect } from './connectProject'
import type { ConnectProjectInput, ConnectService } from './types'

// 새 프로젝트 연결 팝업 (02-3 기본정보 → 02-4 서비스연결 → 02-6 분석 진행 / 02-5 링크 오류).
// 흐름:
//   1) 기본 정보 입력
//   2) 서비스 연결(GitHub/Figma 링크) → '시작하기' → 백엔드 연결/검증 시도(attemptConnect)
//        - 실패: 02-5 오류 화면(배너 + 실패 서비스 카드)로 남고, 프로젝트는 생성되지 않는다.
//        - 성공: 3단계(분석)로 진행
//   3) 분석 진행 → 완료 시 onComplete(입력값, 집계) 호출 → 부모가 목록에 카드 추가
// 즉, 연결/분석이 성공했을 때만 onComplete가 불려서 목록에 올라간다. 실패하면 목록에 뜨지 않는다.
interface ConnectProjectDialogProps {
  open: boolean
  onClose: () => void
  onComplete: (input: ConnectProjectInput, counts: { qaCount: number; failCount: number }) => void
}

const ANALYZE_STEPS = ['디자인 구조 분석', 'UI 코드 분석', '변경 이력 분석']

export function ConnectProjectDialog({ open, onClose, onComplete }: ConnectProjectDialogProps) {
  const [step, setStep] = useState<1 | 2 | 3>(1)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [githubLink, setGithubLink] = useState('')
  const [figmaLink, setFigmaLink] = useState('')
  const [nameError, setNameError] = useState(false)
  const [connecting, setConnecting] = useState(false)
  const [errorService, setErrorService] = useState<ConnectService | null>(null)
  const [errorMessage, setErrorMessage] = useState('')
  const [counts, setCounts] = useState({ qaCount: 0, failCount: 0 })
  const [doneCount, setDoneCount] = useState(0) // 분석 완료 단계 수(0~3)

  // 팝업이 열릴 때마다 초기화
  useEffect(() => {
    if (open) {
      setStep(1)
      setName('')
      setDescription('')
      setGithubLink('')
      setFigmaLink('')
      setNameError(false)
      setConnecting(false)
      setErrorService(null)
      setErrorMessage('')
      setCounts({ qaCount: 0, failCount: 0 })
      setDoneCount(0)
    }
  }, [open])

  // 3단계(분석) 진입 시 단계별 진행 시뮬레이션.
  // TODO: 실제 분석 작업 상태 폴링으로 교체. 지금은 1.1초 간격으로 단계가 완료되는 더미.
  useEffect(() => {
    if (step !== 3) return
    let completed = 0
    let finishTimer: ReturnType<typeof setTimeout> | undefined
    const stepTimer = setInterval(() => {
      completed += 1
      setDoneCount(completed)
      if (completed >= ANALYZE_STEPS.length) {
        clearInterval(stepTimer)
        finishTimer = setTimeout(() => {
          onComplete(
            { name: name.trim(), description: description.trim(), githubLink: githubLink.trim(), figmaLink: figmaLink.trim() },
            counts,
          )
        }, 650)
      }
    }, 1100)
    return () => {
      clearInterval(stepTimer)
      clearTimeout(finishTimer)
    }
    // 분석 시작 시점의 입력값/집계로 충분하므로 step 변화에만 반응한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step])

  if (!open) return null

  const goNext = () => {
    if (!name.trim()) {
      setNameError(true)
      return
    }
    setStep(2)
  }

  const handleStart = async () => {
    setConnecting(true)
    setErrorService(null)
    const result = await attemptConnect({ name, description, githubLink, figmaLink })
    setConnecting(false)
    if (!result.ok) {
      // 연결 실패 → 오류 화면으로 남고, 프로젝트는 만들지 않는다.
      setErrorService(result.service)
      setErrorMessage(result.message)
      return
    }
    setCounts({ qaCount: result.qaCount, failCount: result.failCount })
    setStep(3)
  }

  // 실패한 필드를 수정하면 오류를 지우고 '시작하기'를 다시 활성화한다.
  const onGithubChange = (v: string) => {
    setGithubLink(v)
    if (errorService === 'github') setErrorService(null)
  }
  const onFigmaChange = (v: string) => {
    setFigmaLink(v)
    if (errorService === 'figma') setErrorService(null)
  }

  const startDisabled = connecting || errorService !== null

  const descByStep =
    step === 1
      ? 'QA를 시작할 프로젝트의 기본 정보를 입력하세요. 다음 단계에서 Figma와 GitHub를 연결합니다.'
      : `${name.trim() || '새 프로젝트'} · ${
          step === 2 ? '분석할 GitHub 저장소와 Figma 파일 링크를 입력하세요.' : '연결이 완료되어 비교 분석을 시작했어요.'
        }`

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-[#0f141e]/45 px-5 py-10"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && step !== 3) onClose()
      }}
    >
      <div className="mt-[120px] w-[640px] max-w-full rounded-2xl bg-white shadow-[0_8px_24px_rgba(0,0,0,0.12)]">
        {/* Header */}
        <div className="relative px-6 pt-6">
          <DialogStepper step={step} />
          <h2 className="mt-5 text-xl font-extrabold tracking-tight">새 프로젝트 연결</h2>
          <p className="mt-2 max-w-[542px] text-sm leading-5 text-gray-600">{descByStep}</p>
          {step !== 3 && (
            <button
              type="button"
              onClick={onClose}
              aria-label="닫기"
              className="absolute right-[22px] top-[22px] flex h-8 w-8 items-center justify-center rounded-lg text-gray-500 hover:bg-gray-100"
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                <path d="M18 6 6 18M6 6l12 12" />
              </svg>
            </button>
          )}
        </div>

        {/* Body */}
        <div className="px-6 pt-6">
          {step === 1 && (
            <div className="rounded-xl border border-qa-border bg-qa-surface p-5">
              <div className="mb-3.5 text-xs font-semibold text-gray-600">프로젝트 기본 정보</div>
              <label className="mb-2 block text-sm font-bold">프로젝트 이름</label>
              <input
                type="text"
                value={name}
                onChange={(e) => {
                  setName(e.target.value)
                  if (nameError) setNameError(false)
                }}
                placeholder="예: 쇼핑몰 앱 리디자인"
                className={`h-11 w-full rounded-lg border bg-white px-3.5 text-sm outline-none placeholder:text-qa-sub focus:border-qa-primary ${
                  nameError ? 'border-qa-warning' : 'border-qa-border'
                }`}
              />
              {nameError && <p className="mt-1.5 text-xs text-qa-warning">프로젝트 이름을 입력해 주세요.</p>}
              <label className="mb-2 mt-4 block text-sm font-bold">설명 (선택)</label>
              <input
                type="text"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="프로젝트를 간단히 소개해 주세요"
                className="h-11 w-full rounded-lg border border-qa-border bg-white px-3.5 text-sm outline-none placeholder:text-qa-sub focus:border-qa-primary"
              />
            </div>
          )}

          {step === 2 && (
            <>
              {errorService && (
                <div className="mb-4 flex gap-3 rounded-xl border border-qa-warning/40 bg-[#fdecec] px-4 py-3.5">
                  <AlertIcon className="mt-0.5 flex-none text-qa-warning" />
                  <div>
                    <div className="text-sm font-bold text-gray-900">{errorMessage}</div>
                    <div className="mt-1 text-xs text-gray-600">링크가 유효하지 않거나 저장소 접근 권한이 없어요. 수정하면 시작하기가 활성화됩니다.</div>
                  </div>
                </div>
              )}
              <div className="flex gap-4">
                <ServiceCard
                  label="GitHub 연결"
                  icon={<GitHubIcon />}
                  inputLabel="Repository 링크"
                  placeholder="ex: https://github.com/..."
                  value={githubLink}
                  onChange={onGithubChange}
                  error={errorService === 'github'}
                />
                <ServiceCard
                  label="Figma 연결"
                  icon={<FigmaIcon />}
                  inputLabel="Figma 링크"
                  placeholder="ex: https://figma.com/..."
                  value={figmaLink}
                  onChange={onFigmaChange}
                  error={errorService === 'figma'}
                />
              </div>
            </>
          )}

          {step === 3 && (
            <div className="pt-1 text-center">
              <div className="mx-auto mt-5 h-[34px] w-[34px] animate-spin rounded-full border-[3px] border-[#e3e8ef] border-t-qa-primary" />
              <div className="mt-[22px] text-base font-bold">비교 분석을 진행하고 있어요</div>
              <div className="mt-2 text-sm text-gray-600">Figma 디자인과 GitHub 코드를 분석 중입니다. 완료되면 작업공간으로 이동합니다.</div>
              <div className="mt-7">
                <div className="mb-2 flex justify-between text-xs font-medium text-gray-600">
                  <span>분석 진행률</span>
                  <span>{doneCount} / 3 단계</span>
                </div>
                <div className="h-1.5 overflow-hidden rounded-full bg-[#e6ecf4]">
                  <div
                    className="h-full rounded-full bg-qa-primary transition-[width] duration-500"
                    style={{ width: `${(doneCount / 3) * 100}%` }}
                  />
                </div>
              </div>
              <div className="mt-[22px] rounded-xl border border-qa-border bg-qa-surface px-4 py-2">
                {ANALYZE_STEPS.map((label, i) => {
                  const state = i < doneCount ? 'done' : i === doneCount ? 'doing' : 'pending'
                  return (
                    <div key={label} className="flex h-12 items-center gap-3 border-b border-[#edf0f4] last:border-b-0">
                      <span
                        className={`flex h-5 w-5 flex-none items-center justify-center rounded-full border-[1.5px] ${
                          state === 'done'
                            ? 'border-qa-success bg-qa-success text-white'
                            : state === 'doing'
                              ? 'border-qa-primary'
                              : 'border-[#cdd3dd]'
                        }`}
                      >
                        {state === 'done' && (
                          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                            <path d="m5 12 4.5 4.5L19 7" />
                          </svg>
                        )}
                        {state === 'doing' && <span className="h-2 w-2 rounded-full bg-qa-primary" />}
                      </span>
                      <span className={`flex-1 text-left text-sm font-medium ${state === 'pending' ? 'text-qa-sub' : 'text-gray-900'}`}>
                        {label}
                      </span>
                      <span
                        className={`text-xs font-semibold ${
                          state === 'done' ? 'text-qa-success' : state === 'doing' ? 'text-qa-primary' : 'text-qa-sub'
                        }`}
                      >
                        {state === 'done' ? '완료' : state === 'doing' ? '진행 중' : '대기'}
                      </span>
                    </div>
                  )
                })}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        {step !== 3 && (
          <div className="mt-6 flex justify-end gap-2 border-t border-[#eef1f5] px-6 pb-6 pt-5">
            <button
              type="button"
              onClick={step === 1 ? onClose : () => setStep(1)}
              className="inline-flex h-10 items-center rounded-lg border border-qa-border bg-white px-[18px] text-sm font-semibold text-gray-600 hover:bg-gray-50"
            >
              {step === 1 ? '취소' : '이전'}
            </button>
            <button
              type="button"
              onClick={step === 1 ? goNext : handleStart}
              disabled={step === 2 && startDisabled}
              className="inline-flex h-10 items-center rounded-lg bg-qa-primary px-[18px] text-sm font-semibold text-white transition-colors hover:bg-qa-primary-hover disabled:cursor-not-allowed disabled:bg-gray-300"
            >
              {step === 1 ? '다음으로' : connecting ? '연결 중…' : '시작하기'}
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

function DialogStepper({ step }: { step: 1 | 2 | 3 }) {
  const basicDone = step > 1
  const serviceActive = step === 2
  const serviceDone = step > 2
  return (
    <div className="flex items-center gap-3">
      <StepPill label="기본 정보" state={basicDone ? 'done' : 'active'} />
      <span className={`h-px w-7 ${basicDone ? 'bg-qa-success' : 'bg-qa-border'}`} />
      <StepPill label="서비스 연결" state={serviceDone ? 'done' : serviceActive ? 'active' : 'idle'} />
    </div>
  )
}

function StepPill({ label, state }: { label: string; state: 'idle' | 'active' | 'done' }) {
  return (
    <div className="flex items-center gap-2">
      <span
        className={`flex h-5 w-5 items-center justify-center rounded-full border-[1.5px] ${
          state === 'done' ? 'border-qa-success bg-qa-success' : state === 'active' ? 'border-qa-primary' : 'border-qa-border'
        }`}
      >
        {state === 'done' ? (
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round">
            <path d="m5 12 4.5 4.5L19 7" />
          </svg>
        ) : (
          <span className={`h-2 w-2 rounded-full ${state === 'active' ? 'bg-qa-primary' : 'bg-transparent'}`} />
        )}
      </span>
      <span className={`text-[13px] font-semibold ${state === 'idle' ? 'text-qa-sub' : 'text-gray-900'}`}>{label}</span>
    </div>
  )
}

interface ServiceCardProps {
  label: string
  icon: React.ReactNode
  inputLabel: string
  placeholder: string
  value: string
  onChange: (v: string) => void
  error?: boolean
}

function ServiceCard({ label, icon, inputLabel, placeholder, value, onChange, error = false }: ServiceCardProps) {
  const connected = value.trim().length > 0 && !error
  return (
    <div className={`flex-1 rounded-xl border p-[18px] ${error ? 'border-qa-warning' : 'border-qa-border'}`}>
      <div className="mb-3.5 flex items-center justify-between">
        <div className="flex items-center gap-2.5 text-base font-bold">
          {icon}
          {label}
        </div>
        {error ? (
          <AlertIcon className="text-qa-warning" />
        ) : (
          <span className={connected ? 'text-qa-success' : 'text-gray-300'}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="9" />
              <path d="m8.5 12 2.3 2.3 4.7-4.7" />
            </svg>
          </span>
        )}
      </div>
      <label className="mb-2 block text-[13px] font-semibold text-gray-600">{inputLabel}</label>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className={`h-[42px] w-full rounded-lg border bg-white px-3 text-[13px] font-medium text-qa-primary outline-none placeholder:font-medium placeholder:text-[#b7c7db] focus:border-qa-primary ${
          error ? 'border-qa-warning' : 'border-qa-border'
        }`}
      />
      {error && (
        <p className="mt-2 flex items-center gap-1.5 text-xs font-medium text-qa-warning">
          <AlertIcon size={14} />
          링크가 유효하지 않습니다
        </p>
      )}
    </div>
  )
}

function AlertIcon({ className = '', size = 20 }: { className?: string; size?: number }) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 8v4" />
      <path d="M12 16h.01" />
    </svg>
  )
}

function GitHubIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M12 2C6.48 2 2 6.58 2 12.25c0 4.53 2.87 8.37 6.84 9.73.5.1.68-.22.68-.49 0-.24-.01-.88-.01-1.73-2.78.62-3.37-1.37-3.37-1.37-.46-1.18-1.11-1.5-1.11-1.5-.91-.63.07-.62.07-.62 1 .07 1.53 1.06 1.53 1.06.89 1.56 2.34 1.11 2.91.85.09-.66.35-1.11.63-1.37-2.22-.26-4.56-1.14-4.56-5.06 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.7 0 0 .84-.28 2.75 1.05A9.3 9.3 0 0 1 12 6.84c.85 0 1.71.12 2.51.34 1.91-1.33 2.75-1.05 2.75-1.05.55 1.4.2 2.44.1 2.7.64.72 1.03 1.63 1.03 2.75 0 3.93-2.35 4.79-4.58 5.05.36.32.68.94.68 1.9 0 1.37-.01 2.47-.01 2.81 0 .27.18.6.69.49A10.02 10.02 0 0 0 22 12.25C22 6.58 17.52 2 12 2Z" />
    </svg>
  )
}

function FigmaIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden>
      <path d="M8.5 3h3.5v6H8.5A3 3 0 0 1 8.5 3Z" fill="#F24E1E" />
      <path d="M12 3h3.5a3 3 0 0 1 0 6H12V3Z" fill="#FF7262" />
      <path d="M12 9h3.5a3 3 0 1 1-3.5 3V9Z" fill="#1ABCFE" />
      <path d="M8.5 9H12v6H8.5a3 3 0 0 1 0-6Z" fill="#A259FF" />
      <path d="M8.5 15H12v3a3 3 0 1 1-3.5-3Z" fill="#0ACF83" />
    </svg>
  )
}
