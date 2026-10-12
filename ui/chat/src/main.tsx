/* ──────────────────────────────────────────────
   EDDI Chat UI — Entry Point
   ────────────────────────────────────────────── */

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";

import { ChatProvider } from "@/store/chat-store";
import { ChatWidget } from "@/components/ChatWidget";
import { initLocale } from "@/i18n";
import "@/styles/chat.css";

// Before the first render: the copy helpers outside React read the locale too.
initLocale(new URLSearchParams(window.location.search).get("lang"));

function App() {
  return (
    <ChatProvider>
      <BrowserRouter>
        <Routes>
          {/* Managed agent route */}
          <Route
            path="/chat/managed/:intent/:userId"
            element={<ChatWidget />}
          />
          {/* Direct agent route with userId */}
          <Route
            path="/chat/:environment/:agentId/:userId"
            element={<ChatWidget />}
          />
          {/* Direct agent route (userId via query param) */}
          <Route
            path="/chat/:environment/:agentId"
            element={<ChatWidget />}
          />
          {/* Fallback */}
          <Route
            path="*"
            element={<Navigate to="/chat/production/default" replace />}
          />
        </Routes>
      </BrowserRouter>
    </ChatProvider>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
