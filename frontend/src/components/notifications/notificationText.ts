import { PROPERTY_LABELS, manualQaId, type AppNotification, type NotificationIssue } from '../../lib/api'
import { ROLE_TAG } from '../common/roles'
import { PROGRESS } from '../qa/QaParts'

// 알림 한 건을 시안 NTF/Item 문구로 바꾼다 — 유형 6개(재검증 요청·새 QA 문제·Re-QA 결과·수정 완료·담당 팀 배정·QA 실행 오류)

export type NotificationKind = 'recheck_request' | 'new_issue' | 'reqa_pass' | 'reqa_fail' | 'fixed' | 'assigned' | 'run_failed'

export interface NotificationView {
  kind: NotificationKind
  label: string
  title: string
  detail: string
  /** 누르면 갈 화면 */
  href: string
}

export const KIND_STYLE: Record<NotificationKind, { icon: string; text: string; box: string }> = {
  recheck_request: { icon: 'refresh', text: 'text-qa-primary', box: 'bg-qa-primary/10 text-qa-primary' },
  new_issue: { icon: 'list-checks', text: 'text-qa-primary', box: 'bg-qa-primary/10 text-qa-primary' },
  reqa_pass: { icon: 'shield-check', text: 'text-qa-success', box: 'bg-qa-success/10 text-qa-success' },
  reqa_fail: { icon: 'shield-check', text: 'text-qa-warning', box: 'bg-qa-warning/10 text-qa-warning' },
  fixed: { icon: 'git-compare', text: 'text-qa-success', box: 'bg-qa-success/10 text-qa-success' },
  assigned: { icon: 'circle-user', text: 'text-qa-primary', box: 'bg-qa-primary/10 text-qa-primary' },
  run_failed: { icon: 'alert-circle', text: 'text-qa-warning', box: 'bg-qa-warning/10 text-qa-warning' },
}

const RUN_LABEL = { analysis: '비교 분석', first_qa: '첫 QA', re_qa: 'Re-QA' } as const

/** 이슈 목록과 같은 규칙 — 수동 QA는 QA-M###, 나머지는 #id */
function itemId(issue: NotificationIssue): string {
  return issue.pin_number != null ? manualQaId(issue.pin_number) : `#${issue.id}`
}

/** 이슈 목록의 issueTitle과 같은 규칙(수동 QA 제목 → 스타일 차이 → 설명) */
function issueTitle(issue: NotificationIssue): string {
  if (issue.manual_title) return issue.manual_title
  if (issue.mismatch_property) {
    const i = issue.screen_location.lastIndexOf(' / ')
    const element = i < 0 ? issue.screen_location : issue.screen_location.slice(i + 3)
    return `${element} — ${PROPERTY_LABELS[issue.mismatch_property]} 불일치`
  }
  return issue.description
}

function teamName(issue: NotificationIssue): string {
  return issue.assigned_team ? `${ROLE_TAG[issue.assigned_team].label}팀` : '담당 팀'
}

export function describe(n: AppNotification, projectId: string): NotificationView {
  const base = `/app/projects/${projectId}`
  if (n.event === 'run_failed' || !n.issue) {
    const kind = n.run?.kind ?? 'first_qa'
    return {
      kind: 'run_failed',
      label: 'QA 실행 오류',
      title: `${RUN_LABEL[kind]} 실행을 완료하지 못했어요`,
      detail: `${n.run?.error_message ?? '실행 중 오류가 발생했어요'} · 다시 시도가 필요해요`,
      // 비교 분석은 작업공간 홈, 첫 QA·Re-QA는 디자인 QA 화면에서 다시 실행한다
      href: kind === 'analysis' ? base : `${base}/qa`,
    }
  }
  const issue = n.issue
  const id = itemId(issue)
  const title = issueTitle(issue)
  const manual = issue.source === 'manual'
  const issueHref = `${base}/issues/${issue.id}`
  switch (n.event) {
    case 'issue_created':
      if (n.cause === 'team_assigned') {
        return { kind: 'assigned', label: '담당 팀 배정', title: `${id}이 ${teamName(issue)}에 배정됐어요`, detail: `${title} · ${PROGRESS[issue.status].label}`, href: `${base}/queue` }
      }
      return {
        kind: 'new_issue', label: '새 QA 문제',
        title: n.actor_name ? `${n.actor_name} 님이 새 QA 문제를 등록했어요` : '새 QA 문제가 등록됐어요',
        detail: `${id} · ${title}`, href: issueHref,
      }
    case 'fixed':
      // 수동 QA는 등록자가 직접 재검증하므로 "재검증 요청"으로 보여 준다(시안 MRQ-01)
      if (manual) {
        return { kind: 'recheck_request', label: '재검증 요청', title: `${id} 수정 완료 · 재검증을 요청했어요`, detail: `${title} · 수동 QA`, href: issueHref }
      }
      return { kind: 'fixed', label: '수정 완료', title: `${teamName(issue)}이 ${id} 수정을 완료했어요`, detail: `${title} · ${issue.status === 'fixed' ? '재검증 대기' : PROGRESS[issue.status].label}`, href: issueHref }
    case 'resolved':
      return { kind: 'reqa_pass', label: manual ? '재검증 결과' : 'Re-QA 결과', title: `${id} ${manual ? '재검증' : 'Re-QA'} 결과가 PASS로 확정됐어요`, detail: `${title} · 해결됨`, href: issueHref }
    case 'recheck_failed':
      return { kind: 'reqa_fail', label: manual ? '재검증 결과' : 'Re-QA 결과', title: `${id} ${manual ? '재검증' : 'Re-QA'} 결과가 FAIL이에요 · 다시 수정해 주세요`, detail: `${title} · 재확인 필요`, href: issueHref }
    default:
      return { kind: 'new_issue', label: '알림', title: id, detail: title, href: issueHref }
  }
}

/** 시안 시각 표기 — 1분 안 "방금 전", 1시간 안 "N분 전", 그 밖은 "MM-DD HH:mm" */
export function whenText(iso: string, now: Date = new Date()): string {
  const t = new Date(iso)
  const diff = (now.getTime() - t.getTime()) / 1000
  if (diff < 60) return '방금 전'
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`
  const pad = (v: number) => String(v).padStart(2, '0')
  return `${pad(t.getMonth() + 1)}-${pad(t.getDate())} ${pad(t.getHours())}:${pad(t.getMinutes())}`
}
