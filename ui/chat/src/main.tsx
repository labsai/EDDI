/* ──────────────────────────────────────────────
   EDDI Chat UI — Entry Point
   ────────────────────────────────────────────── */

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";

import { ChatProvider } from "@/store/chat-store";
import { ChatWidget } from "@/components/ChatWidget";
import "@/styles/chat.css";

function App() {
  return (
    <ChatProvider>
      <BrowserRouter>
        <Routes>
          {/* Managed bot route */}
          <Route
            path="/chat/managedbots/:intent/:userId"
            element={<ChatWidget />}
          />
          {/* Direct bot route with userId */}
          <Route
            path="/chat/:environment/:botId/:userId"
            element={<ChatWidget />}
          />
          {/* Direct bot route (userId via query param) */}
          <Route
            path="/chat/:environment/:botId"
            element={<ChatWidget />}
          />
          {/* Fallback */}
          <Route
            path="*"
            element={<Navigate to="/chat/unrestricted/default" replace />}
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
