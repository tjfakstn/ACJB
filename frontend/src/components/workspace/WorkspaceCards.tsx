import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Icon } from '../common/Icon'
import {
  formatDate, formatDateTime, type Invitation, type IssueStatus, type MappingList, type MemberRole, type ProjectDetail,
  type ProjectMember, type ProjectRun,
} from '../../lib/api'

// 작업공간 홈(시안 WS-01·01N·01M·02) 조각들. 수치는 시안 노드 값을 그대로 옮겼다:
// 카드 r12·1px 테두리·안쪽 20, 카드 사이 24, 제목 16/700, 보조 텍스트 12 #5f5f5f.

export const CARD = 'rounded-xl border border-qa-border bg-white'

/** 시안 Role Tag — 역할별 점·글자색(배경은 같은 색 10%) */
const ROLE_TAG: Record<MemberRole, { label: string; dot: string; text: string; bg: string }> = {
  designer: { label: '디자인', dot: 'bg-[#e5489b]', text: 'text-[#c2307a]', bg: 'bg-[#e5489b]/10' },
  frontend: { label: '프론트엔드', dot: 'bg-[#007fff]', text: 'text-[#0066cc]', bg: 'bg-[#007fff]/10' },
  backend: { label: '백엔드', dot: 'bg-[#f08c00]', text: 'text-[#a85a00]', bg: 'bg-[#f08c00]/10' },
  planner: { label: '기획', dot: 'bg-[#965fff]', text: 'text-[#6e3be6]', bg: 'bg-[#965fff]/10' },
}

export function RoleTag({ role }: { role: MemberRole | null }) {
  if (!role) {
    return (
      <span className="flex h-6 flex-none items-center rounded-full border border-qa-border bg-qa-surface px-[9px] text-xs font-medium text-qa-text-sub">
        역할 미지정
      </span>
    )
  }
  const t = ROLE_TAG[role]
  return (
    <span className={`flex h-[22px] flex-none items-center gap-1.5 rounded-full px-2 text-xs font-medium ${t.bg} ${t.text}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${t.dot}`} />
      {t.label}
    </span>
  )
}

/** 시안 Check Circle — 완료(초록 채움+체크), 진행 중(파란 링+점), 대기(회색 링) */
export function CheckCircle({ state, size = 20 }: { state: 'done' | 'current' | 'todo'; size?: 20 | 24 }) {
  const box = size === 24 ? 'h-6 w-6' : 'h-5 w-5'
  if (state === 'done') {
    return (
      <span className={`flex ${box} flex-none items-center justify-center rounded-full border-[1.5px] border-qa-success bg-qa-success/10 text-qa-success`}>
        <Icon name="check" className={size === 24 ? 'h-3.5 w-3.5' : 'h-3 w-3'} />
      </span>
    )
  }
  if (state === 'current') {
    return (
      <span className={`flex ${box} flex-none items-center justify-center rounded-full border-[1.5px] border-qa-primary`}>
        <span className="h-1.5 w-1.5 rounded-full bg-qa-primary" />
      </span>
    )
  }
  return <span className={`${box} flex-none rounded-full border-[1.5px] border-qa-sub`} />
}

/** 시안 버튼 3종 — 채움(primary), 테두리(primary), 글자만(ghost). 아직 화면이 없는 동작은 disabled로 둔다 */
export function SmallButton({ kind, icon, children, onClick, to, disabled, title }: {
  kind: 'fill' | 'ghost'
  icon?: string
  children: ReactNode
  onClick?: () => void
  to?: string
  disabled?: boolean
  title?: string
}) {
  const cls = `inline-flex h-8 flex-none items-center gap-1 whitespace-nowrap rounded-lg px-3 text-xs font-medium disabled:cursor-not-allowed disabled:opacity-40 ${
    kind === 'fill' ? 'bg-qa-primary-hover text-white hover:bg-qa-primary' : 'text-qa-primary-hover hover:bg-qa-primary/10'
  }`
  const inner = <>{icon && <Icon name={icon} className="h-4 w-4" />}{children}</>
  if (to && !disabled) return <Link to={to} className={cls}>{inner}</Link>
  return <button type="button" className={cls} onClick={onClick} disabled={disabled} title={title}>{inner}</button>
}

