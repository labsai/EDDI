import { Suspense } from "react";
import { Routes, Route, Navigate, useLocation, useParams } from "react-router-dom";
import { AppLayout } from "@/components/layout/app-layout";
import { PageLoader } from "@/components/layout/page-loader";
import { ErrorBoundary } from "@/components/ui/error-boundary";
import { CommandPalette } from "@/components/shared/command-palette";
import { WorkforceLayout } from "@/components/workforce/workforce-layout";
import { LandingPage } from "@/pages/landing-page";
import { lazyPage } from "@/lib/lazy-page";

/**
 * Route components are code-split; the layouts, the landing page and the command
 * palette are not.
 *
 * The three eager imports are deliberate, not oversights:
 *  - `AppLayout` / `WorkforceLayout` are the chrome every child route renders
 *    into. Splitting them would put a chunk fetch in front of the first paint of
 *    the shell itself, and they are small.
 *  - `LandingPage` is where `/` redirects, so it is on the critical path for a
 *    cold visit. Lazy-loading the very first thing rendered trades a smaller
 *    bundle for a slower first paint — the wrong side of the trade.
 *  - `CommandPalette` mounts on every screen and binds a global hotkey; it has
 *    to be present before the user presses it.
 *
 * Everything else loads on navigation, behind the `<PageLoader />` skeleton.
 * Before this split the app shipped as one 8.5 MB chunk (2.35 MB gzipped) that
 * every user downloaded in full to see any single screen.
 */

// ── Manager ──────────────────────────────────────────────────────────
const DashboardPage = lazyPage(() => import("@/pages/dashboard"), "DashboardPage");
const AgentsPage = lazyPage(() => import("@/pages/agents"), "AgentsPage");
const AgentDetailPage = lazyPage(() => import("@/pages/agent-detail"), "AgentDetailPage");
const AgentWizardPage = lazyPage(() => import("@/pages/agent-wizard"), "AgentWizardPage");
const AgentStudioPage = lazyPage(() => import("@/pages/agent-studio"), "AgentStudioPage");
const WorkflowsPage = lazyPage(() => import("@/pages/workflows"), "WorkflowsPage");
const WorkflowDetailPage = lazyPage(() => import("@/pages/workflow-detail"), "WorkflowDetailPage");
const ConversationsPage = lazyPage(() => import("@/pages/conversations"), "ConversationsPage");
const ConversationMonitoringPage = lazyPage(
  () => import("@/pages/conversation-monitoring"),
  "ConversationMonitoringPage",
);
const ConversationDetailPage = lazyPage(
  () => import("@/pages/conversation-detail"),
  "ConversationDetailPage",
);
const ChatPage = lazyPage(() => import("@/pages/chat"), "ChatPage");
const ResourcesPage = lazyPage(() => import("@/pages/resources"), "ResourcesPage");
const ResourceListPage = lazyPage(() => import("@/pages/resource-list"), "ResourceListPage");
const ResourceDetailPage = lazyPage(() => import("@/pages/resource-detail"), "ResourceDetailPage");
const CoordinatorPage = lazyPage(() => import("@/pages/coordinator"), "CoordinatorPage");
const SchedulesPage = lazyPage(() => import("@/pages/schedules"), "SchedulesPage");
const OrphansPage = lazyPage(() => import("@/pages/orphans"), "OrphansPage");
const LogsPage = lazyPage(() => import("@/pages/logs"), "LogsPage");
const SecretsPage = lazyPage(() => import("@/pages/secrets"), "SecretsPage");
const VariablesPage = lazyPage(() => import("@/pages/variables"), "VariablesPage");
const AuditPage = lazyPage(() => import("@/pages/audit"), "AuditPage");
const QuotasPage = lazyPage(() => import("@/pages/quotas"), "QuotasPage");
const GdprPage = lazyPage(() => import("@/pages/gdpr"), "GdprPage");
const UserDataPage = lazyPage(() => import("@/pages/user-data"), "UserDataPage");
const TriggersPage = lazyPage(() => import("@/pages/triggers"), "TriggersPage");
const CapabilitiesPage = lazyPage(() => import("@/pages/capabilities"), "CapabilitiesPage");
const SyncPage = lazyPage(() => import("@/pages/sync-page"), "SyncPage");
const ChannelsPage = lazyPage(() => import("@/pages/channels"), "ChannelsPage");
const ChannelDetailPage = lazyPage(() => import("@/pages/channel-detail"), "ChannelDetailPage");
const ApprovalsPage = lazyPage(() => import("@/pages/approvals"), "ApprovalsPage");
const OperatorPage = lazyPage(() => import("@/pages/operator"), "OperatorPage");
const UpdatesPage = lazyPage(() => import("@/pages/updates"), "UpdatesPage");

// ── Groups ───────────────────────────────────────────────────────────
const GroupsPage = lazyPage(() => import("@/pages/groups"), "GroupsPage");
const GroupDetailPage = lazyPage(() => import("@/pages/group-detail"), "GroupDetailPage");
const GroupWizardPage = lazyPage(() => import("@/pages/group-wizard"), "GroupWizardPage");
const GroupTemplatesPage = lazyPage(() => import("@/pages/group-templates"), "GroupTemplatesPage");
const GroupWorkspacePage = lazyPage(() => import("@/pages/group-workspace"), "GroupWorkspacePage");

