/* ──────────────────────────────────────────────
   EDDI Chat UI — Entry Point
   ────────────────────────────────────────────── */

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { ChatProvider } from "@/store/chat-store";
import { ChatApp } from "@/components/ChatApp";
import { ChatLocationProvider } from "@/components/ChatLocationProvider";
import { initLocale } from "@/i18n";
import "@/styles/chat.css";

// Before the first render: the copy helpers outside React read the locale too,
// and a non-English locale is its own chunk (src/i18n). Awaiting it is what
// keeps a German or Arabic user from seeing English first; it never rejects (a
// chunk that fails to load leaves English).
void initLocale(new URLSearchParams(window.location.search).get("lang")).then(() => {
  // No router: the widget's three URL shapes are parsed in src/location.ts.
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <ChatProvider>
        <ChatLocationProvider>
          <ChatApp />
        </ChatLocationProvider>
      </ChatProvider>
    </StrictMode>,
  );
});
