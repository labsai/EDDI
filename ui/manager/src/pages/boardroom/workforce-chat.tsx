import { useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import {
  ChevronLeft,
  PanelRightClose,
  PanelRightOpen,
  Pencil,
  Bot,
} from "lucide-react";
import { ChatPanel } from "@/components/chat/chat-panel";
import { useChatStore } from "@/hooks/use-chat";
import { getAgent } from "@/lib/api/agents";
import { AdvisorAvatar } from "@/components/boardroom/advisor-avatar";
import { AgentEditorSheet } from "@/components/boardroom/agent-editor-sheet";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

/**
 * Workforce-native chat page.
 * Renders ChatPanel inside BoardroomLayout with an optional right-side agent
 * details panel (toggleable). Users never leave the Workforce shell.
 *
 * agentId is read from search params by ChatPanel itself.
 * The right panel reads the selected agent from useChatStore (Zustand).
 */
export function WorkforceChat() {
  const { t } = useTranslation();
  const [showDetails, setShowDetails] = useState(false);
  const [editingAgentId, setEditingAgentId] = useState<string | null>(null);

  // Read selected agent from the ChatPanel's shared store
  const selectedAgentId = useChatStore((s) => s.selectedAgentId);
  const selectedAgentName = useChatStore((s) => s.selectedAgentName);

  // Fetch full agent data for the details panel
  const { data: agentData, isLoading: agentLoading } = useQuery({
    queryKey: ["agent", selectedAgentId],
    queryFn: () => getAgent(selectedAgentId!),
    enabled: !!selectedAgentId && showDetails,
  });

  return (
    <div className="flex flex-1 min-h-0 flex-col overflow-hidden">
      {/* Header bar */}
      <div className="flex items-center gap-2 border-b border-border ps-4 pe-4 py-2 shrink-0">
        <Link
          to="/workforce"
          className="flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
          aria-label={t("boardroom.back", "Back")}
        >
          <ChevronLeft className="h-4 w-4" />
        </Link>
        <h2 className="text-sm font-semibold text-foreground flex-1">
          {t("boardroom.chat.title", "Chat")}
        </h2>

        {/* Details panel toggle */}
        {selectedAgentId && (
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
              "boardroom.chat.toggleDetails",
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
        )}
      </div>

      {/* Main area — chat + optional right panel */}
      <div className="flex flex-1 min-h-0">
        {/* Chat panel — fills remaining space */}
        <div className="flex-1 min-h-0">
          <ChatPanel />
        </div>

        {/* Right details panel */}
        {showDetails && selectedAgentId && (
          <div className="w-72 shrink-0 border-s border-border bg-card overflow-y-auto flex flex-col max-lg:hidden">
            {/* Panel header */}
            <div className="p-3 border-b border-border flex items-center justify-between shrink-0">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {t("boardroom.chat.agentDetails", "Agent Details")}
              </h3>
              <button
                type="button"
                onClick={() => setShowDetails(false)}
                className="p-0.5 rounded hover:bg-secondary/50 text-muted-foreground hover:text-foreground transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                aria-label={t(
                  "boardroom.chat.hideDetails",
                  "Hide details panel",
                )}
              >
                <PanelRightClose className="h-3.5 w-3.5" />
              </button>
            </div>

            {/* Panel content */}
            {agentLoading ? (
              <div className="p-4 space-y-4">
                <div className="flex flex-col items-center gap-2">
                  <Skeleton className="h-14 w-14 rounded-full" />
                  <Skeleton className="h-4 w-24" />
                </div>
                <Skeleton className="h-16 w-full" />
                <Skeleton className="h-8 w-full" />
              </div>
            ) : agentData ? (
              <div className="p-4 space-y-5">
                {/* Agent identity */}
                <div className="flex flex-col items-center text-center gap-2">
                  <AdvisorAvatar
                    name={selectedAgentName ?? agentData.name ?? "Agent"}
                    agentId={selectedAgentId}
                    size="lg"
                  />
                  <div>
                    <p className="text-sm font-semibold text-foreground">
                      {selectedAgentName ?? agentData.name}
                    </p>
                    {agentData.description && (
                      <p className="text-xs text-muted-foreground mt-0.5 line-clamp-3">
                        {agentData.description}
                      </p>
                    )}
                  </div>
                </div>

                {/* Capabilities */}
                {agentData.capabilities && agentData.capabilities.length > 0 && (
                  <div>
                    <h4 className="text-xs font-medium text-muted-foreground mb-2">
                      {t(
                        "boardroom.agentEditor.capabilities",
                        "Capabilities",
                      )}
                    </h4>
                    <div className="flex flex-wrap gap-1.5">
                      {agentData.capabilities.map((cap, idx) => (
                        <Badge
                          key={`${cap.skill}-${idx}`}
                          variant="secondary"
                          className="text-[10px]"
                        >
                          {cap.skill}
                        </Badge>
                      ))}
                    </div>
                  </div>
                )}

                {/* Settings indicators */}
                <div className="space-y-2">
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-muted-foreground">
                      {t(
                        "boardroom.agentEditor.a2aEnabled",
                        "Agent-to-Agent",
                      )}
                    </span>
                    <Badge
                      variant={agentData.a2aEnabled ? "success" : "secondary"}
                      className="text-[10px]"
                    >
                      {agentData.a2aEnabled
                        ? t("common.on", "On")
                        : t("common.off", "Off")}
                    </Badge>
                  </div>
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-muted-foreground">
                      {t(
                        "boardroom.agentEditor.memoryTools",
                        "Memory Tools",
                      )}
                    </span>
                    <Badge
                      variant={
                        agentData.enableMemoryTools ? "success" : "secondary"
                      }
                      className="text-[10px]"
                    >
                      {agentData.enableMemoryTools
                        ? t("common.on", "On")
                        : t("common.off", "Off")}
                    </Badge>
                  </div>
                </div>

                {/* Edit button */}
                <Button
                  variant="outline"
                  size="sm"
                  className="w-full"
                  onClick={() => setEditingAgentId(selectedAgentId)}
                >
                  <Pencil className="h-3.5 w-3.5" />
                  {t("boardroom.chat.editAgent", "Edit Agent")}
                </Button>
              </div>
            ) : (
              <div className="flex flex-1 items-center justify-center p-4">
                <div className="text-center text-muted-foreground">
                  <Bot className="h-8 w-8 mx-auto mb-2 opacity-40" />
                  <p className="text-xs">
                    {t(
                      "boardroom.chat.selectToView",
                      "Select an agent to view details",
                    )}
                  </p>
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Agent editor sheet (slide-over) */}
      <AgentEditorSheet
        agentId={editingAgentId}
        onClose={() => setEditingAgentId(null)}
      />
    </div>
  );
}
