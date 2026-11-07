// 상대경로로 호출 — 로컬 개발에선 vite.config.ts의 proxy가 :8080으로 넘겨주고,
// 배포 환경에서는 같은 도메인에서 서빙된다고 가정 (TODO: 실제 배포 구조 확정되면 갱신)
//
// 응답 형태는 openapi의 {success, data, error{code, message}}. 프론트는 message 문자열이 아니라
// error.code로 분기한다(2026-11-05 에러 코드 확정 — docs/openapi.yaml Error.code).

export interface Me {
  authenticated: boolean
  /** 내 멤버 id — owner_id·assigned_to 등과 비교하는 값 */
  id?: string
  name?: string
  email?: string
  picture?: string
}

export async function fetchMe(): Promise<Me> {
  try {
    const res = await fetch('/api/v1/auth/me', { credentials: 'include' })
    if (!res.ok) return { authenticated: false }
    return await res.json()
  } catch {
    // 네트워크 에러 등 예외 상황에서도 화면이 무한 로딩으로 멈추지 않도록 방어
    return { authenticated: false }
  }
}

export function googleLoginUrl() {
  return '/api/v1/auth/login/google'
}

export function logoutUrl() {
  return '/api/v1/auth/logout'
}

// ---------------------------------------------------------------- 공통 호출

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

/** 서버가 XSRF-TOKEN 쿠키로 내려준 값 — POST·PATCH·DELETE는 X-XSRF-TOKEN 헤더에 실어야 한다(없으면 403) */
function xsrfToken(): string {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : ''
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = xsrfToken()
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  let res: Response
  try {
    res = await fetch(url, {
      method,
      credentials: 'include',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', '서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.')
  }
  if (res.status === 204) return undefined as T
  const json = await res.json().catch(() => null)
  if (!res.ok || !json || json.success === false) {
    throw new ApiError(
      res.status,
      json?.error?.code ?? `HTTP_${res.status}`,
      json?.error?.message ?? '요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
  }
  return json.data as T
}

// ---------------------------------------------------------------- 프로젝트·멤버 (SPEC AC15·16·19)

/** 프로젝트별 역할 — 서버 값(designer/frontend/backend/planner — 2026-11-07 developer를 나눔) */
export type MemberRole = 'designer' | 'frontend' | 'backend' | 'planner'

export const ROLE_LABELS: Record<MemberRole, string> = {
  designer: '디자인',
  frontend: '프론트엔드',
  backend: '백엔드',
  planner: '기획',
}

export interface Project {
  id: string
  name: string
  owner_id: string
}

export interface ProjectMember {
  id: string
  name: string
  email: string
  role: MemberRole
}

/** 내가 속한 프로젝트 — 서버가 name asc, id asc로 고정해 돌려준다 */
export async function listProjects(): Promise<Project[]> {
  const data = await request<{ items: Project[] }>('GET', '/api/v1/projects')
  return data.items
}

export function createProject(name: string, role: MemberRole): Promise<Project> {
  return request<Project>('POST', '/api/v1/projects', { name, role })
}

export async function listMembers(projectId: string): Promise<ProjectMember[]> {
  const data = await request<{ items: ProjectMember[] }>('GET', `/api/v1/projects/${projectId}/members`)
  return data.items
}

export function removeMember(projectId: string, memberId: string): Promise<void> {
  return request<void>('DELETE', `/api/v1/projects/${projectId}/members/${memberId}`)
}

// ---------------------------------------------------------------- 초대 (SPEC AC17·18)

export type InvitationStatus = 'pending' | 'accepted' | 'canceled' | 'expired'

export interface Invitation {
  id: string
  project_id: string
  project_name: string
  email: string
  status: InvitationStatus
  invited_by: string
  invited_by_name: string
  expires_at: string
  created_at: string
}

/** 메일의 수락 링크로 들어왔을 때 보이는 미리보기 — 토큰은 응답에 담기지 않는다(N14) */
export interface InvitationPreview {
  project_name: string
  invited_by_name: string
  email: string
  status: InvitationStatus
  expires_at: string
}

/** 같은 이메일에 대기 중 초대가 있으면 서버가 그 초대를 취소하고 새로 만든다(재발송) */
export function inviteMember(projectId: string, email: string): Promise<Invitation> {
  return request<Invitation>('POST', `/api/v1/projects/${projectId}/invitations`, { email })
}

export async function listInvitations(projectId: string): Promise<Invitation[]> {
  const data = await request<{ items: Invitation[] }>('GET', `/api/v1/projects/${projectId}/invitations`)
  return data.items
}

export function cancelInvitation(projectId: string, invitationId: string): Promise<void> {
  return request<void>('DELETE', `/api/v1/projects/${projectId}/invitations/${invitationId}`)
}

export function previewInvitation(token: string): Promise<InvitationPreview> {
  return request<InvitationPreview>('GET', `/api/v1/invitations/${encodeURIComponent(token)}`)
}

export function acceptInvitation(token: string, role: MemberRole): Promise<Project> {
  return request<Project>('POST', `/api/v1/invitations/${encodeURIComponent(token)}/accept`, { role })
}

// ---------------------------------------------------------------- 로그인 후 돌아갈 곳

const AFTER_LOGIN_KEY = 'qating.afterLogin'

/**
 * 서버는 로그인에 성공하면 항상 프론트의 '/'로 보낸다(app.oauth2.login-success-redirect).
 * 초대 링크처럼 로그인 전에 보던 화면으로 돌아가야 할 때 여기에 경로를 적어 두고, '/'가 읽어서 보낸다.
 */
export function rememberAfterLogin(path: string) {
  try {
    sessionStorage.setItem(AFTER_LOGIN_KEY, path)
  } catch {
    // 저장소를 못 쓰면 로그인 뒤 프로젝트 목록으로 간다
  }
}

export function takeAfterLogin(): string | null {
  try {
    const path = sessionStorage.getItem(AFTER_LOGIN_KEY)
    sessionStorage.removeItem(AFTER_LOGIN_KEY)
    // 앱 안의 경로만 따른다 — 외부 주소로 보내는 열린 리다이렉트를 막는다
    return path && path.startsWith('/') && !path.startsWith('//') ? path : null
  } catch {
    return null
  }
}

export function formatDate(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '-'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
