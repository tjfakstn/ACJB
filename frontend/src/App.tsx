import { BrowserRouter, Navigate, Routes, Route } from 'react-router-dom'
import { LandingPage } from './pages/LandingPage'
import { LoginPage } from './pages/LoginPage'
import { ProjectsPage } from './pages/ProjectsPage'
import { MembersPage } from './pages/MembersPage'
import { InvitationPage } from './pages/InvitationPage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/app" element={<Navigate to="/app/projects" replace />} />
        <Route path="/app/projects" element={<ProjectsPage />} />
        {/* 작업공간 홈(시안 U04-01)이 아직 없어 프로젝트로 들어오면 멤버 관리로 보낸다 */}
        <Route path="/app/projects/:projectId" element={<Navigate to="members" replace />} />
        <Route path="/app/projects/:projectId/members" element={<MembersPage />} />
        <Route path="/invitations/:token" element={<InvitationPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
