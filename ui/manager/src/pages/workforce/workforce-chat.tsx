import { useEffect } from "react";
import { markAgentUsed } from "@/hooks/use-recent-agents";
import { usePersistedBoolean } from "@/hooks/use-persisted-boolean";
import { Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  ChevronLeft,
  PanelRightClose,
  PanelRightOpen,
} from "lucide-react";
import { ChatPanel } from "@/components/chat/chat-panel";
import { useChatStore } from "@/hooks/use-chat";
import { AgentDetailsPanel } from "@/components/workforce/agent-details-panel";
import { cn } from "@/lib/utils";

/**
 * workforce-native chat page.
 * Renders ChatPanel inside WorkforceLayout with a rich right-side agent
 * details panel (toggleable). Users never leave the Workforce shell.
 */
export function WorkforceChat() {
  const { t } = useTranslation();
  const [showDetails, setShowDetails] = usePersistedBoolean("workforce-chat-details-panel", true);
  const [searchParams] = useSearchParams();

  // Read selected agent from Zustand store, fallback to URL searchParams ?agentId=, fallback to first deployed agent
  const selectedAgentId = useChatStore((s) => s.selectedAgentId);
  const selectedAgentName = useChatStore((s) => s.selectedAgentName);
  const effectiveAgentId = selectedAgentId || searchParams.get("agentId") || null;

  // Feeds the "last used" ordering on the Workforce dashboard. While a deep link
  // names a different agent than the one still selected, ChatPanel has not
  // resolved it yet; recording now would credit the previous agent.
  const urlAgentId = searchParams.get("agentId");
  useEffect(() => {
    if (!selectedAgentId) return;
    if (urlAgentId && urlAgentId !== selectedAgentId) return;
    markAgentUsed(selectedAgentId);
  }, [selectedAgentId, urlAgentId]);

  return (
    <div className="flex flex-1 min-h-0 flex-col overflow-hidden">
      {/* Header bar */}
      <div className="flex items-center gap-2 border-b border-border ps-4 pe-4 py-2 shrink-0">
        <Link
          to="/workforce"
          className="flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
          aria-label={t("Workforce.back", "Back")}
        >
          <ChevronLeft className="h-4 w-4 rtl:rotate-180" />
        </Link>
        <h2 className="text-sm font-semibold text-foreground flex-1">
          {t("Workforce.chat.title", "Chat")}
        </h2>

        {/* Details panel toggle */}
        <button
          type="button"
          onClick={() => setShowDetails((v) => !v)}
          className={cn(
            "flex h-7 w-7 items-center justify-center rounded-md transition-colors max-lg:hidden",
            showDetails
              ? "bg-primary/10 text-primary"
              : "text-muted-foreground hover:bg-muted hover:text-foreground",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          )}
          // Two keys, not one with a ternary default: a single key can hold only
          // one string, so the toggled state would have rendered the other
          // one's text in every locale.
          aria-label={
            showDetails
              ? t("Workforce.chat.hideAgentDetails", "Hide agent details")
              : t("Workforce.chat.showAgentDetails", "Show agent details")
          }
          aria-expanded={showDetails}
        >
          {showDetails ? (
            <PanelRightClose className="h-4 w-4" />
          ) : (
            <PanelRightOpen className="h-4 w-4" />
          )}
        </button>
      </div>

      {/* Main area — chat + optional right details panel */}
      <div className="flex flex-1 min-h-0">
        {/* Chat panel — fills remaining space. `min-w-0` for the same reason
            the board's transcript column carries it: without it one unbreakable
            line sizes this column to its content and pushes the details panel
            out of the window. */}
        <div className="flex-1 min-w-0 min-h-0">
          <ChatPanel />
        </div>

        {/* Right details panel */}
        {showDetails && (
          <AgentDetailsPanel
            agentId={effectiveAgentId}
            agentName={selectedAgentName}
            onClose={() => setShowDetails(false)}
          />
        )}
      </div>
    </div>
  );
}
