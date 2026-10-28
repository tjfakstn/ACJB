import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchMe, logoutUrl, type Me } from '../lib/api'

// TODO: 와이어프레임 '첫 시작 안내 화면' / '프로젝트 목록 화면'으로 교체 예정.
// 지금은 로그인 흐름이 끝까지 동작하는지 확인하기 위한 임시 화면.
export function HomePage() {
  const navigate = useNavigate()
  const [me, setMe] = useState<Me | null>(null)

  useEffect(() => {
    fetchMe().then((result) => {
      if (!result.authenticated) {
        navigate('/', { replace: true })
      } else {
        setMe(result)
      }
    })
  }, [navigate])

  if (!me) {
    return null
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
      <div className="flex flex-col items-center gap-3 rounded-xl border border-gray-200 bg-white p-8 text-center shadow-sm">
        {me.picture && (
          <img src={me.picture} alt="" className="h-16 w-16 rounded-full" />
        )}
        <p className="text-lg font-semibold text-gray-900">{me.name}</p>
        <p className="text-sm text-gray-500">{me.email}</p>
        <p className="mt-4 text-xs text-gray-400">
          로그인 성공 — 다음 화면(프로젝트 목록 등)은 아직 준비 중
        </p>
        <a
          href={logoutUrl()}
          className="mt-2 text-xs text-gray-400 underline hover:text-gray-600"
        >
          로그아웃
        </a>
      </div>
    </div>
  )
}
