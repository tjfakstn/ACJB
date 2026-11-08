import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  ApiError, assignTeam, PROPERTY_LABELS, TEAM_LABELS, type ProjectMember, type QaIssue, type Team,
} from '../../lib/api'
import { Icon } from '../common/Icon'
import { KeyValue, OUTLINE_BTN, PRIMARY_BTN, ProgressChip, TeamAvatar, TeamTag, Verdict, issueParts, issueTitle } from './QaParts'

// QA 결과 오른쪽 패널 — 문제 근거와 담당 팀 배정(시안 ISS-01·ASN-01~04, SPEC AC3).
// 담당은 사람이 아니라 팀(역할)이다. 지정·변경은 프로젝트 멤버 누구나 할 수 있고, 그 역할 멤버 각각에게 알림이 간다.
// 시안의 "배정 일시"는 서버가 기록하지 않아 뺐다(상태 이력에는 상태 변경만 남는다).

const TEAMS: { team: Team; desc: string }[] = [
  { team: 'planner', desc: '요구사항·정책·사용자 흐름·기능 명세 수정' },
  { team: 'designer', desc: 'Figma 디자인 기준값 수정' },
  { team: 'frontend', desc: '컴포넌트 코드·스타일 구현 수정' },
  { team: 'backend', desc: '데이터·API 응답으로 인한 표시 오류 수정' },
]