// ---------- 워크플로 스테퍼 ----------

const STEPS = ['QA 실행', '결과 확인', '담당 팀 배정', '수정 진행', '수정 결과 확인']

export function WorkflowStepper({ current }: { current: number }) {
  return (
    <div className={`${CARD} mt-6 flex h-[46px] items-center overflow-x-auto px-5`}>
      <span className="whitespace-nowrap text-xs font-medium text-qa-text-sub">QA 사이클</span>
      <span className="mx-3 h-4 w-px flex-none bg-qa-border" />
      {STEPS.map((label, i) => {
        const n = i + 1
        const done = n < current
        const isCurrent = n === current
        return (
          <div key={label} className="flex flex-none items-center">
            {i > 0 && <span className={`mx-3 h-0.5 w-8 rounded-sm ${n <= current ? 'bg-qa-success' : 'bg-[#e8e8e8]'}`} />}
            <span className="flex items-center gap-2">
              {done ? (
                <span className="flex h-5 w-5 items-center justify-center rounded-full bg-qa-success text-white"><Icon name="check" className="h-3 w-3" /></span>
              ) : isCurrent ? (
                <span className="flex h-5 w-5 items-center justify-center rounded-full bg-qa-primary text-xs font-medium text-white">{n}</span>
              ) : (
                <span className="flex h-5 w-5 items-center justify-center rounded-full border border-[#e8e8e8] bg-white text-xs font-medium text-qa-text-sub">{n}</span>
              )}
              <span className={`whitespace-nowrap text-xs ${isCurrent ? 'font-medium text-black' : 'text-qa-text-sub'}`}>{label}</span>
              {isCurrent && <span className="whitespace-nowrap rounded-full bg-qa-primary/10 px-2 text-xs font-medium leading-[14px] text-qa-primary">현재 단계</span>}
            </span>
          </div>
        )
      })}
    </div>
  )
}

// ---------- 첫 QA 상태 ----------

export function FirstQaStatus({ detail, openIssues, cta }: { detail: ProjectDetail; openIssues: number; cta: ReactNode }) {
  const done = detail.first_qa_status === 'succeeded'
  return (
    <section className={`${CARD} mt-6 flex min-h-[74px] items-center gap-4 px-6 py-4`}>
      <span className={`flex h-10 w-10 flex-none items-center justify-center rounded-full ${done ? 'bg-qa-success/10 text-qa-success' : 'bg-qa-primary/10 text-qa-primary'}`}>
        <Icon name={done ? 'check' : 'inbox'} className="h-5 w-5" />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <h2 className="text-base font-bold leading-[19px] text-black">
            {done ? '첫 QA 실행 결과 · 최초 검사 완료' : '아직 QA를 실행한 기록이 없습니다.'}
          </h2>
          {done && <span className="rounded-full bg-qa-primary px-2 text-xs font-medium leading-[14px] text-white">첫 QA</span>}
        </div>
        <p className="mt-1 text-xs text-qa-text-sub">
          {done
            ? '이전 실행 기록이 없어 이전 검사 대비 비교 정보는 제공되지 않아요.'
            : '첫 QA를 실행하고 디자인과 구현 결과를 확인해 보세요. 실행 전에 분석 대상 연결과 검증 범위를 확인할 수 있어요.'}
        </p>
      </div>
      {done ? (
        <div className="flex flex-none items-center gap-2">
          <span className="flex h-6 items-center rounded-full border border-qa-border bg-qa-surface px-[11px] text-xs font-medium text-qa-text-sub">이전 실행 기록 없음</span>
          <span className="flex h-[22px] items-center rounded-full bg-qa-warning/10 px-2.5 text-xs font-medium text-black">수정이 필요한 항목 {openIssues}</span>
        </div>
      ) : cta}
    </section>
  )
}

// ---------- 프로젝트 시작하기 ----------

