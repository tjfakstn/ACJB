import { googleLoginUrl } from '../lib/api'

// 와이어프레임의 "로그인하기" 버튼은 회색 placeholder라 실제 스타일이 정해진 게 아니라서,
// 구글 로그인이라는 점을 살려 구글 브랜드 가이드라인 스타일(흰 배경 + G 로고)로 구현.
export function GoogleLoginButton() {
  return (
    <a
      href={googleLoginUrl()}
      className="flex items-center justify-center gap-3 rounded-md border border-gray-300 bg-white px-4 py-2.5 text-sm font-medium text-gray-800 shadow-sm transition-colors hover:bg-gray-50"
    >
      <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
        <path
          fill="#4285F4"
          d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.9c1.7-1.57 2.7-3.88 2.7-6.62z"
        />
        <path
          fill="#34A853"
          d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.9-2.26c-.8.54-1.84.86-3.06.86-2.35 0-4.34-1.59-5.05-3.72H.98v2.33A9 9 0 0 0 9 18z"
        />
        <path
          fill="#FBBC05"
          d="M3.95 10.7A5.4 5.4 0 0 1 3.67 9c0-.59.1-1.16.28-1.7V4.97H.98A9 9 0 0 0 0 9c0 1.45.35 2.83.98 4.03l2.97-2.33z"
        />
        <path
          fill="#EA4335"
          d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58C13.46.89 11.43 0 9 0A9 9 0 0 0 .98 4.97l2.97 2.33C4.66 5.17 6.65 3.58 9 3.58z"
        />
      </svg>
      Google로 로그인
    </a>
  )
}