export function AssignPanel({ issue, projectId, members, onClose, onAssigned }: {
  issue: QaIssue
  projectId: string
  members: ProjectMember[]
  onClose: () => void
  onAssigned: (updated: QaIssue, previous: Team | null) => void
}) {
  const [editing, setEditing] = useState(issue.assigned_team === null)
  const [choice, setChoice] = useState<Team | null>(issue.assigned_team)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 다른 문제를 고르면 그 문제의 상태로 다시 시작한다
  useEffect(() => {
    setEditing(issue.assigned_team === null)
    setChoice(issue.assigned_team)
    setError(null)
  }, [issue.id, issue.assigned_team])

  const m = issue.style_mismatch
  const parts = issueParts(issue)
  const changing = issue.assigned_team !== null

  const save = async () => {
    if (!choice) return
    setSaving(true)
    setError(null)
    try {
      const updated = await assignTeam(projectId, issue.id, choice)
      setEditing(false)
      onAssigned(updated, issue.assigned_team)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '담당 팀을 저장하지 못했어요.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <aside className="rounded-xl border border-qa-primary/50 bg-white" aria-label="문제 상세">
      <div className="border-b border-qa-border p-5">
        <div className="flex items-start justify-between gap-2">
          <Verdict pass={issue.status === 'resolved'} />
          <button type="button" onClick={onClose} aria-label="패널 닫기" className="rounded p-1 text-qa-text-sub hover:bg-qa-surface">
            <Icon name="x" className="h-4 w-4" />
          </button>
        </div>
        <h2 className="mt-2 text-base font-bold leading-6 text-black">{issueTitle(issue)}</h2>
        <div className="mt-1 text-xs text-qa-text-sub">#{issue.id} · {parts.screen}</div>
        <Link to={`/app/projects/${projectId}/issues/${issue.id}`} className="mt-2 inline-flex items-center gap-0.5 text-xs font-medium text-qa-primary-hover hover:underline">
          QA 이슈 상세에서 전체 보기<Icon name="chevron-right" className="h-3.5 w-3.5" />
        </Link>
      </div>

      <div className="border-b border-qa-border p-5">
        <div className="text-xs font-medium text-qa-text-sub">불일치 내용</div>
        <div className="mt-2 rounded-lg bg-qa-surface px-4 py-1">
          {m ? (
            <KeyValue rows={[
              { label: '불일치 속성', value: PROPERTY_LABELS[m.property] },
              { label: '디자인 기대값', value: m.expected_value },
              { label: '구현 실제값', value: <span className="text-qa-warning">{m.actual_value}</span> },
            ]} />
          ) : <p className="py-2 text-sm text-black">{issue.description}</p>}
        </div>
        {issue.description.startsWith('[시연용]') && (
          <p className="mt-2 text-xs text-qa-text-sub">개발용 가짜 비교기가 만든 시연용 값이에요(Claude API 연결 전).</p>
        )}
      </div>

      <div className="p-5">
        <div className="flex items-center justify-between gap-2">
          <h3 className="text-sm font-bold text-black">수정 담당 팀 <span className="text-qa-warning">*</span></h3>
          {issue.assigned_team && !editing
            ? <span className="inline-flex h-[22px] items-center gap-1 rounded-full bg-qa-success/10 px-2 text-xs font-medium text-qa-success"><Icon name="check" className="h-3 w-3" />배정 완료</span>
            : <span className="inline-flex h-[22px] items-center rounded-full bg-qa-warning/10 px-2 text-xs font-medium text-[#c8161a]">{changing ? '변경 중' : '미배정'}</span>}
        </div>

        {editing ? (
          <>
            <p className="mt-1 text-xs leading-4 text-qa-text-sub">
              AI 추천 없이 직접 선택해요. 그 팀 역할을 가진 멤버 모두에게 알림이 가고, 그 멤버들이 진행 상태를 바꿀 수 있어요.
            </p>
            <fieldset className="mt-3 flex flex-col gap-2">
              <legend className="sr-only">수정 담당 팀</legend>
              {TEAMS.map(({ team, desc }) => {
                const count = members.filter((mem) => mem.role === team).length
                const selected = choice === team
                return (
                  <label key={team} className={`flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-2.5 ${selected ? 'border-qa-primary bg-qa-primary/5' : 'border-qa-border hover:bg-qa-surface'}`}>
                    <input type="radio" name="team" value={team} checked={selected} onChange={() => setChoice(team)} className="h-4 w-4 accent-qa-primary-hover" />
                    <TeamAvatar team={team} />
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-1.5 text-sm font-medium text-black">
                        {TEAM_LABELS[team]}
                        {issue.assigned_team === team && <span className="text-xs font-normal text-qa-text-sub">(현재)</span>}
                      </span>
                      <span className="block text-xs text-qa-text-sub">{desc}</span>
                      <span className={`block text-xs ${count === 0 ? 'text-[#a85a00]' : 'text-qa-text-sub'}`}>
                        {count === 0 ? '이 역할 멤버가 없어 알림을 받을 사람이 없어요' : `이 역할 멤버 ${count}명`}
                      </span>
                    </span>
                  </label>
                )
              })}
            </fieldset>
            {!choice && (
              <p className="mt-2 flex items-center gap-1 text-xs text-qa-text-sub"><Icon name="info" className="h-3.5 w-3.5" />담당 팀을 선택하면 배정 버튼이 활성화돼요.</p>
            )}
            {error && <p role="alert" className="mt-2 text-xs text-qa-warning">{error}</p>}
            <div className="mt-3 flex gap-2">
              {changing && (
                <button type="button" className={`${OUTLINE_BTN} flex-1`} onClick={() => { setEditing(false); setChoice(issue.assigned_team) }}>취소</button>
              )}
              <button type="button" className={`${PRIMARY_BTN} flex-1`} onClick={save}
                disabled={!choice || saving || choice === issue.assigned_team}>
                {changing ? '변경 저장' : '담당 팀 배정'}
              </button>
            </div>
          </>
        ) : (
          <>
            <p className="mt-1 text-xs leading-4 text-qa-text-sub">담당 팀이 확정되었어요. 실제 수정 작업은 QA Queue에서 진행 상태로 관리해요.</p>
            <div className="mt-3 rounded-lg bg-qa-surface px-4 py-1">
              <KeyValue rows={[
                { label: '담당 팀', value: <TeamTag team={issue.assigned_team} /> },
                { label: '배정 방식', value: '멤버가 직접 지정' },
              ]} />
            </div>
            <button type="button" className={`${OUTLINE_BTN} mt-3 w-full`} onClick={() => setEditing(true)}>담당 팀 변경</button>
          </>
        )}
      </div>

      <div className="flex items-center justify-between gap-2 border-t border-qa-border px-5 py-3">
        <div>
          <div className="text-xs font-medium text-black">수정 진행 상태</div>
          <div className="text-xs text-qa-text-sub">배정 상태와 별개로 QA Queue에서 관리돼요.</div>
        </div>
        <ProgressChip status={issue.status} />
      </div>
    </aside>
  )
}
