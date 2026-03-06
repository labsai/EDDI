import { Routes, Route, Navigate } from "react-router-dom";
import { AppLayout } from "@/components/layout/app-layout";
import { DashboardPage } from "@/pages/dashboard";
import { BotsPage } from "@/pages/bots";
import { PackagesPage } from "@/pages/packages";
import { ConversationsPage } from "@/pages/conversations";
import { ResourcesPage } from "@/pages/resources";

export function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/manage" element={<DashboardPage />} />
        <Route path="/manage/bots" element={<BotsPage />} />
        <Route path="/manage/botview/:id" element={<BotsPage />} />
        <Route path="/manage/packages" element={<PackagesPage />} />
        <Route path="/manage/packageview/:id" element={<PackagesPage />} />
        <Route path="/manage/conversations" element={<ConversationsPage />} />
        <Route
          path="/manage/conversationview/:id"
          element={<ConversationsPage />}
        />
        <Route path="/manage/resources" element={<ResourcesPage />} />
        <Route path="/" element={<Navigate to="/manage" replace />} />
        <Route path="*" element={<Navigate to="/manage" replace />} />
      </Route>
    </Routes>
  );
}
