/* ──────────────────────────────────────────────
   Entry of /chat/embed.js (T2.9): reads its own <script>'s data- attributes
   and draws the launcher. See launcher.ts for the contract and
   docs/chat-ui-embedding.md for the snippet.
   ────────────────────────────────────────────── */

import { mountLauncher, readConfig, type Launcher } from "./launcher";

declare global {
  interface Window {
    EddiChat?: Pick<Launcher, "open" | "close" | "toggle" | "setTokenProvider">;
  }
}

// Captured now: document.currentScript is only set while this script runs.
const script = document.currentScript as HTMLScriptElement | null;

function start() {
  if (!script) return;
  const cfg = readConfig(script, window);
  if (!cfg) return;
  const launcher = mountLauncher(cfg, window);
  if (!launcher) return;
  window.EddiChat = {
    open: launcher.open,
    close: launcher.close,
    toggle: launcher.toggle,
    setTokenProvider: launcher.setTokenProvider,
  };
}

if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start, { once: true });
else start();
