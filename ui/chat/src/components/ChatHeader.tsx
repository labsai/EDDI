/* ──────────────────────────────────────────────
   ChatHeader — Branding (logo/title/bot name) +
   theme toggle. Undo/redo/restart moved to input area.
   ────────────────────────────────────────────── */

import { useChatState } from "@/store/chat-store";
import { useTheme, type ThemeMode } from "@/hooks/useTheme";

export function ChatHeader() {
  const { config, botName } = useChatState();
  const { setTheme } = useTheme(config.theme ?? "dark");

  const cycleTheme = () => {
    const current = document.documentElement.getAttribute("data-theme");
    const next: ThemeMode = current === "dark" ? "light" : "dark";
    setTheme(next);
  };

  // Determine what to show in the branding area
  const showLogo = config.showLogo !== false;
  const showBotName = config.showBotName !== false && !!botName;

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
        {showBotName && (
          <span className="chat-header__bot-name">{botName}</span>
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
