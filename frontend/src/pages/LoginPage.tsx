import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchMe } from '../lib/api'
import { GoogleLoginButton } from '../components/GoogleLoginButton'

// Figma 와이어프레임 '랜딩/로그인 화면' 참고 (13:96) — 지금은 1차 와이어프레임이라
// 로고/텍스트가 전부 placeholder라, 레이아웃(중앙 카드 + 로고 + 로그인 버튼)만 가져오고
// 실제 톤/카피는 우리 서비스에 맞게 채움. 디자인 확정되면 다시 맞출 것.
export function LoginPage() {
  const navigate = useNavigate()
  const [checking, setChecking] = useState(true)

  useEffect(() => {
    fetchMe().then((me) => {
      if (me.authenticated) {
        navigate('/app', { replace: true })
      } else {
        setChecking(false)
      }
    })
  }, [navigate])

  if (checking) {
    return null
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
      <div className="w-full max-w-sm rounded-xl border border-gray-200 bg-white p-8 shadow-sm">
        <div className="mb-8 flex flex-col items-center gap-2">
          <div className="flex h-12 w-12 items-center justify-center rounded-md bg-gray-800 text-lg font-semibold text-white">
            Q
          </div>
          <h1 className="text-xl font-semibold text-gray-900">QAting</h1>
          <p className="text-sm text-gray-500">
            Figma와 GitHub를 연결하는 QA 자동화
          </p>
        </div>

        <GoogleLoginButton />
      </div>
    </div>
  )
}
