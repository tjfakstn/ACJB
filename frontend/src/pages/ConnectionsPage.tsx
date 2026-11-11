import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ConnectionCards, type ConnectionStatus } from '../components/projects/ConnectionCards'
import { ErrorCard, Loading, PageTitle, QaShell, useProject } from '../components/qa/QaParts'

// 연결 관리(시안 LNK — 화면 시안이 아직 없어 마법사 3단계 연결 카드를 그대로 쓴다, 2026-11-11).
// 프로젝트를 만들 때 연결을 건너뛰었거나 링크를 바꿀 때 여기서 연결한다. 연결은 소유자만, 비교 분석이 성공한 뒤에는 바꿀 수 없다(AC21)
export function ConnectionsPage() {
  const { projectId = '' } = useParams()
  const { me, detail, error, reload } = useProject(projectId)
  /** 카드가 아는 연결 상태 — 연결할 때마다 다시 읽는 프로젝트 정보는 응답 순서가 뒤바뀔 수 있어 안내 문구는 이것을 따른다 */
  const [status, setStatus] = useState<ConnectionStatus | null>(null)
  if (!me) return null

  const isOwner = !!detail && detail.owner_id === me.id
  const locked = !detail ? null
    : !isOwner ? 'Figma·GitHub 연결은 프로젝트 소유자만 바꿀 수 있어요.'
    : detail.analysis_status === 'succeeded' ? '비교 분석이 끝나 연결을 바꿀 수 없어요. 매핑 결과가 지금 연결한 파일·저장소를 가리키기 때문이에요.'
    : null
  const ready = status ? status.bothConnected : !!detail?.figma && !!detail?.github

  return (
    <QaShell projectId={projectId}>
      <PageTitle
        crumbs={[{ label: detail?.name ?? '프로젝트', to: `/app/projects/${projectId}` }, { label: '연결 관리' }]}
        title="연결 관리"
        description="분석할 GitHub 저장소와 Figma 페이지·섹션을 연결해요. 링크를 넣고 칸을 벗어나면 접근 권한을 확인한 뒤 연결됩니다."
      />
      {error ? <ErrorCard message={error} projectId={projectId} /> : !detail ? <Loading /> : (
        <section className="mt-6 flex max-w-[960px] flex-col gap-5 rounded-2xl border border-qa-border bg-white p-6">
          {/* 소유자·분석 상태가 정해진 뒤 연결 정보로 카드를 처음부터 그린다 */}
          <ConnectionCards key={`${detail.id}-${locked ?? ''}`} projectId={projectId} initialFigma={detail.figma} initialGithub={detail.github}
            locked={locked} onStatus={setStatus} onConnected={() => void reload()} />
          <div className="h-px bg-qa-border" />
          <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-qa-text-sub">
            <span>{ready ? '둘 다 연결됐어요. 프로젝트 홈에서 비교 분석을 시작할 수 있어요.' : 'QA를 실행하려면 Figma와 GitHub를 모두 연결해야 해요.'}</span>
            {ready && detail.analysis_status !== 'succeeded' && (
              <Link to={`/app/projects/${projectId}`} className="font-medium text-qa-primary-hover hover:underline">프로젝트 홈으로 가기</Link>
            )}
          </div>
        </section>
      )}
    </QaShell>
  )
}
