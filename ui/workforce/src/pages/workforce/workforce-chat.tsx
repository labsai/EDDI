import { useCallback, useEffect, useMemo } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { markAgentUsed } from "@/hooks/use-recent-agents";
import { usePersistedBoolean } from "@/hooks/use-persisted-boolean";
import { Link, useLocation, useNavigate, useSearchParams } from "react-router-dom";
import {
  readInitialChatMessage,
  workforceChatStart,
  type InitialChatMessage,
} from "@eddi/app-kit/lib/workforce-chat-start";
import { lastStepIndex } from "@eddi/core";
import { useAuth } from "@eddi/app-kit/hooks/use-auth";
import type { ChatMentionCandidate, ChatMentionConfig, ChatTranscriptMarker } from "@eddi/app-kit/lib/chat-mention";
import {
  addHandoff,
  completeHandoff,
  useWorkforceHandoffs,
} from "@eddi/app-kit/lib/workforce-handoffs";
import { useCatalogAgents, useCatalogGroups } from "@/hooks/use-catalog";
import { workforceTeamStart } from "@/lib/workforce-team-start";
import { mentionCandidates } from "@/lib/mention-candidates";
import { HandoffOriginMarker, HandoffTargetMarker } from "@/components/workforce/handoff-marker";
import { workforceKeys } from "@eddi/app-kit/lib/query-keys";
import { useTranslation } from "react-i18next";
import {
  ChevronLeft,
  PanelRightClose,
  PanelRightOpen,
} from "lucide-react";
import { ChatPanel } from "@eddi/app-kit/components/chat/chat-panel";
import { useChatStore } from "@eddi/app-kit/hooks/use-chat";
import { AgentDetailsPanel } from "@/components/workforce/agent-details-panel";
import { cn } from "@eddi/app-kit/lib/utils";
import { useWorkforceAccess } from "@eddi/app-kit/hooks/use-workforce-access";

/**
 * workforce-native chat page.
 * Renders ChatPanel inside WorkforceLayout with a rich right-side agent
 * details panel (toggleable). Users never leave the Workforce shell.
 */
export function WorkforceChat() {
  const { t } = useTranslation();
  const [showDetails, setShowDetails] = usePersistedBoolean("workforce-chat-details-panel", true);
  const [searchParams] = useSearchParams();
  // The details panel reads the agent and deployment stores — authors only.
  const { canAuthor } = useWorkforceAccess();

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

  // A first message from Home travels in router state. It is dropped from the
  // history entry as soon as the chat takes it, so a reload cannot send it again.
  const location = useLocation();
  const navigate = useNavigate();
  const initialMessage = useMemo(() => readInitialChatMessage(location.state), [location.state]);
  const conversationId = useChatStore((s) => s.conversationId);
  const dropInitialMessage = useCallback(() => {
    navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: null });
  }, [navigate, location.pathname, location.search]);

  // The sidebar lists this conversation with the title its first turn gives
  // it: re-read the list when a conversation opens and after each turn.
  const queryClient = useQueryClient();
  const isProcessing = useChatStore((s) => s.isProcessing);
  useEffect(() => {
    if (!conversationId || isProcessing) return;
    void queryClient.invalidateQueries({ queryKey: workforceKeys.chats });
  }, [conversationId, isProcessing, queryClient]);

  // ─── @mention hand-off (T2.2) ───────────────────────────
  // Who may be mentioned: the catalog's agents and teams, which list only what
  // the caller may USE — never the agent store. Not this chat's own agent.
  const { user } = useAuth();
  const principal = user?.username || user?.email || null;
  const { data: catalogAgents } = useCatalogAgents();
  const { data: catalogGroups } = useCatalogGroups();
  const candidates = useMemo(
    () => mentionCandidates(catalogAgents, catalogGroups, selectedAgentId),
    [catalogAgents, catalogGroups, selectedAgentId],
  );
  const groupVersions = useMemo(
    () => new Map((catalogGroups ?? []).map((g) => [g.groupId, g.version] as const)),
    [catalogGroups],
  );
  const messages = useChatStore((s) => s.messages);

  const handOff = useCallback(
    (target: ChatMentionCandidate, text: string) => {
      // Only from a running chat: the marker belongs in a conversation.
      if (!conversationId || !selectedAgentId) return false;
      const record = addHandoff(principal, {
        from: {
          agentId: selectedAgentId,
          agentName: selectedAgentName || selectedAgentId,
          conversationId,
          afterStep: lastStepIndex(messages),
        },
        to: {
          kind: target.kind,
          id: target.id,
          name: target.name,
          version: target.kind === "team" ? (groupVersions.get(target.id) ?? null) : null,
          conversationId: null,
        },
        question: text,
      });
      const next =
        target.kind === "team"
          ? workforceTeamStart(target.id, text, { version: groupVersions.get(target.id), handoffId: record.id })
          : workforceChatStart(target.id, text, { handoffId: record.id });
      navigate(next.to, { state: next.state });
      return true;
    },
    [conversationId, selectedAgentId, selectedAgentName, principal, messages, groupVersions, navigate],
  );
  const mention = useMemo<ChatMentionConfig | undefined>(
    () => (candidates.length > 0 ? { candidates, onHandOff: handOff } : undefined),
    [candidates, handOff],
  );

  // The new chat a hand-off started: link the hand-off to it.
  const linkHandoff = useCallback(
    (startedId: string, message: InitialChatMessage) => {
      if (message.handoffId) completeHandoff(principal, message.handoffId, startedId);
    },
    [principal],
  );

  // Markers: hand-offs made from this chat, after the turn they followed; and,
  // at the top, the hand-off this chat was started by.
  const handoffs = useWorkforceHandoffs(principal);
  const transcriptMarkers = useMemo<ChatTranscriptMarker[]>(() => {
    if (!conversationId) return [];
    const markers: ChatTranscriptMarker[] = [];
    for (const h of handoffs) {
      if (h.to.kind === "agent" && h.to.conversationId === conversationId) {
        markers.push({ id: `to-${h.id}`, afterStep: null, content: <HandoffTargetMarker handoff={h} /> });
      }
      if (h.from.conversationId === conversationId) {
        markers.push({ id: `from-${h.id}`, afterStep: h.from.afterStep, content: <HandoffOriginMarker handoff={h} /> });
      }
    }
    return markers;
  }, [handoffs, conversationId]);

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
        {canAuthor && (
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
        )}
      </div>

      {/* Main area — chat + optional right details panel */}
      <div className="flex flex-1 min-h-0">
        {/* Chat panel — fills remaining space. `min-w-0` for the same reason
            the board's transcript column carries it: without it one unbreakable
            line sizes this column to its content and pushes the details panel
            out of the window. */}
        <div className="flex-1 min-w-0 min-h-0">
          <ChatPanel
            urlSync
            initialMessage={initialMessage}
            onInitialMessageConsumed={dropInitialMessage}
            onInitialConversationStarted={linkHandoff}
            mention={mention}
            transcriptMarkers={transcriptMarkers}
          />
        </div>

        {/* Right details panel */}
        {canAuthor && showDetails && (
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
