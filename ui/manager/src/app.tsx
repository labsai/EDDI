import { Routes, Route, Navigate, useLocation, useParams } from "react-router-dom";
import { AppLayout } from "@/components/layout/app-layout";
import { ErrorBoundary } from "@/components/ui/error-boundary";
import { DashboardPage } from "@/pages/dashboard";
import { AgentsPage } from "@/pages/agents";
import { AgentDetailPage } from "@/pages/agent-detail";
import { WorkflowsPage } from "@/pages/workflows";
import { WorkflowDetailPage } from "@/pages/workflow-detail";
import { ConversationsPage } from "@/pages/conversations";
import { ConversationDetailPage } from "@/pages/conversation-detail";
import { ChatPage } from "@/pages/chat";
import { ResourcesPage } from "@/pages/resources";
import { ResourceListPage } from "@/pages/resource-list";
import { ResourceDetailPage } from "@/pages/resource-detail";
import { AgentWizardPage } from "@/pages/agent-wizard";
import { CoordinatorPage } from "@/pages/coordinator";
import { SchedulesPage } from "@/pages/schedules";
import { OrphansPage } from "@/pages/orphans";
import { LogsPage } from "@/pages/logs";
import { SecretsPage } from "@/pages/secrets";
import { VariablesPage } from "@/pages/variables";
import { AuditPage } from "@/pages/audit";
import { QuotasPage } from "@/pages/quotas";
import { GroupsPage } from "@/pages/groups";
import { GroupDetailPage } from "@/pages/group-detail";
import { GroupWizardPage } from "@/pages/group-wizard";
import { AgentStudioPage } from "@/pages/agent-studio";
import { CommandPalette } from "@/components/shared/command-palette";

import { GdprPage } from "@/pages/gdpr";
import { UserDataPage } from "@/pages/user-data";
import { TriggersPage } from "@/pages/triggers";
import { CapabilitiesPage } from "@/pages/capabilities";
import { SyncPage } from "@/pages/sync-page";
import { ChannelsPage } from "@/pages/channels";
import { ChannelDetailPage } from "@/pages/channel-detail";
import { ApprovalsPage } from "@/pages/approvals";
import { LandingPage } from "@/pages/landing-page";

import { WorkforceLayout } from "@/components/workforce/workforce-layout";
import { WorkforceDashboard } from "@/pages/workforce/workforce-dashboard";
import { WorkforceWizard } from "@/pages/workforce/workforce-wizard";
import { WorkforceBoard } from "@/pages/workforce/workforce-board";
import { WorkforceThread } from "@/pages/workforce/workforce-thread";
import { WorkforceSettings } from "@/pages/workforce/workforce-settings";
import { WorkforceHistory } from "@/pages/workforce/workforce-history";
import { WorkforceAnalytics } from "@/pages/workforce/workforce-analytics";
import { WorkforceChat } from "@/pages/workforce/workforce-chat";

/** Redirect /workforce/* → /workforce/* preserving sub-paths */
function WorkforceRedirect() {
  const { "*": rest } = useParams();
  const sub = rest ? `/${rest}` : "";
  return <Navigate to={`/workforce${sub}`} replace />;
}

export function App() {
  const location = useLocation();
  return (
    <ErrorBoundary resetKey={location.key}>
    <Routes>
      {/* Landing — workspace chooser */}
      <Route path="/" element={<LandingPage />} />

      {/* Studio — full-screen breakout, no sidebar/topbar chrome */}
      <Route path="/manage/studio/:agentId" element={<AgentStudioPage />} />

      {/* Workforce — standalone app, no Manager chrome */}
      <Route path="/workforce" element={<WorkforceLayout />}>
        <Route index element={<WorkforceDashboard />} />
        <Route path="new" element={<WorkforceWizard />} />
        <Route path="analytics" element={<WorkforceAnalytics />} />
        <Route path="chat" element={<WorkforceChat />} />
        <Route path=":boardId" element={<WorkforceBoard />} />
        <Route path=":boardId/thread/:memberId" element={<WorkforceThread />} />
        <Route path=":boardId/settings" element={<WorkforceSettings />} />
        <Route path=":boardId/history" element={<WorkforceHistory />} />
      </Route>

      {/* Legacy /Workforce redirect → /workforce */}
      <Route path="/workforce/*" element={<WorkforceRedirect />} />
      <Route path="/Workforce" element={<Navigate to="/workforce" replace />} />

      <Route element={<AppLayout />}>
        <Route path="/manage" element={<DashboardPage />} />
        <Route path="/manage/agents" element={<AgentsPage />} />
        <Route path="/manage/agents/wizard" element={<AgentWizardPage />} />
        <Route path="/manage/agentview/:id" element={<AgentDetailPage />} />
        <Route path="/manage/workflows" element={<WorkflowsPage />} />
        <Route path="/manage/workflowview/:id" element={<WorkflowDetailPage />} />
        <Route path="/manage/conversations" element={<ConversationsPage />} />
        <Route path="/manage/coordinator" element={<CoordinatorPage />} />
        <Route path="/manage/schedules" element={<SchedulesPage />} />
        <Route path="/manage/logs" element={<LogsPage />} />
        <Route path="/manage/orphans" element={<OrphansPage />} />
        <Route path="/manage/secrets" element={<SecretsPage />} />
        <Route path="/manage/variables" element={<VariablesPage />} />
        <Route path="/manage/audit" element={<AuditPage />} />
        <Route path="/manage/quotas" element={<QuotasPage />} />
        <Route path="/manage/gdpr" element={<GdprPage />} />
        <Route path="/manage/groups" element={<GroupsPage />} />
        <Route path="/manage/groups/wizard" element={<GroupWizardPage />} />
        <Route path="/manage/groups/:id" element={<GroupDetailPage />} />
        <Route path="/manage/userdata" element={<UserDataPage />} />
        <Route path="/manage/triggers" element={<TriggersPage />} />
        <Route path="/manage/capabilities" element={<CapabilitiesPage />} />
        <Route path="/manage/sync" element={<SyncPage />} />
        <Route path="/manage/channels" element={<ChannelsPage />} />
        <Route path="/manage/channels/:id" element={<ChannelDetailPage />} />
        <Route path="/manage/approvals" element={<ApprovalsPage />} />
        {/* Redirects from old standalone user-data pages */}
        <Route path="/manage/memories" element={<Navigate to="/manage/userdata?tab=memories" replace />} />
        <Route path="/manage/properties" element={<Navigate to="/manage/userdata?tab=properties" replace />} />
        <Route path="/manage/user-conversations" element={<Navigate to="/manage/userdata?tab=conversations" replace />} />
        <Route
          path="/manage/conversationview/:id"
          element={<ConversationDetailPage />}
        />
        <Route path="/manage/chat" element={<ChatPage />} />
        <Route path="/manage/resources" element={<ResourcesPage />} />
        <Route path="/manage/resources/:type" element={<ResourceListPage />} />
        <Route
          path="/manage/resources/:type/:id"
          element={<ResourceDetailPage />}
        />
      </Route>

      {/* Catch-all → landing */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
    <CommandPalette />
    </ErrorBoundary>
  );
}

