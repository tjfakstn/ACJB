import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, getAnalysis, needsFigmaToken, startAnalysis, type ProjectRun } from '../../lib/api'
import { Icon } from '../common/Icon'
import { CloseButton } from './WizardParts'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from './wizardStyles'

// 프로젝트 시작 — 비교 분석(시안 CON-06 진행 중 · CON-06F 실패 · CON-06R 다시 시도 중 · CON-06F2 재실패, SPEC AC22).
// 열리면 분석을 시작하고(이미 진행 중이면 그 실행을 따라간다) 2초마다 상태를 본다. 성공하면 onDone.
// 시안과 다른 점(서버가 주지 않는 값은 지어내지 않는다):
//  - 서버는 단계별 진행을 알려 주지 않아 "n / 3 단계" 진행률 대신 움직이는 막대를 쓰고, 단계 목록은 하는 일만 보여 준다
//  - "변경 이력 분석"(커밋·PR) 단계는 비교 분석 범위가 아니라 뺐다(N13)
//  - 실패 원인은 서버가 준 문구 하나뿐이라 "실패한 단계·오류 위치" 줄은 없다
const POLL_MS = 2000

const STEPS = [
  { title: '디자인 구조 분석', body: '연결한 Figma 페이지·섹션의 화면과 요소를 읽어요' },
  { title: 'UI 코드 분석', body: 'GitHub 브랜치의 UI 코드에서 각 화면을 그리는 파일과 컴포넌트를 찾아요' },
]

type View =
  | { kind: 'starting' }
  | { kind: 'running'; run: ProjectRun }
  | { kind: 'failed'; message: string }

interface AnalysisStepProps {
  projectId: string
  projectName: string
  /** 마법사 안이면 단계 표시를 보여 준다 */
  inWizard: boolean
  onClose: () => void
  /** [연결 정보 확인]·[Figma 토큰 넣기] — 마법사면 연결 단계로, 작업공간이면 연결 관리로. needsToken이면 토큰 입력칸을 펼친다(2026-11-12) */
  onCheckConnection: (needsToken: boolean) => void
  onDone: () => void
}

