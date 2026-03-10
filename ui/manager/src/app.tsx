import { Suspense, lazy } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import { AppLayout } from "@/components/layout/app-layout";
import { PageLoader } from "@/components/layout/page-loader";

// Route-based code splitting — each page loads on demand
const DashboardPage = lazy(() =>
  import("@/pages/dashboard").then((m) => ({ default: m.DashboardPage }))
);
const BotsPage = lazy(() =>
  import("@/pages/bots").then((m) => ({ default: m.BotsPage }))
);
const BotDetailPage = lazy(() =>
  import("@/pages/bot-detail").then((m) => ({ default: m.BotDetailPage }))
);
const PackagesPage = lazy(() =>
  import("@/pages/packages").then((m) => ({ default: m.PackagesPage }))
);
const PackageDetailPage = lazy(() =>
  import("@/pages/package-detail").then((m) => ({
    default: m.PackageDetailPage,
  }))
);
const ConversationsPage = lazy(() =>
  import("@/pages/conversations").then((m) => ({
    default: m.ConversationsPage,
  }))
);
const ConversationDetailPage = lazy(() =>
  import("@/pages/conversation-detail").then((m) => ({
    default: m.ConversationDetailPage,
  }))
);
const ChatPage = lazy(() =>
  import("@/pages/chat").then((m) => ({ default: m.ChatPage }))
);
const ResourcesPage = lazy(() =>
  import("@/pages/resources").then((m) => ({ default: m.ResourcesPage }))
);
const ResourceListPage = lazy(() =>
  import("@/pages/resource-list").then((m) => ({
    default: m.ResourceListPage,
  }))
);
const ResourceDetailPage = lazy(() =>
  import("@/pages/resource-detail").then((m) => ({
    default: m.ResourceDetailPage,
  }))
);
const BotWizardPage = lazy(() =>
  import("@/pages/bot-wizard").then((m) => ({ default: m.BotWizardPage }))
);

export function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route
          path="/manage"
          element={
            <Suspense fallback={<PageLoader />}>
              <DashboardPage />
            </Suspense>
          }
        />
        <Route
          path="/manage/bots"
          element={
            <Suspense fallback={<PageLoader />}>
              <BotsPage />
            </Suspense>
          }
        />
        <Route
          path="/manage/bots/wizard"
          element={
            <Suspense fallback={<PageLoader />}>
              <BotWizardPage />
            </Suspense>
          }
        />
        <Route
          path="/manage/botview/:id"
          element={
            <Suspense fallback={<PageLoader />}>
              <BotDetailPage />
            </Suspense>
          }
        />
        <Route
          path="/manage/packages"
          element={
            <Suspense fallback={<PageLoader />}>
              <PackagesPage />
            </Suspense>
          }
        />
        <Route
          path="/manage/packageview/:id"
          element={
            <Suspense fallback={<PageLoader />}>
              <PackageDetailPage />
            </Suspense>
          }
        />
        <Route
          path="/manage/conversations"
          element={
            <Suspense fallback={<PageLoader />}>
              <ConversationsPage />
            </Suspense>
          }
        />
        <Route
          path="/manage/conversationview/:id"
          element={
            <Suspense fallback={<PageLoader />}>
              <ConversationDetailPage />
            </Suspense>
          }
        />
        <Route
          path="/manage/chat"
          element={
            <Suspense fallback={<PageLoader />}>
              <ChatPage />
            </Suspense>
          }
        />
        <Route
          path="/manage/resources"
          element={
            <Suspense fallback={<PageLoader />}>
              <ResourcesPage />
            </Suspense>
          }
        />
        <Route
          path="/manage/resources/:type"
          element={
            <Suspense fallback={<PageLoader />}>
              <ResourceListPage />
            </Suspense>
          }
        />
        <Route
          path="/manage/resources/:type/:id"
          element={
            <Suspense fallback={<PageLoader />}>
              <ResourceDetailPage />
            </Suspense>
          }
        />
        <Route path="/" element={<Navigate to="/manage" replace />} />
        <Route path="*" element={<Navigate to="/manage" replace />} />
      </Route>
    </Routes>
  );
}
