import { usePersistedBoolean } from "@/hooks/use-persisted-boolean";
import { Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  ChevronLeft,
  PanelRightClose,
  PanelRightOpen,
} from "lucide-react";
import { ChatPanel } from "@/components/chat/chat-panel";
import { useChatStore, useDeployedAgents } from "@/hooks/use-chat";
import { parseResourceUri } from "@/lib/api/agents";
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
  const { data: deployedAgents } = useDeployedAgents();

  // Read selected agent from Zustand store, fallback to URL searchParams ?agentId=, fallback to first deployed agent
  const selectedAgentId = useChatStore((s) => s.selectedAgentId);
  const selectedAgentName = useChatStore((s) => s.selectedAgentName);
  const effectiveAgentId =
    selectedAgentId ||
    searchParams.get("agentId") ||
    (deployedAgents?.[0] ? parseResourceUri(deployedAgents[0].resource).id : null);

  return (
    <div className="flex flex-1 min-h-0 flex-col overflow-hidden">
      {/* Header bar */}
      <div className="flex items-center gap-2 border-b border-border ps-4 pe-4 py-2 shrink-0">
        <Link
          to="/workforce"
          className="flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
          aria-label={t("Workforce.back", "Back")}
        >
          <ChevronLeft className="h-4 w-4" />
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
          aria-label={t(
            "Workforce.chat.toggleDetails",
            showDetails ? "Hide agent details" : "Show agent details",
          )}
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
        {/* Chat panel — fills remaining space */}
        <div className="flex-1 min-h-0">
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
