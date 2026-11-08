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

/** 역할이 없는 멤버(프로젝트를 만든 사람, 소유자가 정하기 전)를 보여줄 때 쓰는 이름 */
export const ROLE_UNSET_LABEL = '미지정'

export function roleLabel(role: MemberRole | null | undefined): string {
  return role ? ROLE_LABELS[role] : ROLE_UNSET_LABEL
}

export interface Project {
  id: string
  name: string
  description: string | null
  owner_id: string
  created_at: string
}

export type RunStatus = 'none' | 'queued' | 'running' | 'succeeded' | 'failed'

/** 프로젝트 목록 카드(시안 PRJ-01, SPEC AC15) — issue_count="QA 결과 n건", open_issue_count="FAIL n건" */
export interface ProjectSummary {
  id: string
  name: string
  description: string | null
  owner_id: string
  figma_connected: boolean
  github_connected: boolean
  analysis_status: RunStatus
  last_analyzed_at: string | null
  issue_count: number
  open_issue_count: number
  member_count: number
  members_preview: { id: string; name: string }[]
}

export interface ProjectMember {
  id: string
  name: string
  email: string
  /** null=미지정 — 프로젝트를 만든 사람은 역할 없이 시작한다(2026-11-07) */
  role: MemberRole | null
}

/** 내가 속한 프로젝트 — 서버가 name asc, id asc로 고정해 돌려준다 */
export async function listProjects(): Promise<ProjectSummary[]> {
  const data = await request<{ items: ProjectSummary[] }>('GET', '/api/v1/projects')
  return data.items
}

/** 이름(필수)·설명(선택)만 받는다(시안 PRJ-02). 만든 사람은 소유자이자 역할 미지정 멤버가 된다(SPEC AC16) */
export function createProject(name: string, description: string | null): Promise<Project> {
  return request<Project>('POST', '/api/v1/projects', description ? { name, description } : { name })
}

export async function listMembers(projectId: string): Promise<ProjectMember[]> {
  const data = await request<{ items: ProjectMember[] }>('GET', `/api/v1/projects/${projectId}/members`)
  return data.items
}

/** page_id·page_name은 페이지 또는 섹션 — scope가 어느 쪽인지 알려 준다(2026-11-07) */
export interface FigmaConnection { url: string; file_key: string; file_name: string; page_id: string; page_name: string; scope: 'page' | 'section'; connected_at: string }
export interface GithubConnection { url: string; repo: string; default_branch: string; branch: string; connected_at: string }
/** 연결 응답에만 — 고를 수 있는 브랜치(이름순, 최대 100개) */
export interface GithubConnectResult extends GithubConnection { branches: string[] }

/** 소유자만. Figma 앱 페이지 [공유하기]로 복사한 페이지 링크만 받는다. 보기 권한 확인 후 저장 — 400 페이지 링크 아님, 422, 502(SPEC AC21) */
export function connectFigma(projectId: string, url: string): Promise<FigmaConnection> {
  return request<FigmaConnection>('PUT', `/api/v1/projects/${projectId}/connections/figma`, { url })
}

/** 소유자만. 공개 저장소만 — GitHub 404(비공개·없는 저장소)는 422(SPEC AC21) */
export function connectGithub(projectId: string, url: string, branch?: string): Promise<GithubConnectResult> {
  return request<GithubConnectResult>('PUT', `/api/v1/projects/${projectId}/connections/github`, branch ? { url, branch } : { url })
}

/** 소유자만 — 소유자 본인 역할도 바꿀 수 있다(SPEC AC24) */
export function changeMemberRole(projectId: string, memberId: string, role: MemberRole): Promise<ProjectMember> {
  return request<ProjectMember>('PATCH', `/api/v1/projects/${projectId}/members/${memberId}`, { role })
}

// ---------------------------------------------------------------- 작업공간 홈 (SPEC AC25)

export type IssueStatus = 'needs_check' | 'in_progress' | 'fixed' | 'needs_recheck' | 'resolved'

export const ISSUE_STATUS_LABELS: Record<IssueStatus, string> = {
  needs_check: '확인 필요',
  in_progress: '수정 중',
  fixed: '수정 완료',
  needs_recheck: '재확인 필요',
  resolved: '해결',
}

export interface ProjectDetail {
  id: string
  name: string
  description: string | null
  owner_id: string
  /** 만든 시각(2026-11-07 추가 — 작업공간 "생성일") */
  created_at: string
  /** null=미지정 */
  my_role: MemberRole | null
  figma: FigmaConnection | null
  github: GithubConnection | null
  analysis_status: RunStatus
  first_qa_status: RunStatus
  member_count: number
  pending_invitation_count: number
  issue_counts: Record<IssueStatus, number>
}