// ── Workforce ────────────────────────────────────────────────────────
const WorkforceDashboard = lazyPage(
  () => import("@/pages/workforce/workforce-dashboard"),
  "WorkforceDashboard",
);
const WorkforceWizard = lazyPage(
  () => import("@/pages/workforce/workforce-wizard"),
  "WorkforceWizard",
);
const WorkforceBoard = lazyPage(() => import("@/pages/workforce/workforce-board"), "WorkforceBoard");
const WorkforceThread = lazyPage(
  () => import("@/pages/workforce/workforce-thread"),
  "WorkforceThread",
);
const WorkforceSettings = lazyPage(
  () => import("@/pages/workforce/workforce-settings"),
  "WorkforceSettings",
);
const WorkforceHistory = lazyPage(
  () => import("@/pages/workforce/workforce-history"),
  "WorkforceHistory",
);
const WorkforceAnalytics = lazyPage(
  () => import("@/pages/workforce/workforce-analytics"),
  "WorkforceAnalytics",
);
const WorkforceChat = lazyPage(() => import("@/pages/workforce/workforce-chat"), "WorkforceChat");

/** Redirect /Workforce/* (capital W) → /workforce/* preserving sub-paths */
function WorkforceRedirect() {
  const { "*": rest } = useParams();
  const sub = rest ? `/${rest}` : "";
  return <Navigate to={`/workforce${sub}`} replace />;
}

export function App() {
  const location = useLocation();
  return (
    <ErrorBoundary resetKey={location.key}>
      {/*
        Outer boundary for the routes that render OUTSIDE a layout (the studio
        breakout). Routes inside a layout suspend at `SuspendedOutlet` instead,
        which keeps the surrounding chrome mounted — the inner boundary wins, so
        this one never fires for them.
      */}
      <Suspense fallback={<PageLoader />}>
        <Routes>
          {/* Landing — workspace chooser */}
          <Route path="/welcome" element={<LandingPage />} />
          <Route path="/" element={<Navigate to="/welcome" replace />} />

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

          {/* Legacy /Workforce (capital W) redirect → /workforce */}
          <Route path="/Workforce/*" caseSensitive element={<WorkforceRedirect />} />
          <Route path="/Workforce" caseSensitive element={<Navigate to="/workforce" replace />} />

          <Route element={<AppLayout />}>
            <Route path="/manage" element={<DashboardPage />} />
            <Route path="/manage/agents" element={<AgentsPage />} />
            <Route path="/manage/agents/wizard" element={<AgentWizardPage />} />
            <Route path="/manage/agentview/:id" element={<AgentDetailPage />} />
            <Route path="/manage/workflows" element={<WorkflowsPage />} />
            <Route path="/manage/workflowview/:id" element={<WorkflowDetailPage />} />
            <Route path="/manage/conversations" element={<ConversationsPage />} />
            <Route
              path="/manage/conversations/monitoring"
              element={<ConversationMonitoringPage />}
            />
            <Route path="/manage/operator" element={<OperatorPage />} />
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
            <Route path="/manage/groups/templates" element={<GroupTemplatesPage />} />
            <Route path="/manage/groups/:id" element={<GroupDetailPage />} />
            <Route path="/manage/groups/:id/workspace" element={<GroupWorkspacePage />} />
            <Route path="/manage/userdata" element={<UserDataPage />} />
            <Route path="/manage/triggers" element={<TriggersPage />} />
            <Route path="/manage/capabilities" element={<CapabilitiesPage />} />
            <Route path="/manage/sync" element={<SyncPage />} />
            <Route path="/manage/channels" element={<ChannelsPage />} />
            <Route path="/manage/channels/:id" element={<ChannelDetailPage />} />
            <Route path="/manage/approvals" element={<ApprovalsPage />} />
            <Route path="/manage/updates" element={<UpdatesPage />} />
            {/* Redirects from old standalone user-data pages */}
            <Route
              path="/manage/memories"
              element={<Navigate to="/manage/userdata?tab=memories" replace />}
            />
            <Route
              path="/manage/properties"
              element={<Navigate to="/manage/userdata?tab=properties" replace />}
            />
            <Route
              path="/manage/user-conversations"
              element={<Navigate to="/manage/userdata?tab=conversations" replace />}
            />
            <Route path="/manage/conversationview/:id" element={<ConversationDetailPage />} />
            <Route path="/manage/chat" element={<ChatPage />} />
            <Route path="/manage/resources" element={<ResourcesPage />} />
            <Route path="/manage/resources/:type" element={<ResourceListPage />} />
            <Route path="/manage/resources/:type/:id" element={<ResourceDetailPage />} />
          </Route>

          {/* Catch-all → welcome */}
          <Route path="*" element={<Navigate to="/welcome" replace />} />
        </Routes>
      </Suspense>
      <CommandPalette />
    </ErrorBoundary>
  );
}
