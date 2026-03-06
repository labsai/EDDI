import { Routes, Route, Navigate } from "react-router-dom";
import { AppLayout } from "@/components/layout/app-layout";
import { DashboardPage } from "@/pages/dashboard";
import { BotsPage } from "@/pages/bots";
import { BotDetailPage } from "@/pages/bot-detail";
import { PackagesPage } from "@/pages/packages";
import { PackageDetailPage } from "@/pages/package-detail";
import { ConversationsPage } from "@/pages/conversations";
import { ConversationDetailPage } from "@/pages/conversation-detail";
import { ChatPage } from "@/pages/chat";
import { ResourcesPage } from "@/pages/resources";

export function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/manage" element={<DashboardPage />} />
        <Route path="/manage/bots" element={<BotsPage />} />
        <Route path="/manage/botview/:id" element={<BotDetailPage />} />
        <Route path="/manage/packages" element={<PackagesPage />} />
        <Route path="/manage/packageview/:id" element={<PackageDetailPage />} />
        <Route path="/manage/conversations" element={<ConversationsPage />} />
        <Route
          path="/manage/conversationview/:id"
          element={<ConversationDetailPage />}
        />
        <Route path="/manage/chat" element={<ChatPage />} />
        <Route path="/manage/resources" element={<ResourcesPage />} />
        <Route path="/" element={<Navigate to="/manage" replace />} />
        <Route path="*" element={<Navigate to="/manage" replace />} />
      </Route>
    </Routes>
  );
}

