import { Link } from 'react-router-dom'
import {
  CheckCircle,
  Dot,
  IconChevronRight,
  IconColumns,
  IconGitCompare,
  IconHome,
  IconInbox,
  IconLink,
  IconListChecks,
  IconShieldCheck,
} from '../components/landing/icons'

// 01-1 랜딩 페이지 — Figma(UI Design - Refined / 427:8733) 1:1 구현.
// 브랜드 컬러는 Tailwind 기본 스케일 밖이라 디자인 토큰 hex를 그대로 쓴다:
//  로고 #965FFF · Second(링크/프라이머리 버튼) #0072DC · Primary(보더/강조) #007FFF
//  success #1E8782 · warning #FC1B1F · surface #F9FAFB · border #E2E8F0 · gray700 #5F5F5F
// 아이콘 SVG 원본 에셋은 받을 수 없어 같은 형태로 재구성했다(components/landing/icons.tsx).

const ELEVATION =
  '0px 12px 32px 0px rgba(0,0,0,0.06), 0px 1px 2px 0px rgba(0,0,0,0.04)'

// ── 상태 뱃지 (이슈 행 · 매핑 예시) ──────────────────────────────
function StatusBadge({ kind }: { kind: 'fail' | 'pass' | 'pending' | 'confidence' }) {
  if (kind === 'fail') {
    return (
      <span className="flex h-6 shrink-0 items-center gap-1.5 rounded-full bg-[rgba(252,27,31,0.1)] px-2">
        <Dot color="#FC1B1F" />
        <span className="text-xs font-medium text-black">FAIL</span>
      </span>
    )
  }
  if (kind === 'pass') {
    return (
      <span className="flex h-6 shrink-0 items-center gap-1.5 rounded-full bg-[rgba(30,135,130,0.1)] px-2">
        <Dot color="#1E8782" />
        <span className="text-xs font-medium text-black">PASS</span>
      </span>
    )
  }
  if (kind === 'confidence') {
    return (
      <span className="flex h-6 shrink-0 items-center gap-1.5 rounded-full bg-[rgba(30,135,130,0.1)] px-2">
        <Dot color="#1E8782" />
        <span className="text-xs font-medium text-black">신뢰도 94%</span>
      </span>
    )
  }
  return (
    <span className="flex h-6 shrink-0 items-center gap-1.5 rounded-full border border-[#E2E8F0] bg-[#F9FAFB] px-2">
      <Dot color="#9B9EAF" />
      <span className="text-xs font-medium text-black">대기</span>
    </span>
  )
}

// ── Hero 내 제품 미리보기 목업 ───────────────────────────────────
const PREVIEW_NAV = [
  { label: '프로젝트 홈', icon: IconHome },
  { label: '디자인-코드 매핑', icon: IconGitCompare },
  { label: '디자인 QA', icon: IconShieldCheck },
  { label: 'QA 결과', icon: IconListChecks },
] as const

const PREVIEW_ISSUES = [
  { id: 'QA-1042', title: '버튼 컴포넌트 — 패딩 불일치', prop: 'padding', state: 'error', badge: 'fail' },
  { id: 'QA-1038', title: '헤더 폰트 크기 불일치', prop: 'font-size', state: 'error', badge: 'fail' },
  { id: 'QA-1035', title: '카드 모서리 반경 수정 완료', prop: 'border-radius', state: 'checked', badge: 'pass' },
  { id: 'QA-1030', title: '아이콘 크기 검증 대기', prop: 'size', state: 'unchecked', badge: 'pending' },
] as const