export function AnalysisStep({ projectId, projectName, inWizard, onClose, onCheckConnection, onDone }: AnalysisStepProps) {
  const [view, setView] = useState<View>({ kind: 'starting' })
  /** 이 창에서 실패한 횟수 — 두 번째부터 "다시 시도했지만…"(CON-06F2) */
  const [failures, setFailures] = useState(0)
  const [retrying, setRetrying] = useState(false)
  const timer = useRef<number | null>(null)
  /** 실패를 실행 id로 한 번만 센다 — 개발 모드에서 화면이 두 번 붙어 같은 실행을 두 번 따라가도 한 번이다 */
  const failedRuns = useRef(new Set<string>())
  const started = useRef(false)
  /** 창이 닫힌 뒤에는 상태 확인을 더 하지 않는다 */
  const alive = useRef(true)

  const stopPolling = () => {
    if (timer.current !== null) window.clearTimeout(timer.current)
    timer.current = null
  }

  const follow = useCallback((run: ProjectRun) => {
    if (!alive.current) return
    if (run.status === 'succeeded') {
      onDone()
      return
    }
    if (run.status === 'failed') {
      if (!failedRuns.current.has(run.id)) {
        failedRuns.current.add(run.id)
        setFailures((n) => n + 1)
      }
      setRetrying(false)
      setView({ kind: 'failed', message: run.error_message ?? '비교 분석을 마치지 못했어요.' })
      return
    }
    setView({ kind: 'running', run })
    timer.current = window.setTimeout(() => {
      getAnalysis(projectId)
        .then((r) => (r ? follow(r) : setView({ kind: 'failed', message: '분석 상태를 찾을 수 없어요.' })))
        .catch(() => { timer.current = window.setTimeout(() => follow(run), POLL_MS) })
    }, POLL_MS)
  }, [projectId, onDone])

  const begin = useCallback(async () => {
    stopPolling()
    try {
      follow(await startAnalysis(projectId))
    } catch (e) {
      // 이미 진행 중이거나 끝났으면(409) 최근 실행을 따라간다
      const latest = e instanceof ApiError && e.status === 409 ? await getAnalysis(projectId).catch(() => null) : null
      if (latest && latest.status !== 'failed') {
        follow(latest)
        return
      }
      setRetrying(false)
      setFailures((n) => n + 1)
      setView({ kind: 'failed', message: e instanceof ApiError ? e.message : '비교 분석을 시작하지 못했어요.' })
    }
  }, [projectId, follow])

  useEffect(() => {
    alive.current = true
    if (!started.current) {
      started.current = true
      void begin()
    }
    return () => {
      alive.current = false
      stopPolling()
    }
    // 열릴 때 한 번만 시작한다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const retry = () => {
    setRetrying(true)
    setView({ kind: 'starting' })
    void begin()
  }

  const failed = view.kind === 'failed'
  const secondFailure = failed && failures >= 2
  // 서버 계정이 이 Figma 파일을 읽지 못해 실패했으면 다시 시도해도 같다 — 본인 토큰 넣기를 먼저 권한다
  const tokenFix = failed && needsFigmaToken(view.message)

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-start justify-between gap-4">
        <div className="flex flex-col gap-3">
          {inWizard && <DoneStepper />}
          <div>
            <h2 id="create-project-title" className="text-xl font-bold leading-7 text-black">프로젝트 시작 — 비교 분석</h2>
            <p className="mt-1 text-sm leading-5 text-qa-text-sub">
              {projectName} · {failed ? '프로젝트 연결은 완료되었지만 비교 분석을 마치지 못했어요.'
                : retrying ? '같은 연결 정보로 비교 분석을 다시 시작했어요.' : '연결이 완료되어 비교 분석을 시작했어요.'}
            </p>
          </div>
        </div>
        <CloseButton onClick={onClose} />
      </div>

      {!failed ? (
        <div className="flex flex-col gap-5">
          <div className="flex flex-col items-center gap-5 pt-4 text-center">
            <span className="h-8 w-8 animate-spin rounded-full border-[3px] border-[#e8e8e8] border-t-qa-primary-hover" aria-hidden="true" />
            <div>
              <div className="text-base font-bold leading-6 text-black">{retrying ? '비교 분석을 다시 진행하고 있어요' : '비교 분석을 진행하고 있어요'}</div>
              <div className="mt-1 text-sm leading-5 text-qa-text-sub">
                Figma 디자인과 GitHub 코드를 분석 중입니다. {inWizard ? '완료되면 작업공간으로 이동합니다.' : '완료되면 결과가 작업공간에 표시됩니다.'}
              </div>
            </div>
          </div>
          <div>
            <div className="flex justify-between text-xs leading-4 text-qa-text-sub">
              <span className="font-medium">분석 진행률</span>
              <span>{view.kind === 'running' && view.run.status === 'running' ? '분석 중' : '대기 중'}</span>
            </div>
            <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-qa-border" role="progressbar" aria-label="비교 분석 진행 중">
              <div className="h-full w-1/3 animate-[qa-indeterminate_1.4s_ease-in-out_infinite] rounded-full bg-qa-primary-hover" />
            </div>
          </div>
          <StepList state="running" />
          <p className="text-xs leading-4 text-qa-text-sub">창을 닫아도 분석은 계속돼요. 결과는 작업공간의 "최근 AI 분석"에서 볼 수 있어요.</p>
        </div>
      ) : (
        <div className="flex flex-col gap-5">
          <div className="flex flex-col items-center gap-3 pt-2 text-center">
            <span className="flex h-12 w-12 items-center justify-center rounded-full bg-qa-warning/10 text-qa-warning">
              <Icon name="alert-circle" className="h-6 w-6" />
            </span>
            {secondFailure && (
              <span className="rounded-full bg-qa-warning/10 px-2 py-0.5 text-xs font-medium text-qa-warning">다시 시도 · {failures}번째 실행 실패</span>
            )}
            <div>
              <div className="text-base font-bold leading-6 text-black">{secondFailure ? '다시 시도했지만 분석을 완료하지 못했어요' : '비교 분석을 완료하지 못했어요'}</div>
              <div className="mt-1 text-sm leading-5 text-qa-text-sub">
                {secondFailure ? '같은 문제가 반복되면 연결 정보를 먼저 확인해 주세요.' : '아래 원인을 확인한 뒤 다시 시도해 주세요.'}
              </div>
            </div>
          </div>
          <div>
            <div className="flex justify-between text-xs leading-4">
              <span className="font-medium text-qa-text-sub">분석 진행률</span>
              <span className="text-qa-warning">중단됨</span>
            </div>
            <div className="mt-2 h-1.5 rounded-full bg-qa-border"><div className="h-full w-1/3 rounded-full bg-qa-warning" /></div>
          </div>
          <StepList state="failed" />
          <div className="rounded-xl border border-qa-border px-4 py-4">
            <div className="text-xs font-medium text-black">실패 원인</div>
            <p className="mt-2 text-sm font-medium leading-5 text-black">{view.message}</p>
            <div className="my-3 h-px bg-qa-border" />
            <div className="text-xs text-qa-text-sub">이렇게 해결해 보세요</div>
            <ol className="mt-2 flex flex-col gap-2 text-xs leading-4 text-black">
              {(tokenFix ? ['[Figma 토큰 넣기]를 눌러 연결 정보에서 본인 Figma 토큰(File content: Read only)을 넣어 주세요.',
                '토큰을 저장한 뒤 다시 시도하면 이 프로젝트의 Figma는 그 토큰으로 읽어요.'] : ['Figma 파일 보기 권한과 GitHub 저장소 공개 여부가 바뀌지 않았는지 확인해 주세요.',
                '링크나 분석할 브랜치가 바뀌었다면 연결 정보에서 수정해 주세요.',
                '일시적인 문제일 수 있어요. 잠시 후 다시 시도해 주세요.']).map((t, i) => (
                <li key={t} className="flex items-start gap-2">
                  <span className="flex h-4 w-4 flex-none items-center justify-center rounded-full bg-qa-primary/10 text-[10px] font-medium text-qa-primary">{i + 1}</span>{t}
                </li>
              ))}
            </ol>
          </div>
          <div className="flex flex-col gap-2 rounded-xl bg-qa-primary/10 px-4 py-3 text-xs leading-4 text-black">
            <span className="flex items-center gap-2"><Icon name="circle-check" className="h-4 w-4 flex-none text-qa-success" />프로젝트 정보와 GitHub·Figma 연결 정보는 그대로 저장되어 있어요.</span>
            <span className="flex items-center gap-2"><span className="h-4 w-4 flex-none rounded-full border border-qa-sub" />이번 분석은 완료로 기록되지 않았어요. 다시 시도하면 분석을 처음부터 진행합니다.</span>
          </div>
          <div className="h-px bg-qa-border" />
          <div className="flex flex-wrap items-center justify-between gap-3">
            <button type="button" onClick={onClose} className="h-10 px-2 text-sm font-medium text-qa-primary-hover hover:underline">
              {inWizard ? '프로젝트 목록으로' : '닫기'}
            </button>
            <div className="flex gap-2">
              {tokenFix ? (
                <>
                  <button type="button" onClick={retry} className={`${SECONDARY_BUTTON} inline-flex items-center gap-2`}><Icon name="refresh" className="h-4 w-4" />다시 시도</button>
                  <button type="button" onClick={() => onCheckConnection(true)} className={PRIMARY_BUTTON}>Figma 토큰 넣기</button>
                </>
              ) : secondFailure ? (
                <>
                  <button type="button" onClick={retry} className={`${SECONDARY_BUTTON} inline-flex items-center gap-2`}><Icon name="refresh" className="h-4 w-4" />다시 시도</button>
                  <button type="button" onClick={() => onCheckConnection(false)} className={PRIMARY_BUTTON}>연결 정보 확인</button>
                </>
              ) : (
                <>
                  <button type="button" onClick={() => onCheckConnection(false)} className={SECONDARY_BUTTON}>연결 정보 확인</button>
                  <button type="button" onClick={retry} className={PRIMARY_BUTTON}><Icon name="refresh" className="h-4 w-4" />다시 시도</button>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

/** 시안 CON-06의 단계 표시 — 프로젝트 정보·Figma·Git 연결 둘 다 끝난 상태 */
function DoneStepper() {
  return (
    <ol className="flex items-center gap-2" aria-label="진행 단계">
      {['프로젝트 정보', 'Figma·Git 연결'].map((label, i) => (
        <li key={label} className="flex items-center gap-2">
          {i > 0 && <span className="h-0.5 w-8 bg-qa-success" aria-hidden="true" />}
          <span className="flex h-5 w-5 items-center justify-center rounded-full border border-qa-success bg-qa-success/10 text-qa-success"><Icon name="check" className="h-3 w-3" /></span>
          <span className="text-xs font-medium text-black">{label}</span>
        </li>
      ))}
    </ol>
  )
}

function StepList({ state }: { state: 'running' | 'failed' }) {
  return (
    <ul className="flex flex-col gap-3 rounded-xl bg-qa-surface px-4 py-4">
      {STEPS.map((s) => (
        <li key={s.title} className="flex items-start gap-3">
          {state === 'running' ? (
            <span className="mt-0.5 flex h-5 w-5 flex-none items-center justify-center rounded-full border border-qa-primary"><span className="h-1.5 w-1.5 rounded-full bg-qa-primary" /></span>
          ) : (
            <span className="mt-0.5 h-5 w-5 flex-none rounded-full border border-qa-sub" />
          )}
          <div className="min-w-0 flex-1">
            <div className="text-sm font-medium leading-5 text-black">{s.title}</div>
            <div className="text-xs leading-4 text-qa-text-sub">{s.body}</div>
          </div>
          <span className={`flex-none text-xs font-medium ${state === 'running' ? 'text-qa-primary-hover' : 'text-qa-text-sub'}`}>
            {state === 'running' ? '진행 중' : '완료 안 됨'}
          </span>
        </li>
      ))}
    </ul>
  )
}
