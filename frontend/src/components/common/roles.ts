import type { MemberRole } from '../../lib/api'

// 역할 태그 색·아바타 이니셜 — 작업공간 카드와 초대 수락 화면이 같이 쓴다

/** 시안 Role Tag — 역할별 점·글자색(배경은 같은 색 10%) */
export const ROLE_TAG: Record<MemberRole, { label: string; dot: string; text: string; bg: string }> = {
  designer: { label: '디자인', dot: 'bg-[#e5489b]', text: 'text-[#c2307a]', bg: 'bg-[#e5489b]/10' },
  frontend: { label: '프론트엔드', dot: 'bg-[#007fff]', text: 'text-[#0066cc]', bg: 'bg-[#007fff]/10' },
  backend: { label: '백엔드', dot: 'bg-[#f08c00]', text: 'text-[#a85a00]', bg: 'bg-[#f08c00]/10' },
  planner: { label: '기획', dot: 'bg-[#965fff]', text: 'text-[#6e3be6]', bg: 'bg-[#965fff]/10' },
}

/** 시안 아바타 이니셜: 한글 이름이면 성 다음 글자(김하늘→하), 아니면 첫 글자 */
export function initialOf(name: string): string {
  const trimmed = name.trim()
  // 정규식이 2글자 이상을 보장하므로 charAt(1)은 항상 글자가 있다(charAt은 범위 밖이면 '' — undefined가 아님)
  if (/^[가-힣]{2,4}$/.test(trimmed)) return trimmed.charAt(1)
  return (trimmed[0] ?? '?').toUpperCase()
}