export function getProjectDetail(projectId: string): Promise<ProjectDetail> {
  return request<ProjectDetail>('GET', `/api/v1/projects/${projectId}`)
}

/** 소유자만 — 프로젝트와 그 안의 데이터를 모두 지우고 되돌릴 수 없다. 실행 중이면 409 NOT_READY(SPEC AC26) */
export function deleteProject(projectId: string): Promise<void> {
  return request<void>('DELETE', `/api/v1/projects/${projectId}`)
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
  /** 이미 참여한 사람을 그 프로젝트로 보낼 때 쓴다(시안 GST-02A) */
  project_id: string
  project_name: string
  project_description: string | null
  invited_by_name: string
  email: string
  status: InvitationStatus
  expires_at: string
}

/** 같은 이메일에 대기 중 초대가 있으면 서버가 새로 만들지 않고 그 초대를 7일 연장해 다시 보낸다(200) */
export function inviteMember(projectId: string, email: string): Promise<Invitation> {
  return request<Invitation>('POST', `/api/v1/projects/${projectId}/invitations`, { email })
}

/** 대기 중 초대의 만료를 7일 늘리고 새 링크로 다시 보낸다 — 이전 링크는 무효(SPEC AC17) */
export function resendInvitation(projectId: string, invitationId: string): Promise<Invitation> {
  return request<Invitation>('POST', `/api/v1/projects/${projectId}/invitations/${invitationId}/resend`)
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

// --- 비교 분석(AC22)·디자인-코드 매핑(AC27) ---

export interface ProjectRun {
  id: string
  kind: 'analysis' | 'first_qa' | 're_qa'
  status: Exclude<RunStatus, 'none'>
  /** analysis는 저장한 매핑 수, first_qa는 만든 이슈 수, re_qa는 해결(pass)된 이슈 수 */
  created_count: number
  /** analysis·first_qa는 근거가 없어 버린 수, re_qa는 근거를 다시 얻지 못해 건너뛴 수 */
  dropped_count: number
  /** re_qa에서 여전히 달라 fail로 판정한 수. 다른 kind는 null */
  failed_count: number | null
  /** first_qa가 고른 검증 범위 */
  scope: QaScope[] | null
  /** 개발용 가짜 비교기로 돈 실행([시연용] — 2026-11-08) */
  demo: boolean
  error_message: string | null
  created_at: string
  finished_at: string | null
}

/** 소유자만. Figma·GitHub가 둘 다 연결돼 있고 진행 중·성공한 분석이 없을 때만 202(그 외 409 NOT_READY) */
export function startAnalysis(projectId: string): Promise<ProjectRun> {
  return request<ProjectRun>('POST', `/api/v1/projects/${projectId}/analysis`)
}

/** 최근 비교 분석 — 실행한 적이 없으면 null */
export function getAnalysis(projectId: string): Promise<ProjectRun | null> {
  return request<ProjectRun | null>('GET', `/api/v1/projects/${projectId}/analysis`)
}

export interface NodeMapping { node_id: string; node_name: string; code_path: string; code_symbol: string }
export interface FrameMapping { frame_id: string; frame_name: string; code_path: string; nodes: NodeMapping[] }
export interface MappingList { run: ProjectRun | null; commit_sha: string | null; items: FrameMapping[] }

/** 최근 성공한 비교 분석의 매핑 — 프레임 이름순, 그 안 노드 이름순. 없으면 items 빈 배열 */
export function getMappings(projectId: string): Promise<MappingList> {
  return request<MappingList>('GET', `/api/v1/projects/${projectId}/analysis/mappings`)
}

export function formatDateTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '-'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${formatDate(iso)} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// --- 첫 QA(AC23)·QA 이슈·담당 팀(AC3·AC13)·자동 Re-QA(AC28) — 2026-11-08 ---

/** 첫 QA 검증 범위(시안 QA-01) — 1개 이상 */
export type QaScope = 'size_spacing' | 'color_typography' | 'border_radius' | 'layout' | 'presence'

export const QA_SCOPES: { value: QaScope; label: string }[] = [
  { value: 'size_spacing', label: '크기·간격·위치' },
  { value: 'color_typography', label: '색상·타이포그래피' },
  { value: 'border_radius', label: '테두리·모서리' },
  { value: 'layout', label: '레이아웃' },
  { value: 'presence', label: 'UI 요소 존재 여부' },
]

/** 담당 팀은 프로젝트 역할과 같은 값 — 그 역할 멤버들이 팀이다(2026-11-08) */
export type Team = MemberRole

export const TEAM_LABELS: Record<Team, string> = {
  planner: '기획팀',
  designer: '디자인팀',
  frontend: '프론트엔드팀',
  backend: '백엔드팀',
}

export type StyleProperty = 'color' | 'font_weight' | 'font_family' | 'radius' | 'shadow' | 'spacing' | 'text'

export const PROPERTY_LABELS: Record<StyleProperty, string> = {
  color: '색상',
  font_weight: '글자 굵기',
  font_family: '글꼴',
  radius: '모서리',
  shadow: '그림자',
  spacing: '간격',
  text: '텍스트',
}

export interface StyleMismatch {
  id: string
  frame_id: string | null
  screen: string | null
  property: StyleProperty
  expected_value: string
  actual_value: string
}

export interface QaIssue {
  id: string
  project_id: string
  description: string
  /** 첫 QA 이슈는 "화면 / 요소" */
  screen_location: string
  repro_steps: string[]
  platform_scope: 'common' | 'aos' | 'ios'
  figma_link: string | null
  style_mismatch: StyleMismatch | null
  status: IssueStatus
  source: 'manual' | 'first_qa'
  /** changed_at asc */
  status_history: { status: IssueStatus; changed_by: string; changed_at: string }[]
  assigned_team: Team | null
  reported_by: string
  created_at: string
  updated_at: string
}

export interface Verification {
  id: string
  qa_issue_id: string
  result: 'pass' | 'fail'
  scope: string | null
  source: 'manual' | 're_qa'
  /** Re-QA가 다시 읽은 구현 값(시안 RQA-03 수정 후). manual이면 null */
  observed_value: string | null
  verified_by: string
  verified_at: string
}

/** 소유자만. 성공한 비교 분석이 있고 첫 QA가 아직 성공하지 않았을 때 202(그 외 409 NOT_READY), scope 비면 400 */
export function startFirstQa(projectId: string, scope: QaScope[]): Promise<ProjectRun> {
  return request<ProjectRun>('POST', `/api/v1/projects/${projectId}/first-qa`, { scope })
}

/** 최근 첫 QA — 실행한 적이 없으면 null */
export function getFirstQa(projectId: string): Promise<ProjectRun | null> {
  return request<ProjectRun | null>('GET', `/api/v1/projects/${projectId}/first-qa`)
}

/** 소유자만. fixed인 첫 QA 이슈가 없거나 첫 QA·Re-QA가 진행 중이면 409 NOT_READY */
export function startReQa(projectId: string): Promise<ProjectRun> {
  return request<ProjectRun>('POST', `/api/v1/projects/${projectId}/re-qa`)
}

/** 최근 Re-QA — 실행한 적이 없으면 null */
export function getReQa(projectId: string): Promise<ProjectRun | null> {
  return request<ProjectRun | null>('GET', `/api/v1/projects/${projectId}/re-qa`)
}

/** 서버가 created_at desc, id desc로 고정해 돌려준다 */
export async function listIssues(projectId: string): Promise<QaIssue[]> {
  const data = await request<{ items: QaIssue[] }>('GET', `/api/v1/projects/${projectId}/qa-issues`)
  return data.items
}

export function getIssue(projectId: string, issueId: string): Promise<QaIssue> {
  return request<QaIssue>('GET', `/api/v1/projects/${projectId}/qa-issues/${issueId}`)
}

/** 프로젝트 멤버 누구나. 같은 팀 재지정은 알림 없음, null로 비우기는 400(AC3·N5) */
export function assignTeam(projectId: string, issueId: string, team: Team): Promise<QaIssue> {
  return request<QaIssue>('PATCH', `/api/v1/projects/${projectId}/qa-issues/${issueId}`, { assigned_team: team })
}

/** in_progress·fixed는 역할이 담당 팀인 멤버만(403), 정해진 전이 밖이면 409(AC6·AC13) */
export function changeIssueStatus(projectId: string, issueId: string, status: 'in_progress' | 'fixed'): Promise<QaIssue> {
  return request<QaIssue>('PATCH', `/api/v1/projects/${projectId}/qa-issues/${issueId}`, { status })
}

/** verified_at desc */
export async function listVerifications(projectId: string, issueId: string): Promise<Verification[]> {
  const data = await request<{ items: Verification[] }>('GET', `/api/v1/projects/${projectId}/qa-issues/${issueId}/verifications`)
  return data.items
}

/** 연결한 Figma 파일에서 그 프레임을 여는 링크 */
export function figmaFrameUrl(fileKey: string, frameId: string): string {
  return `https://www.figma.com/design/${fileKey}/?node-id=${encodeURIComponent(frameId.replace(':', '-'))}`
}
