import { Routes, Route, Navigate } from "react-router-dom";
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

export function App() {
  return (
    <ErrorBoundary>
    <Routes>
      {/* Studio — full-screen breakout, no sidebar/topbar chrome */}
      <Route path="/manage/studio/:agentId" element={<AgentStudioPage />} />

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
        <Route path="/" element={<Navigate to="/manage" replace />} />
        <Route path="*" element={<Navigate to="/manage" replace />} />
      </Route>
    </Routes>
    <CommandPalette />
    </ErrorBoundary>
  );
}
