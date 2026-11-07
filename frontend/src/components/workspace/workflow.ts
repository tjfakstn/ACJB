import type { ProjectDetail } from '../../lib/api'

/**
 * 지금 단계(1~5, 모두 끝났으면 6). 첫 QA 전이면 1, 이후에는 남은 이슈 상태로 정한다:
 * 확인 필요가 있으면 담당 배정(3), 수정 중이 있으면 수정 진행(4), 수정 완료·재확인 필요가 있으면 수정 결과 확인(5)
 */
export function currentStep(detail: ProjectDetail): number {
  if (detail.first_qa_status !== 'succeeded') return 1
  const c = detail.issue_counts
  if (c.needs_check > 0) return 3
  if (c.in_progress > 0) return 4
  if (c.fixed > 0 || c.needs_recheck > 0) return 5
  return 6
}
