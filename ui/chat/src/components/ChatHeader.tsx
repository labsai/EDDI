/* ──────────────────────────────────────────────
   ChatHeader — Branding (logo/title/agent name) +
   theme toggle. Undo/redo/restart moved to input area.
   ────────────────────────────────────────────── */

import { useChatState } from "@/store/chat-store";
import { useTheme, type ThemeMode } from "@/hooks/useTheme";

export function ChatHeader() {
  const { config, agentName } = useChatState();
  const { setTheme } = useTheme(config.theme ?? "dark");

  const cycleTheme = () => {
    const current = document.documentElement.getAttribute("data-theme");
    const next: ThemeMode = current === "dark" ? "light" : "dark";
    setTheme(next);
  };

  // Determine what to show in the branding area
  const showLogo = config.showLogo !== false;
  const showAgentName = config.showAgentName !== false && !!agentName;

  return (
    <header className="chat-header">
      <div className="chat-header__branding">
        {showLogo && (
          <img
            className="chat-header__logo"
            src={config.logoUrl ?? "/img/logo_eddi.png"}
            alt={config.title ?? "EDDI"}
          />
        )}
        {showAgentName && (
          <span className="chat-header__agent-name">{agentName}</span>
        )}
      </div>

      <div className="chat-header__actions">
        {/* Theme toggle */}
        <button
          className="chat-header__btn"
          onClick={cycleTheme}
          title="Toggle theme"
          data-testid="theme-toggle"
        >
          ◑
        </button>
      </div>
    </header>
  );
}
