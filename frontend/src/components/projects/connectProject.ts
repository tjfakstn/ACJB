import type { ConnectProjectInput, ConnectResult } from './types'

// 새 프로젝트 "연결 + 비교 분석" 요청의 단일 진입점.
//
// TODO(백엔드 연동, 설만수): 아래 스텁을 실제 API 호출로 교체.
//   1) GitHub/Figma 링크의 유효성(실제 존재 + 접근 권한)은 **백엔드에서** 검증한다.
//      프론트는 비어있는지 정도만 보고, 유효/무효의 정본은 서버 응답으로 판단한다.
//      실패하면 { ok:false, service, message } 로 어느 서비스가 문제인지 돌려준다.
//   2) 성공 시 비교 분석 결과의 집계를 돌려준다:
//      qaCount  = QA 결과 수,
//      failCount = 디자인-구현 불일치(style_mismatch) 수.
//      → 이 값들은 프론트에서 만들지 않는다. 반드시 서버 응답을 그대로 사용한다.
//      (연동 시 openapi.yaml의 figma sync + style-mismatches scan 결과를 집계)
export async function attemptConnect(input: ConnectProjectInput): Promise<ConnectResult> {
  // ==== 임시 스텁 (백엔드 없음) ====
  // 실제 링크 유효성 검증은 백엔드 몫. 데모/QA가 "오류 화면(02-5)"과 "성공 흐름"을
  // 모두 눌러볼 수 있도록, 링크가 비었거나 'fail' 을 포함하면 실패로 보는 최소 동작만 둔다.
  await new Promise((resolve) => setTimeout(resolve, 300))
  const invalid = (link: string) => link.trim() === '' || link.toLowerCase().includes('fail')
  if (invalid(input.githubLink)) {
    return { ok: false, service: 'github', message: 'GitHub 저장소 링크를 확인해 주세요' }
  }
  if (invalid(input.figmaLink)) {
    return { ok: false, service: 'figma', message: 'Figma 파일 링크를 확인해 주세요' }
  }
  // 성공: 집계는 백엔드가 채운다. 스텁에서는 "아직 서버 값 없음"을 뜻하는 0으로 둔다(임의 난수 금지).
  // TODO: 서버 분석 결과의 실제 qaCount/failCount 로 대체.
  return { ok: true, qaCount: 0, failCount: 0 }
}
