// 상대경로로 호출 — 로컬 개발에선 vite.config.ts의 proxy가 :8080으로 넘겨주고,
// 배포 환경에서는 같은 도메인에서 서빙된다고 가정 (TODO: 실제 배포 구조 확정되면 갱신)

export interface Me {
  authenticated: boolean
  name?: string
  email?: string
  picture?: string
}

export async function fetchMe(): Promise<Me> {
  const res = await fetch('/api/v1/auth/me', { credentials: 'include' })
  if (!res.ok) return { authenticated: false }
  return res.json()
}

export function googleLoginUrl() {
  return '/oauth2/authorization/google'
}