export interface ChecklistItem {
  label: string
  sub: string
  done: boolean
  action?: ReactNode
}

export function GettingStarted({ items }: { items: ChecklistItem[] }) {
  const doneCount = items.filter((i) => i.done).length
  // 시안 WS-01N처럼 아직 안 한 준비 항목 중 첫 번째(첫 QA 실행 제외)를 강조한다
  const highlight = items.findIndex((i, idx) => !i.done && idx < items.length - 1)
  return (
    <section className={`${CARD} p-5`}>
      <div className="flex items-center gap-2">
        <h2 className="text-base font-bold leading-6 text-black">프로젝트 시작하기</h2>
        <span className="flex h-6 items-center gap-1.5 rounded-full bg-qa-primary/10 px-2 text-xs font-medium text-black">
          <span className="h-1.5 w-1.5 rounded-full bg-qa-primary" />
          {doneCount} / {items.length} 완료
        </span>
      </div>
      <p className="mt-4 text-xs leading-[14px] text-qa-text-sub">QA 실행 이력이 없는 새 프로젝트예요. 아래 순서대로 준비하면 첫 디자인 QA를 실행할 수 있어요.</p>
      <ul className="mt-4 overflow-hidden rounded-xl border border-qa-border">
        {items.map((item, idx) => (
          <li key={item.label}
            className={`flex min-h-[60px] items-center gap-3 px-4 py-3.5 ${idx > 0 ? 'border-t border-qa-border' : ''} ${idx === highlight ? 'bg-qa-surface' : ''}`}>
            <CheckCircle state={item.done ? 'done' : idx === highlight ? 'current' : 'todo'} />
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium leading-[14px] text-black">{item.label}</div>
              <div className="mt-0.5 truncate text-xs leading-4 text-qa-text-sub">{item.sub}</div>
            </div>
            {item.action}
          </li>
        ))}
      </ul>
    </section>
  )
}

// ---------- QA Queue ----------

export function QaQueueCard({ counts }: { counts: Record<IssueStatus, number> }) {
  const total = Object.values(counts).reduce((a, b) => a + b, 0)
  const open = total - counts.resolved
  return (
    <section className={`${CARD} p-5`}>
      <div className="flex items-center gap-2">
        <h2 className="text-base font-bold leading-[19px] text-black">QA Queue</h2>
        <span className="rounded-full bg-qa-surface px-2 text-xs font-medium leading-4 text-qa-text-sub">
          {total === 0 ? '등록된 문제 없음' : `${total}건 등록`}
        </span>
      </div>
      <div className="mt-4 flex flex-col items-center rounded-lg bg-qa-surface px-4 py-6 text-center">
        <Icon name="inbox" className="h-7 w-7 text-gray-900" />
        {total === 0 ? (
          <>
            <div className="mt-2 text-sm font-medium leading-[17px] text-black">아직 등록된 QA 문제가 없습니다</div>
            <p className="mt-2 text-xs leading-[14px] text-qa-text-sub">첫 QA를 실행하면 수정이 필요한 항목이 문제로 등록되고, 담당 팀을 지정해 이곳에서 관리할 수 있어요.</p>
          </>
        ) : (
          <>
            <div className="mt-2 text-sm font-medium leading-5 text-black">미해결 {open}건 · 해결 {counts.resolved}건</div>
            <p className="mt-2 text-xs leading-4 text-qa-text-sub">남은 문제는 QA Queue에서 담당 팀과 수정 진행 상태를 확인하세요.</p>
          </>
        )}
      </div>
    </section>
  )
}

// ---------- 프로젝트 정보 ----------