function ProductPreview() {
  return (
    <div
      className="flex w-full items-start overflow-hidden rounded-2xl border border-[#E2E8F0] bg-white"
      style={{ boxShadow: ELEVATION }}
    >
      {/* Sidebar */}
      <div className="flex w-[232px] shrink-0 flex-col gap-1 self-stretch border-r border-[#E2E8F0] bg-[#F9FAFB] px-3 py-4">
        <div className="flex flex-col gap-1 px-3 py-2">
          <p className="text-sm font-medium text-black">쇼핑몰 앱 리디자인</p>
          <p className="text-xs text-[#5F5F5F]">Figma · GitHub 연결됨</p>
        </div>
        {PREVIEW_NAV.map(({ label, icon: Icon }) => (
          <div key={label} className="flex h-10 items-center gap-3 rounded-lg px-3">
            <Icon size={20} className="shrink-0 text-[#5F5F5F]" />
            <p className="text-sm text-[#5F5F5F]">{label}</p>
          </div>
        ))}
        <div className="flex h-10 items-center gap-3 rounded-lg bg-[rgba(0,127,255,0.1)] px-3">
          <IconInbox size={20} className="shrink-0 text-[#0072DC]" />
          <p className="text-sm font-medium text-[#0072DC]">QA Queue</p>
        </div>
        <div className="flex h-10 items-center gap-3 rounded-lg px-3">
          <IconLink size={20} className="shrink-0 text-[#5F5F5F]" />
          <p className="text-sm text-[#5F5F5F]">연결 관리</p>
        </div>
      </div>

      {/* Main */}
      <div className="flex min-w-px flex-1 flex-col gap-5 p-6">
        {/* Page Header */}
        <div className="flex w-full items-center gap-3">
          <div className="flex min-w-px flex-1 flex-col gap-1">
            <p className="text-xl font-bold tracking-[-0.1px] text-black">QA Queue</p>
            <p className="text-xs text-[#5F5F5F]">등록 이슈 31개 · 미해결 18개</p>
          </div>
          <button className="flex h-8 shrink-0 items-center justify-center rounded-lg border border-[#007FFF] bg-white px-3 text-xs font-medium text-[#0072DC]">
            필터
          </button>
          <button className="flex h-8 shrink-0 items-center justify-center rounded-lg bg-[#0072DC] px-3 text-xs font-medium text-white">
            Re-QA 실행
          </button>
        </div>

        {/* Result Tiles */}
        <div className="flex w-full items-start gap-3">
          {[
            { label: 'PASS', value: '74', state: 'checked' as const },
            { label: 'FAIL', value: '31', state: 'error' as const },
            { label: '검증 대기', value: '23', state: 'unchecked' as const },
          ].map((t) => (
            <div key={t.label} className="flex min-w-px flex-1 flex-col gap-2 rounded-lg bg-[#F9FAFB] p-4">
              <div className="flex items-center gap-2">
                <CheckCircle state={t.state} size={20} className="shrink-0" />
                <p className="text-xs font-medium text-black">{t.label}</p>
              </div>
              <p className="text-[28px] font-bold leading-9 tracking-[-0.28px] text-black">{t.value}</p>
            </div>
          ))}
        </div>

        {/* Issue List */}
        <div className="flex w-full flex-col rounded-xl border border-[#E2E8F0]">
          <div className="flex w-full items-center border-b border-[#E2E8F0] bg-[#F9FAFB] px-4 py-2">
            <p className="min-w-px flex-1 text-xs font-medium text-[#5F5F5F]">최근 이슈</p>
            <span className="flex h-8 items-center justify-center rounded-lg px-3 text-xs font-medium text-[#0072DC]">
              전체 보기
            </span>
          </div>
          {PREVIEW_ISSUES.map((row) => (
            <div key={row.id} className="flex w-full items-center gap-3 border-b border-[#E2E8F0] bg-white px-4 py-3 last:border-b-0">
              <CheckCircle state={row.state} size={20} className="shrink-0" />
              <p className="w-16 shrink-0 text-xs text-[#5F5F5F]">{row.id}</p>
              <p className="min-w-px flex-1 truncate text-sm font-medium text-black">{row.title}</p>
              <p className="w-24 shrink-0 text-right text-xs text-[#5F5F5F]">{row.prop}</p>
              <StatusBadge kind={row.badge} />
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

// ── 작동 방식 단계 ───────────────────────────────────────────────
const STEPS = [
  { step: 'STEP 1', status: '완료', statusColor: '#1E8782', state: 'checked' as const, connector: '#1E8782', title: '프로젝트 연결', titleGray: false, desc: 'Figma 파일과 GitHub 저장소를 연결해 분석 대상을 지정합니다.' },
  { step: 'STEP 2', status: '완료', statusColor: '#1E8782', state: 'checked' as const, connector: '#1E8782', title: '디자인·코드 분석', titleGray: false, desc: '디자인 구조와 UI 코드를 분석해 요소를 자동으로 매핑합니다.' },
  { step: 'STEP 3', status: '진행 중', statusColor: '#0072DC', state: 'current' as const, connector: '#E2E8F0', title: '디자인 QA 실행', titleGray: false, desc: '검증 범위를 선택하면 디자인 기대값과 구현값을 비교합니다.' },
  { step: 'STEP 4', status: '대기', statusColor: '#5F5F5F', state: 'unchecked' as const, connector: null, title: '이슈 수정 & Re-QA', titleGray: true, desc: 'QA Queue에서 이슈를 처리하고 수정 결과를 다시 검증합니다.' },
] as const

// ── 기능: 비교 테이블 행 ─────────────────────────────────────────
const DIFF_ROWS = [
  { prop: 'padding', expected: '12px 24px', actual: '8px 20px', pass: false },
  { prop: 'border-radius', expected: '6px', actual: '4px', pass: false },
  { prop: 'background', expected: '#2563EB', actual: '#2563EB', pass: true },
  { prop: 'font-size', expected: '14px / 600', actual: '14px / 600', pass: true },
] as const

export function LandingPage() {
  return (
    <div className="flex min-h-screen flex-col items-center bg-white">
      {/* Header */}
      <header className="flex h-16 w-full items-center justify-between border-b border-[#E2E8F0] bg-white pl-[160px] pr-[80px]">
        <div className="flex items-center gap-8">
          <p className="text-xl font-bold tracking-[-0.1px] text-[#965FFF]">QAting</p>
          <nav className="flex items-start gap-1">
            <a href="#features" className="flex h-8 items-center rounded-lg px-3 text-sm text-[#5F5F5F]">기능</a>
            <a href="#how" className="flex h-8 items-center rounded-lg px-3 text-sm text-[#5F5F5F]">작동 방식</a>
          </nav>
        </div>
        <Link
          to="/login"
          className="flex h-8 items-center justify-center gap-1 rounded-lg px-3 text-xs font-medium text-[#0072DC]"
        >
          로그인하기
        </Link>
      </header>

      {/* Hero */}
      <section className="flex w-full flex-col items-center pb-24 pt-16">
        <div className="flex w-[1120px] flex-col gap-12">
          <div className="flex w-[720px] flex-col gap-6">
            <div className="flex h-6 items-center gap-1.5 self-start rounded-full bg-[rgba(0,127,255,0.1)] px-2">
              <Dot color="#007FFF" />
              <span className="text-xs font-medium text-black">Figma · GitHub 연동 지원</span>
            </div>
            <div className="flex flex-col gap-4">
              <h1 className="text-[40px] font-bold leading-[52px] tracking-[-0.4px] text-black">
                디자인과 코드의 차이,
                <br />
                이제 자동으로 찾아내세요
              </h1>
              <p className="w-[560px] text-base leading-6 text-[#5F5F5F]">
                QAting은 Figma 디자인과 GitHub 코드를 비교해 크기·간격·색상·타이포그래피 불일치를 찾아내고, 수정이 필요한 항목을 QA 이슈로 정리합니다.
              </p>
            </div>
            <div className="flex flex-col gap-3">
              <Link
                to="/login"
                className="flex h-10 w-fit items-center justify-center self-start rounded-lg bg-[#0072DC] px-4 text-sm font-medium text-white"
              >
                시작하기
              </Link>
              <p className="text-xs text-[#5F5F5F]">설치 없이 Figma · GitHub 계정 연결만으로 시작할 수 있어요</p>
            </div>
          </div>
          <ProductPreview />
        </div>
      </section>

      {/* How It Works */}
      <section id="how" className="flex w-full flex-col items-center border-y border-[#E2E8F0] bg-[#F9FAFB] py-16">
        <div className="flex w-[1120px] flex-col gap-10">
          <div className="flex w-[640px] flex-col gap-3">
            <p className="text-xs font-medium text-[#0072DC]">작동 방식</p>
            <h2 className="text-[28px] font-bold leading-9 tracking-[-0.28px] text-black">연결부터 Re-QA까지, 네 단계로 끝나요</h2>
            <p className="text-base leading-6 text-[#5F5F5F]">각 단계의 진행 상황을 한눈에 확인하고, 다음에 할 일을 바로 알 수 있어요.</p>
          </div>
          <div className="flex w-full items-start gap-6">
            {STEPS.map((s) => (
              <div key={s.step} className="flex min-w-px flex-1 flex-col gap-3">
                <div className="flex w-full items-center gap-3">
                  <CheckCircle state={s.state} size={24} className="shrink-0" />
                  {s.connector && (
                    <div className="h-0.5 min-w-px flex-1 rounded-full" style={{ backgroundColor: s.connector }} />
                  )}
                </div>
                <div className="flex items-center gap-2 text-xs">
                  <span className="font-medium" style={{ color: s.titleGray ? '#5F5F5F' : '#5F5F5F' }}>{s.step}</span>
                  <span className="text-[#5F5F5F]">·</span>
                  <span className="font-medium" style={{ color: s.statusColor }}>{s.status}</span>
                </div>
                <p className={`text-base font-bold leading-6 ${s.titleGray ? 'text-[#5F5F5F]' : 'text-black'}`}>{s.title}</p>
                <p className="text-sm leading-5 text-[#5F5F5F]">{s.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Features */}
      <section id="features" className="flex w-full flex-col items-center py-16">
        <div className="flex w-[1120px] flex-col gap-10">
          <div className="flex w-[640px] flex-col gap-3">
            <p className="text-xs font-medium text-[#0072DC]">기능</p>
            <h2 className="text-[28px] font-bold leading-9 tracking-[-0.28px] text-black">디자인 QA에 필요한 흐름을 하나의 워크스페이스에서</h2>
            <p className="text-base leading-6 text-[#5F5F5F]">흩어진 디자인 기준과 코드 변경 이력을 연결해, 무엇이 왜 달라졌는지 빠르게 이해할 수 있어요.</p>
          </div>
          <div className="flex w-full items-start gap-4">
            {/* Feature/Compare */}
            <div className="flex min-w-px flex-1 flex-col gap-5 self-stretch rounded-xl border border-[#E2E8F0] bg-white p-6">
              <div className="flex w-full flex-col gap-3">
                <div className="flex size-9 items-center justify-center rounded-lg border border-[#E2E8F0] bg-white">
                  <IconColumns size={18} className="text-[#0072DC]" />
                </div>
                <div className="flex w-full flex-col gap-1">
                  <p className="text-base font-bold leading-6 text-black">디자인 기대값과 구현값 자동 비교</p>
                  <p className="text-sm leading-5 text-[#5F5F5F]">속성별로 Figma 기준값과 실제 구현값을 나란히 비교하고, 불일치 항목만 빠르게 걸러냅니다.</p>
                </div>
              </div>
              <div className="flex w-full flex-col rounded-lg border border-[#E2E8F0]">
                <div className="flex w-full gap-3 border-b border-[#E2E8F0] bg-[#F9FAFB] py-2 pl-3 pr-0 text-xs font-medium text-[#5F5F5F]">
                  <p className="min-w-px flex-1">속성</p>
                  <p className="w-[120px]">디자인 기대값</p>
                  <p className="w-[120px]">구현 실제값</p>
                  <p className="w-8 text-right">결과</p>
                </div>
                {DIFF_ROWS.map((r) => (
                  <div key={r.prop} className="flex w-full items-center gap-3 border-b border-[#E2E8F0] px-3 py-2 last:border-b-0">
                    <p className="min-w-px flex-1 text-sm font-medium text-black">{r.prop}</p>
                    <p className="w-[120px] text-sm text-[#5F5F5F]">{r.expected}</p>
                    <p className={`w-[120px] text-sm ${r.pass ? 'text-[#5F5F5F]' : 'text-black'}`}>{r.actual}</p>
                    <div className="w-8 text-right">
                      <CheckCircle state={r.pass ? 'checked' : 'error'} size={20} className="ml-auto" />
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* Right column: Mapping + Queue */}
            <div className="flex min-w-px flex-1 flex-col gap-4">
              <div className="flex w-full flex-col gap-5 rounded-xl border border-[#E2E8F0] bg-white p-6">
                <div className="flex w-full flex-col gap-3">
                  <div className="flex size-9 items-center justify-center rounded-lg border border-[#E2E8F0] bg-white">
                    <IconGitCompare size={18} className="text-[#0072DC]" />
                  </div>
                  <div className="flex w-full flex-col gap-1">
                    <p className="text-base font-bold leading-6 text-black">디자인-코드 매핑</p>
                    <p className="text-sm leading-5 text-[#5F5F5F]">Figma 요소와 연결된 코드 파일·컴포넌트·관련 PR을 자동으로 추적합니다.</p>
                  </div>
                </div>
                <div className="flex w-full items-center gap-2 rounded-lg bg-[#F9FAFB] p-3">
                  <p className="shrink-0 text-sm font-medium text-black">ButtonPrimary</p>
                  <IconChevronRight size={16} className="shrink-0 text-[#5F5F5F]" />
                  <p className="min-w-px flex-1 text-sm text-[#5F5F5F]">ui/Button.tsx</p>
                  <StatusBadge kind="confidence" />
                </div>
              </div>
              <div className="flex w-full flex-1 flex-col gap-5 rounded-xl border border-[#E2E8F0] bg-white p-6">
                <div className="flex w-full flex-col gap-3">
                  <div className="flex size-9 items-center justify-center rounded-lg border border-[#E2E8F0] bg-white">
                    <IconInbox size={18} className="text-[#0072DC]" />
                  </div>
                  <div className="flex w-full flex-col gap-1">
                    <p className="text-base font-bold leading-6 text-black">QA Queue &amp; Re-QA</p>
                    <p className="text-sm leading-5 text-[#5F5F5F]">불일치 항목을 이슈로 관리하고, 수정 후 Re-QA로 해결 여부를 확인합니다.</p>
                  </div>
                </div>
                <div className="flex w-full flex-col gap-2">
                  <div className="flex w-full items-start justify-between text-xs">
                    <p className="font-medium text-black">이슈 해결 진행률</p>
                    <p className="text-[#5F5F5F]">13 / 31 해결</p>
                  </div>
                  <div className="relative h-2 w-full overflow-hidden rounded-full bg-[#E8E8E8]">
                    <div className="absolute left-0 top-0 h-2 rounded-full bg-[#1E8782]" style={{ width: '42%' }} />
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* CTA */}
      <section className="flex w-full flex-col items-center border-t border-[#E2E8F0] bg-[#F9FAFB] py-16">
        <div className="flex w-[1120px] flex-col items-center gap-6">
          <div className="flex flex-col items-center gap-3 text-center">
            <h2 className="text-[28px] font-bold leading-9 tracking-[-0.28px] text-black">첫 디자인 QA를 지금 시작해 보세요</h2>
            <p className="text-base leading-6 text-[#5F5F5F]">Figma와 GitHub를 연결하면 바로 분석을 시작할 수 있어요.</p>
          </div>
          <Link
            to="/login"
            className="flex h-10 items-center justify-center rounded-lg border border-[#007FFF] bg-white px-4 text-sm font-medium text-[#0072DC]"
          >
            로그인하기
          </Link>
        </div>
      </section>

      {/* Footer */}
      <footer className="flex w-full flex-col items-center border-t border-[#E2E8F0] py-8">
        <div className="flex w-[1120px] items-center justify-between">
          <p className="text-base font-bold text-[#965FFF]">QAting</p>
          <div className="flex items-start gap-6 text-xs text-[#5F5F5F]">
            <span>도움말</span>
            <span>개인정보처리방침</span>
            <span>약관</span>
          </div>
        </div>
      </footer>
    </div>
  )
}
