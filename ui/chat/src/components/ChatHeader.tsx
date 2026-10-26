/* ──────────────────────────────────────────────
   ChatHeader — Branding (logo/title/agent name) +
   theme toggle. Undo/redo/restart moved to input area.
   ────────────────────────────────────────────── */

import { useEffect } from "react";
import { SunMoon } from "lucide-react";

import { useChatState } from "@/store/chat-store";
import { brandDocumentTitle, brandLogoUrl, brandTitle, getBrand } from "@/api/brand";
import { useTheme, nextTheme } from "@/hooks/useTheme";
import { t } from "@/i18n";

export function ChatHeader() {
  const { config, agentName } = useChatState();
  const { setTheme, getMode } = useTheme(config.theme ?? "dark");

  const cycleTheme = () => setTheme(nextTheme(getMode()));

  const brand = getBrand();
  // What nobody overrode: the product name when branded, else "EDDI".
  const defaultTitle = brandTitle(brand);
  const showLogo = config.showLogo !== false;
  const title = config.title?.trim() || defaultTitle;
  // `?title=` / config.title was never rendered. An explicit title is shown as
  // text (beside the logo, when there is one) and replaces the agent name;
  // with the logo hidden and no name, the title keeps the header from being
  // an empty bar.
  const titleOverridden = title !== defaultTitle;
  // An explicit logoUrl wins ("" means none); otherwise the brand's logo, or
  // null for a brand without one — never EDDI's logo under another name.
  const logoSrc = config.logoUrl !== undefined ? config.logoUrl || null : brandLogoUrl(brand);
  // No image to show: the title stands in for the logo, as a wordmark.
  const wordmark = showLogo && !logoSrc;
  const showAgentName =
    config.showAgentName !== false && !!agentName && !titleOverridden;
  const showTitleText = titleOverridden || wordmark || (!showLogo && !showAgentName);

  // The tab/window title, so a bookmark or a browser tab says what this is.
  useEffect(() => {
    const name = config.showAgentName !== false ? agentName : null;
    document.title = titleOverridden ? title : brandDocumentTitle(name);
  }, [title, titleOverridden, agentName, config.showAgentName]);

  return (
    <header className="chat-header">
      <div className="chat-header__branding">
        {showLogo && logoSrc && (
          <img
            className="chat-header__logo"
            src={logoSrc}
            alt={showTitleText ? "" : title}
          />
        )}
        {showTitleText && (
          <span
            className={
              wordmark && !titleOverridden
                ? "chat-header__title chat-header__title--wordmark"
                : "chat-header__title"
            }
            data-testid="chat-title"
          >
            {title}
          </span>
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
          title={t("theme.toggle")}
          aria-label={t("theme.toggle")}
          data-testid="theme-toggle"
        >
          <SunMoon size="1em" />
        </button>
      </div>
    </header>
  );
}
