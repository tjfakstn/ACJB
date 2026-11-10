import { BrowserRouter, Navigate, Routes, Route } from 'react-router-dom'
import { LandingPage } from './pages/LandingPage'
import { LoginPage } from './pages/LoginPage'
import { ProjectsPage } from './pages/ProjectsPage'
import { MappingPage } from './pages/MappingPage'
import { MembersPage } from './pages/MembersPage'
import { WorkspacePage } from './pages/WorkspacePage'
import { InvitationPage } from './pages/InvitationPage'
import { QaRunPage } from './pages/QaRunPage'
import { LiveQaPage } from './pages/LiveQaPage'
import { QaQueuePage } from './pages/QaQueuePage'
import { IssueDetailPage } from './pages/IssueDetailPage'
import { ReQaResultPage } from './pages/ReQaResultPage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/app" element={<Navigate to="/app/projects" replace />} />
        <Route path="/app/projects" element={<ProjectsPage />} />
        <Route path="/app/projects/:projectId" element={<WorkspacePage />} />
        <Route path="/app/projects/:projectId/members" element={<MembersPage />} />
        <Route path="/app/projects/:projectId/mapping" element={<MappingPage />} />
        <Route path="/app/projects/:projectId/qa" element={<QaRunPage />} />
        <Route path="/app/projects/:projectId/results" element={<LiveQaPage />} />
        <Route path="/app/projects/:projectId/queue" element={<QaQueuePage />} />
        <Route path="/app/projects/:projectId/issues/:issueId" element={<IssueDetailPage />} />
        <Route path="/app/projects/:projectId/issues/:issueId/re-qa" element={<ReQaResultPage />} />
        <Route path="/invitations/:token" element={<InvitationPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