export function ProjectInfoCard({ rows }: { rows: { label: string; value: string }[] }) {
  return (
    <section className={`${CARD} p-5`}>
      <h2 className="text-base font-bold leading-[19px] text-black">프로젝트 정보</h2>
      <dl className="mt-3 flex flex-col gap-3">
        {rows.map((r) => (
          <div key={r.label} className="flex h-8 items-center justify-between gap-4 text-sm">
            <dt className="flex-none text-qa-text-sub">{r.label}</dt>
            <dd className="truncate text-right font-medium text-black">{r.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

// ---------- 프로젝트 멤버 ----------

/** 시안 아바타 이니셜: 한글 이름이면 성 다음 글자(김하늘→하), 아니면 첫 글자 */
function initialOf(name: string): string {
  const trimmed = name.trim()
  if (/^[가-힣]{2,4}$/.test(trimmed)) return trimmed[1]
  return (trimmed[0] ?? '?').toUpperCase()
}

function shortDate(iso: string): string {
  return formatDate(iso).slice(5)
}

export function MembersCard({ projectId, me, ownerId, members, invitations, onInvite }: {
  projectId: string
  me: string
  ownerId: string
  members: ProjectMember[]
  invitations: Invitation[]
  onInvite: () => void
}) {
  // 소유자를 맨 위에, 나머지는 서버 순서(name asc) 그대로
  const sorted = [...members].sort((a, b) => Number(b.id === ownerId) - Number(a.id === ownerId))
  const pending = invitations.length
  const total = members.length + pending
  const alone = members.length <= 1 && pending === 0
  return (
    <section className={`${CARD} p-5`}>
      <div className="flex h-8 items-center gap-2">
        <h2 className="flex-none text-base font-bold text-black">프로젝트 멤버</h2>
        <span className="min-w-0 flex-1 truncate text-xs text-qa-text-sub">
          {pending > 0 ? `${total}명 · 참여 ${members.length} · 대기 ${pending}` : `${members.length}명`}
        </span>
        <SmallButton kind="ghost" to={`/app/projects/${projectId}/members`}>멤버 관리</SmallButton>
      </div>
      <ul className="mt-3 flex flex-col gap-3">
        {sorted.map((m) => {
          const isOwner = m.id === ownerId
          return (
            <li key={m.id} className="flex h-8 items-center gap-2.5">
              <span className="flex h-8 w-8 flex-none items-center justify-center rounded-full bg-qa-primary/10 text-xs font-medium text-qa-primary">
                {initialOf(m.name)}
              </span>
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium leading-[14px] text-black">{m.name}{m.id === me ? ' (나)' : ''}</div>
                <div className="mt-0.5 truncate text-xs leading-[14px] text-qa-text-sub">
                  {isOwner ? `소유자${m.role ? '' : ' · 프로젝트 역할 미지정'}` : '참여 완료'}
                </div>
              </div>
              <RoleTag role={m.role} />
            </li>
          )
        })}
        {invitations.map((inv) => (
          <li key={inv.id} className="flex h-8 items-center gap-2.5">
            <span className="flex h-8 w-8 flex-none items-center justify-center rounded-full border border-qa-border bg-qa-surface text-gray-900">
              <Icon name="mail" className="h-4 w-4" />
            </span>
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-medium leading-[14px] text-black">{inv.email}</div>
              <div className="mt-0.5 truncate text-xs leading-4 text-qa-text-sub">초대 대기 중 · {shortDate(inv.expires_at)} 만료 예정</div>
            </div>
          </li>
        ))}
      </ul>
      {alone ? (
        <div className="mt-3 flex flex-col items-center rounded-lg bg-qa-surface px-4 py-4 text-center">
          <div className="text-sm font-medium leading-[14px] text-black">아직 초대한 멤버가 없어요</div>
          <p className="mt-2 text-xs leading-[14px] text-qa-text-sub">이메일로 팀원을 초대하세요. 초대받은 팀원이 수락할 때 기획·디자인·프론트엔드·백엔드 중 역할을 골라요.</p>
          <div className="mt-2"><SmallButton kind="fill" icon="user-plus" onClick={onInvite}>멤버 초대하기</SmallButton></div>
        </div>
      ) : (
        <button type="button" onClick={onInvite}
          className="mt-3 flex h-10 w-full items-center justify-center gap-2 rounded-lg border border-qa-primary bg-white text-sm font-medium text-qa-primary-hover hover:bg-qa-primary/5">
          <Icon name="user-plus" className="h-5 w-5" />
          멤버 초대
        </button>
      )}
    </section>
  )
}

// ---------- 연결 상태 ----------

function ConnectionRow({ icon, title, meta, connected }: { icon: string; title: string; meta: string; connected: boolean }) {
  return (
    <div className="flex min-h-[66px] items-center gap-3 rounded-lg border border-qa-border px-4 py-3">
      <span className="flex h-10 w-10 flex-none items-center justify-center rounded-lg bg-qa-surface text-gray-900">
        <Icon name={icon} className="h-5 w-5" />
      </span>
      <div className="min-w-0 flex-1">
        <div className="text-sm font-medium leading-5 text-black">{title}</div>
        <div className="mt-0.5 break-words text-xs leading-4 text-qa-text-sub">{meta}</div>
      </div>
      <span className="flex-none text-xs font-medium text-black">{connected ? '연결됨' : '연결 전'}</span>
      {connected ? <CheckCircle state="done" size={24} /> : <CheckCircle state="todo" size={24} />}
    </div>
  )
}

export function ConnectionsCard({ detail }: { detail: ProjectDetail }) {
  const { figma, github } = detail
  const last = [figma?.connected_at, github?.connected_at].filter(Boolean).sort().pop()
  return (
    <section className={`${CARD} p-5`}>
      <div className="flex h-8 items-center justify-between">
        <h2 className="text-base font-bold leading-6 text-black">연결 상태</h2>
        <SmallButton kind="ghost" disabled title="연결 관리 화면은 준비 중이에요">연결 관리</SmallButton>
      </div>
      <div className="mt-4 flex flex-col gap-4">
        <ConnectionRow icon="figma" title="Figma 연결" connected={!!figma}
          meta={figma ? `${figma.file_name} › ${figma.page_name}` : '연결된 Figma 페이지가 없어요'} />
        <ConnectionRow icon="github" title="GitHub 연결" connected={!!github}
          meta={github ? `${github.repo} · ${github.branch}` : '연결된 GitHub 저장소가 없어요'} />
      </div>
      <p className="mt-4 text-xs text-qa-text-sub">
        {figma && github && last ? `마지막 확인 ${formatDate(last)} · 접근 권한 확인됨` : 'QA 실행 전에 Figma·GitHub 연결이 필요해요.'}
      </p>
    </section>
  )
}

// ---------- 최근 AI 분석 (시안 WS-01 "AI Analysis") ----------

const RUN_CHIP: Record<string, { label: string; dot: string; bg: string }> = {
  none: { label: '분석 전', dot: 'bg-qa-sub', bg: 'bg-qa-surface' },
  queued: { label: '대기 중', dot: 'bg-qa-primary', bg: 'bg-qa-primary/10' },
  running: { label: '진행 중', dot: 'bg-qa-primary', bg: 'bg-qa-primary/10' },
  succeeded: { label: '완료', dot: 'bg-qa-success', bg: 'bg-qa-success/10' },
  failed: { label: '실패', dot: 'bg-qa-warning', bg: 'bg-qa-warning/10' },
}

export function AnalysisCard({ run, mapping, connected, isOwner, onOpen }: {
  run: ProjectRun | null
  mapping: MappingList | null
  connected: boolean
  isOwner: boolean
  /** 분석 창 열기 — 시작·다시 시도·진행 상태 보기 */
  onOpen: () => void
}) {
  const status = run?.status ?? 'none'
  const chip = RUN_CHIP[status]
  const frames = mapping?.items.length ?? 0
  const nodes = mapping?.items.reduce((n, f) => n + f.nodes.length, 0) ?? 0
  const steps = status === 'succeeded'
    ? [
        { title: '디자인 구조 분석', desc: `화면 ${frames}개에 구현 파일 연결` },
        { title: 'UI 코드 분석', desc: `요소 ${nodes}개에 컴포넌트 연결${mapping?.commit_sha ? ` · 커밋 ${mapping.commit_sha.slice(0, 7)}` : ''}` },
      ]
    : [
        { title: '디자인 구조 분석', desc: 'Figma 화면과 요소 읽기' },
        { title: 'UI 코드 분석', desc: '화면을 그리는 파일·컴포넌트 찾기' },
      ]
  return (
    <section className={`${CARD} p-5`}>
      <div className="flex h-6 items-center gap-2">
        <h2 className="text-base font-bold leading-6 text-black">최근 AI 분석</h2>
        <span className={`flex h-6 items-center gap-1.5 rounded-full px-2 text-xs font-medium text-black ${chip.bg}`}>
          <span className={`h-1.5 w-1.5 rounded-full ${chip.dot}`} />{chip.label}
        </span>
      </div>
      <ul className="mt-4 flex flex-col gap-3">
        {steps.map((s) => (
          <li key={s.title} className="flex items-start gap-3">
            <span className="mt-0.5 flex">
              <CheckCircle state={status === 'succeeded' ? 'done' : status === 'running' || status === 'queued' ? 'current' : 'todo'} />
            </span>
            <div className="min-w-0">
              <div className="text-sm font-medium leading-5 text-black">{s.title}</div>
              <div className="text-xs leading-4 text-qa-text-sub">{s.desc}</div>
            </div>
          </li>
        ))}
      </ul>
      {status === 'failed' && run?.error_message && (
        <p className="mt-3 rounded-lg bg-qa-warning/10 px-3 py-2 text-xs leading-4 text-black">{run.error_message}</p>
      )}
      {status === 'succeeded' && run?.dropped_count ? (
        <p className="mt-3 text-xs leading-4 text-qa-text-sub">근거가 없어 버린 짝 {run.dropped_count}개</p>
      ) : null}
      <div className="mt-4">
        {status === 'running' || status === 'queued' ? (
          <SmallButton kind="ghost" icon="arrow-right" onClick={onOpen}>분석 진행 상태 보기</SmallButton>
        ) : status === 'succeeded' ? (
          <span className="text-xs text-qa-text-sub">{run?.finished_at ? `${formatDateTime(run.finished_at)} 완료` : ''}</span>
        ) : !connected ? (
          <span className="text-xs text-qa-text-sub">Figma·GitHub를 모두 연결하면 비교 분석을 시작할 수 있어요.</span>
        ) : isOwner ? (
          <SmallButton kind="fill" icon={status === 'failed' ? 'refresh' : 'arrow-right'} onClick={onOpen}>
            {status === 'failed' ? '다시 시도' : '비교 분석 시작'}
          </SmallButton>
        ) : (
          <span className="text-xs text-qa-text-sub">비교 분석은 프로젝트 소유자가 시작해요.</span>
        )}
      </div>
    </section>
  )
}

// ---------- 디자인-코드 매핑 (시안 WS-01 "Mapping") ----------

export function MappingCard({ projectId, mapping }: { projectId: string; mapping: MappingList }) {
  const frames = mapping.items.length
  const nodes = mapping.items.reduce((n, f) => n + f.nodes.length, 0)
  return (
    <section className={`${CARD} p-5`}>
      <h2 className="text-base font-bold leading-6 text-black">디자인-코드 매핑</h2>
      <dl className="mt-4 flex flex-col">
        {[
          { label: '매핑된 화면', value: `${frames}개` },
          { label: '매핑된 요소', value: `${nodes}개` },
          { label: '기준 시점', value: mapping.run?.finished_at ? formatDate(mapping.run.finished_at) : '-' },
        ].map((r) => (
          <div key={r.label} className="flex h-9 items-center justify-between text-sm">
            <dt className="text-qa-text-sub">{r.label}</dt>
            <dd className="font-medium text-black">{r.value}</dd>
          </div>
        ))}
      </dl>
      <Link to={`/app/projects/${projectId}/mapping`}
        className="mt-4 flex h-10 w-full items-center justify-center gap-2 rounded-lg border border-qa-primary bg-white text-sm font-medium text-qa-primary-hover hover:bg-qa-primary/5">
        <Icon name="git-compare" className="h-5 w-5" />
        디자인-코드 매핑 보기
      </Link>
    </section>
  )
}
