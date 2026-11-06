import { ReviewNotice } from "./review-notice";
import { useAgentReviewProfile } from "../../hooks/use-agent-review-profile";
import { Fragment, useEffect, useId, useRef, useState, useCallback, useMemo, type ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { Environment } from "../../lib/constants";
import { preferredChatEnvironment } from "../../lib/deployment-environments";
import { toast } from "sonner";
import { getErrorMessage } from "../../lib/api-client";
import {
  chatConversation,
  useChatStore,
  useDeployedAgents,
  useStartConversation,
  useResumeOrStartConversation,
  useLoadConversation,
  useSendMessage,
  useEndConversation,
  useUndoConversation,
  useRedoConversation,
  useRerunConversation,
  chatToolActivity,
} from "../../hooks/use-chat";
import type { SentAttachment } from "../../hooks/use-chat";
import {
  filesFromClipboard,
  useAttachmentStaging,
  useFileDrop,
  type PendingAttachment,
  type ReadyAttachment,
} from "../../hooks/use-attachment-staging";
import { FileDropOverlay, PendingAttachmentChip } from "./attachment-chip";
import { ChatMessage } from "./chat-message";
import { ChatRatingCard } from "./chat-rating";
import { useChatRatings } from "../../hooks/use-chat-ratings";
import { lastStepIndex, ratableSteps } from "@eddi/core";
import { ReplyAnnouncer, type AnnouncedMessage } from "@eddi/conversation";
import {
  ToolActivityCards,
  ToolTurnNotice,
  formatToolList,
  toolCardPositions,
  toolNames,
  useToolTurns,
} from "@eddi/conversation/tool-activity";
import { ChatActivity } from "./chat-activity";
import { GuardrailActivity } from "./guardrail-activity";
import { ChatHistory } from "./chat-history";
import { StreamingToggle } from "./streaming-toggle";
import { DebugDrawer } from "../debugger/debug-drawer";
import { useDebugStore, isInternalTask, type PipelineEvent } from "../../hooks/use-debug-events";
import { useSmartAutoScroll } from "../../hooks/use-smart-auto-scroll";
import { cn } from "../../lib/utils";
import { isImeComposing } from "../../lib/ime";
import { isSafePathSegment } from "../../lib/api/path-segment";
import { InputHint } from "./input-hint";
import { PauseNotice } from "../hitl/pause-notice";
import { SecretInputField } from "./secret-input-field";
import type { InitialChatMessage } from "../../lib/workforce-chat-start";
import {
  Bot,
  ChevronDown,
  History,
  StopCircle,
  MessageSquarePlus,
  Loader2,
  Undo2,
  Redo2,
  Lock,
  Unlock,
  Eye,
  EyeOff,
  Send,
  Paperclip,
  RefreshCw,
  Activity,
  ArrowDown,
  Info,
  ChevronUp,
  Hash,
  Clock,
  Wrench,
  ListOrdered,
  Star,
  Square,
  WifiOff,
  Pencil,
} from "lucide-react";

export interface ChatPanelProps {
  embedded?: boolean;
  /**
   * Keep the open conversation in the address bar (`?agentId=…&conversationId=…`),
   * so a reload or a second tab returns to it (T1.15). Workforce sets it; the
   * Manager's chat clears the parameters once read, as before.
   */
  urlSync?: boolean;
  /**
   * A first message to send in a NEW conversation with `agentId` once that
   * agent is selected — Workforce Home's composer and starters. Comes from
   * router state, never from the URL (see `workforce-chat-start.ts`).
   */
  initialMessage?: InitialChatMessage | null;
  /** Called once the initial message has been taken, so the host can drop it. */
  onInitialMessageConsumed?: () => void;
  /**
   * A notice beside "Edit" and "Regenerate" on the last turn. Undo and rerun
   * take back the reply, never what a tool did, so by default a turn that ran
   * tools says which (T1.6, from the stream's `tool_result` events — the
   * turns this page watched; see `chatToolActivity`). Pass this to say
   * something else; returning null shows nothing.
   */
  renderTurnNotice?: (context: TurnNoticeContext) => ReactNode;
}

/** What the last turn's Edit / Regenerate notice is about. */
export interface TurnNoticeContext {
  action: "edit" | "regenerate";
  /** The step being taken back or rerun, when the transcript knows it. */
  stepIndex: number | null;
}

/** What the secret bubble shows: never offered for editing. */
const MASKED_BUBBLE = "●●●●●●●●";

/** The query parameters as one comparable value. */
function paramKey(agentId: string | null, conversationId: string | null): string {
  return `${agentId ?? ""}|${conversationId ?? ""}`;
}

export function ChatPanel({
  embedded = false,
  urlSync = false,
  initialMessage = null,
  onInitialMessageConsumed,
  renderTurnNotice,
}: ChatPanelProps = {}) {
  const { t, i18n } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const [historyOpen, setHistoryOpen] = useState(false);
  const [agentSelectorOpen, setAgentSelectorOpen] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const agentSelectorRef = useRef<HTMLDivElement>(null);
  const agentTriggerRef = useRef<HTMLButtonElement>(null);
  const agentSearchRef = useRef<HTMLInputElement>(null);
  const [agentFilter, setAgentFilter] = useState("");
  const [agentHighlight, setAgentHighlight] = useState(0);
  const agentListboxId = useId();

  const [contextOpen, setContextOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  /** Put the caret back in the composer: after a send, a new chat, Stop. */
  const focusComposer = useCallback(() => {
    requestAnimationFrame(() => {
      rootRef.current?.querySelector<HTMLElement>('[data-testid="chat-input"]')?.focus();
    });
  }, []);

  // Store state
  const messages = useChatStore((s) => s.messages);
  const selectedAgentId = useChatStore((s) => s.selectedAgentId);
  const selectedAgentName = useChatStore((s) => s.selectedAgentName);
  const conversationId = useChatStore((s) => s.conversationId);
  const isProcessing = useChatStore((s) => s.isProcessing);
  const isThinking = useChatStore((s) => s.isThinking);
  // Undo, redo and rerun raise isProcessing too; only a turn being answered can be stopped.
  const historyBusy = useChatStore((s) => s.historyBusy);
  const reconnecting = useChatStore((s) => s.reconnecting);
  const isPaused = useChatStore((s) => s.isPaused);
  const pauseReason = useChatStore((s) => s.pauseReason);
  const pausedAt = useChatStore((s) => s.pausedAt);
  const undoAvailable = useChatStore((s) => s.undoAvailable);
  const redoAvailable = useChatStore((s) => s.redoAvailable);
  const quickReplies = useChatStore((s) => s.quickReplies);
  const setSelectedAgent = useChatStore((s) => s.setSelectedAgent);
  const activeInputField = useChatStore((s) => s.activeInputField);
  const isSecretMode = useChatStore((s) => s.isSecretMode);
  const toggleSecretMode = useChatStore((s) => s.toggleSecretMode);
  // A conversation load is reading: the transcript and its id still belong to
  // the conversation being left, so nothing may be sent until it settles.
  const isLoadingConversation = useChatStore((s) => s.loadingConversationId !== null);

  // Activity display
  const showActivity = useDebugStore((s) => s.showActivity);
  const toggleShowActivity = useDebugStore((s) => s.toggleShowActivity);
  const currentTurnEvents = useDebugStore((s) => s.currentTurnEvents);
  const liveToolCalls = useDebugStore((s) => s.liveToolCalls);
  const liveToolsSettled = useDebugStore((s) => s.liveToolsSettled);

  // Queries & mutations
  const { data: deployedAgents, isLoading: agentsLoading } = useDeployedAgents();
  const startConversation = useStartConversation();
  // Opening a chat reopens the last conversation; only "New Conversation"
  // deliberately starts a fresh one.
  const openConversation = useResumeOrStartConversation();
  const loadConversation = useLoadConversation();
  const sendMessage = useSendMessage();
  const endConversation = useEndConversation();
  const undoConversation = useUndoConversation();
  const redoConversation = useRedoConversation();
  const stepMovePending = undoConversation.isPending || redoConversation.isPending;

  // Open the chat for the ?agentId= query param
  /**
   * The environment to open a conversation in for a given agent: production
   * when it is live there, otherwise wherever it actually is.
   *
   * Every chat entry point used to assume production — and the value was then
   * dropped on the wire entirely — so an agent deployed only to `test` could be
   * neither listed nor talked to.
   */
  const environmentFor = useCallback(
    (agentId: string): Environment => {
      return preferredChatEnvironment(
        deployedAgents?.find((a) => a.id === agentId)?.environments ?? [],
      );
    },
    [deployedAgents],
  );

  const chatEnvironment = environmentFor(selectedAgentId ?? "");
  // Every send waits while the review lookup is out, so nobody commits a turn an
  // opted-in agent's maintainers may read before being told so. A failed lookup
  // settles it: the chat waits for the request, never blocks on it.
  const {
    pending: reviewPending,
    rating: agentRatingProfile,
    controls,
  } = useAgentReviewProfile(selectedAgentId, chatEnvironment);
  // The agent's conversationControls (B4): a control it switched off is not
  // offered here either, so a designer sees what the agent's users see.
  const ratingProfile = controls.rating ? agentRatingProfile : null;
  useEffect(() => {
    useChatStore.getState().setAgentStreaming(controls.streaming);
  }, [controls.streaming]);
  // The same rating controls the Chat UI shows, so designers can try the flow.
  const ratings = useChatRatings(conversationId, ratingProfile, messages);
  const ratableByPosition = useMemo(() => ratableSteps(messages), [messages]);

  // The URL pair this panel has acted on (urlSync), and a conversation the URL
  // named that is still being read.
  const handledParamsRef = useRef<string | null>(null);
  const awaitingConversationRef = useRef<string | null>(null);

  useEffect(() => {
    const rawAgentIdParam = searchParams.get("agentId");
    if (!rawAgentIdParam) return;
    // Both ids end up in request paths (`GET /agents/<conversationId>`), so a
    // crafted link carrying `../`, `%2F` or whitespace is dropped here instead of
    // being passed on to retarget the call. See lib/api/path-segment.ts.
    const agentIdParam = isSafePathSegment(rawAgentIdParam) ? rawAgentIdParam : null;
    if (!agentIdParam) {
      setSearchParams({}, { replace: true });
      return;
    }
    // "Continue in Chat" names the exact conversation to continue. It used to
    // be ignored, so the chat reopened the agent's most recent conversation
    // instead — a different one whenever the user had continued from history.
    const rawConversationIdParam = searchParams.get("conversationId");
    const conversationIdParam =
      rawConversationIdParam && isSafePathSegment(rawConversationIdParam)
        ? rawConversationIdParam
        : null;

    // Wait for the deployed-agents list before acting, so the display name can be
    // resolved from it (below) rather than falling back to the id and then losing
    // the chance once the param is cleared.
    if (deployedAgents === undefined) return;

    // With the URL kept in sync, the parameters stay in the address bar, so this
    // effect sees them again on every render — and after the user picked another
    // agent, before the URL follows. Act on each pair once.
    const key = paramKey(agentIdParam, conversationIdParam);
    if (urlSync && handledParamsRef.current === key) return;

    // Deep links only PRESELECT the agent; they never start or reopen a
    // conversation on their own. Auto-starting from URL params let a crafted
    // link (e.g. in agent studio, /manage/chat, /workforce/chat) silently open a
    // conversation AS THE ADMIN the moment the page loaded — a CSRF-style side
    // effect. Starting is now always an explicit user action (agent picker,
    // "New conversation", or sending a message).
    if (agentIdParam !== selectedAgentId) {
      // The display name is resolved ONLY from the deployed-agents list, never
      // from the URL: an attacker-supplied ?agentName= must not be reflected
      // into the UI. Falls back to the id when the agent is not listed.
      const agentName =
        deployedAgents.find((b) => b.id === agentIdParam)?.name || agentIdParam;
      setSelectedAgent(agentIdParam, agentName);
    }

    // A named conversation is only READ (the same GET the history list uses to
    // open one): nothing is started, resumed or sent, so the rule above holds.
    // Skipped when it is already the open one.
    if (conversationIdParam && conversationIdParam !== useChatStore.getState().conversationId) {
      awaitingConversationRef.current = conversationIdParam;
      loadConversation.mutate(
        { agentId: agentIdParam, conversationId: conversationIdParam },
        {
          onError: (err) => toast.error(getErrorMessage(err)),
          onSettled: () => {
            if (awaitingConversationRef.current === conversationIdParam) awaitingConversationRef.current = null;
          },
        },
      );
    }

    if (urlSync) {
      // Keep the two ids — a reload or a second tab opens the same
      // conversation (T1.15) — and drop the (ignored) agentName.
      handledParamsRef.current = key;
      if (searchParams.has("agentName")) {
        const next = new URLSearchParams();
        next.set("agentId", agentIdParam);
        if (conversationIdParam) next.set("conversationId", conversationIdParam);
        setSearchParams(next, { replace: true });
      }
      return;
    }

    // Remove query params so a refresh does not re-trigger, and so the
    // (ignored) agentName does not linger in the URL.
    setSearchParams({}, { replace: true });
  }, [searchParams, deployedAgents, selectedAgentId, setSelectedAgent, loadConversation, setSearchParams, urlSync]);

  // T1.15 — the other direction: the conversation on screen goes into the
  // address bar, so a reload or a second tab returns to it. Only once the URL's
  // own parameters were acted on (above), and never while a conversation the
  // URL named is still loading, or the half-switched store would overwrite it.
  useEffect(() => {
    if (!urlSync || deployedAgents === undefined) return;
    const urlAgentId = searchParams.get("agentId");
    const urlKey = paramKey(urlAgentId, searchParams.get("conversationId"));
    if (urlAgentId && handledParamsRef.current !== urlKey) return;
    // Read live: the effect above may have switched the agent in this same
    // commit, and this render's values would still be the previous one's.
    const live = useChatStore.getState();
    if (live.loadingConversationId || !live.selectedAgentId) return;
    if (awaitingConversationRef.current) {
      if (awaitingConversationRef.current !== live.conversationId) return;
      awaitingConversationRef.current = null;
    }
    const storeKey = paramKey(live.selectedAgentId, live.conversationId);
    if (storeKey === urlKey) return;
    handledParamsRef.current = storeKey;
    const next = new URLSearchParams();
    next.set("agentId", live.selectedAgentId);
    if (live.conversationId) next.set("conversationId", live.conversationId);
    setSearchParams(next, { replace: true });
  }, [urlSync, deployedAgents, searchParams, isLoadingConversation, selectedAgentId, conversationId, setSearchParams]);

  // Workforce Home handed over a first message: start a NEW conversation with
  // that agent and send it, once the agent is selected and its review notice
  // has been looked up (Home showed the notice beside its composer).
  const initialTakenRef = useRef(false);
  useEffect(() => {
    if (!initialMessage || initialTakenRef.current) return;
    if (deployedAgents === undefined || reviewPending) return;
    if (useChatStore.getState().selectedAgentId !== initialMessage.agentId) return;
    initialTakenRef.current = true;
    onInitialMessageConsumed?.();
    useChatStore.getState().clearMessages();
    startConversation
      .mutateAsync({ agentId: initialMessage.agentId, environment: environmentFor(initialMessage.agentId) })
      .then((started) => {
        // Installed only if nothing replaced the transcript meanwhile.
        if (useChatStore.getState().conversationId !== started) return;
        sendMessage.mutate({ message: initialMessage.text });
      })
      .catch((err) => toast.error(getErrorMessage(err)));
  }, [initialMessage, deployedAgents, reviewPending, selectedAgentId, onInitialMessageConsumed, startConversation, sendMessage, environmentFor]);

  // Smart auto-scroll: auto scrolls when at bottom, pauses when user scrolls up
  const {
    scrollRef: scrollContainerRef,
    showScrollFab,
    hasNewContent,
    scrollToBottom,
    handleScroll,
  } = useSmartAutoScroll<HTMLDivElement>({
    deps: [messages, isProcessing, isThinking, currentTurnEvents.length],
    bottomThreshold: 80,
  });

  // Close agent selector on outside click
  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (
        agentSelectorRef.current &&
        !agentSelectorRef.current.contains(e.target as Node)
      ) {
        setAgentSelectorOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, []);


  // Agents matching the selector's search box (name, id or description).
  const visibleAgents = useMemo(() => {
    const q = agentFilter.trim().toLowerCase();
    if (!q) return deployedAgents ?? [];
    return (deployedAgents ?? []).filter(
      (a) =>
        a.name?.toLowerCase().includes(q) ||
        a.id.toLowerCase().includes(q) ||
        a.description?.toLowerCase().includes(q),
    );
  }, [deployedAgents, agentFilter]);

  const openAgentSelector = useCallback(() => {
    setAgentFilter("");
    const idx = (deployedAgents ?? []).findIndex((a) => a.id === selectedAgentId);
    setAgentHighlight(Math.max(0, idx));
    setAgentSelectorOpen(true);
  }, [deployedAgents, selectedAgentId]);

  const closeAgentSelector = useCallback((returnFocus: boolean) => {
    setAgentSelectorOpen(false);
    if (returnFocus) agentTriggerRef.current?.focus();
  }, []);

  // Focus the search box when the list opens.
  useEffect(() => {
    if (agentSelectorOpen) agentSearchRef.current?.focus();
  }, [agentSelectorOpen]);

  // Keep the highlighted option scrolled into view.
  useEffect(() => {
    if (!agentSelectorOpen) return;
    document
      .getElementById(`${agentListboxId}-opt-${agentHighlight}`)
      ?.scrollIntoView?.({ block: "nearest" });
  }, [agentSelectorOpen, agentHighlight, agentListboxId]);

  const handleSelectAgent = useCallback(
    (agentId: string, agentName: string) => {
      setSelectedAgent(agentId, agentName);
      setAgentSelectorOpen(false);
      openConversation.mutate(
        { agentId, environment: environmentFor(agentId) },
        { onError: (err) => toast.error(getErrorMessage(err)) },
      );
      // Focus the chat input after the dropdown closes and UI settles
      setTimeout(() => {
        const input = document.querySelector<HTMLTextAreaElement>('[data-testid="chat-input"]');
        input?.focus();
      }, 150);
    },
    [setSelectedAgent, openConversation, environmentFor]
  );

  /** Explicit fresh start — the one path that always creates a conversation. */
  const handleNewConversation = useCallback(() => {
    if (!selectedAgentId) return;
    useChatStore.getState().clearMessages();
    startConversation.mutate(
      { agentId: selectedAgentId, environment: chatEnvironment },
      { onSuccess: focusComposer, onError: (err) => toast.error(getErrorMessage(err)) },
    );
  }, [selectedAgentId, startConversation, chatEnvironment, focusComposer]);

  // ── Attachment upload (shared staging hook — also used by the operator) ──
  const fileInputRef = useRef<HTMLInputElement>(null);
  const {
    pendingAttachments,
    isUploading,
    hasReadyAttachment,
    stageFiles,
    handleFileInput,
    removeAttachment,
    discardAll,
    retryAttachment,
    takeForSend,
  } = useAttachmentStaging(conversationId);

  // Secret mode and attachments are mutually exclusive — a masked turn must not
  // carry a file. Discard anything staged the moment secret mode switches on.
  useEffect(() => {
    if (isSecretMode) discardAll();
  }, [isSecretMode, discardAll]);

  /** Returns whether a send was issued. */
  const handleSend = useCallback(
    (message: string, isSecret?: boolean): boolean => {
      // Read live, not from the render: a send racing the load's first render
      // must not reach the conversation being left (useSendMessage refuses it
      // too, but only after the attachments below were already drained).
      if (useChatStore.getState().loadingConversationId) return false;
      // Nothing is sent before the review notice has had its chance to appear.
      if (reviewPending) return false;
      // Secret turns never carry attachments — a masked bubble must not leak a
      // filename or thumbnail. Discard anything staged (freeing previews and
      // best-effort deleting the blob) instead of forwarding or displaying it.
      if (isSecret) {
        if (!message.trim()) return false; // nothing to send once attachments are dropped
        discardAll();
        sendMessage.mutate({ message, isSecret: true });
        return true;
      }

      // Guard BEFORE draining the staging area: a no-op send must not clear
      // the user's staged chips.
      if (!message.trim() && !hasReadyAttachment) return false;

      // Forward only successfully-uploaded attachments as context this turn.
      const sent: SentAttachment[] = takeForSend().map((a: ReadyAttachment) => ({
        storageRef: a.result.storageRef,
        fileName: a.result.fileName || a.file.name,
        mimeType: a.result.mimeType || a.file.type || "application/octet-stream",
        sizeBytes: a.result.sizeBytes ?? a.file.size,
        forwardableInline: a.result.forwardableInline,
        previewUrl: a.previewUrl,
      }));

      sendMessage.mutate({
        message,
        isSecret,
        attachments: sent.length ? sent : undefined,
      });
      return true;
    },
    [sendMessage, takeForSend, discardAll, hasReadyAttachment, reviewPending]
  );

  const handleQuickReply = useCallback(
    (reply: string) => {
      if (useChatStore.getState().loadingConversationId || reviewPending) return;
      sendMessage.mutate({ message: reply });
    },
    [sendMessage, reviewPending]
  );

  // Pasted files (screenshots via Ctrl/Cmd+V) go through the same staging as
  // the picker. No-conversation and secret-mode cases are handled at the input.
  const handlePasteFiles = useCallback(
    (files: File[]) => {
      if (!conversationId) return;
      void stageFiles(files);
    },
    [conversationId, stageFiles],
  );

  // Dropping files anywhere on the chat area stages them, same as the picker
  // and paste. Secret mode blocks it — a masked turn must not carry a file.
  const { isDragOver, dropHandlers } = useFileDrop(
    Boolean(conversationId) && !isSecretMode && controls.attachments,
    handlePasteFiles,
  );

  // ── Rerun last step ──
  const rerunConversation = useRerunConversation();
  // Undo, redo and rerun each finish by replacing the whole transcript with a
  // fresh read. While one is in flight nothing else may change the
  // conversation — a send, quick reply or other move racing it would either be
  // wiped by that read or be read half-done — so every entry point below waits.
  const conversationBusy = stepMovePending || rerunConversation.isPending;
  const lastMessage = messages[messages.length - 1];
  const showRerun = lastMessage?.role === "agent" && lastMessage.isError === true;

  // ── Stop ──
  // Aborts the stream and asks the server to cancel the turn; the controller
  // then checks that it took, and says so when it did not (a cluster can run
  // the turn on another pod — docs/hitl.md).
  const canStop = Boolean(conversationId) && isProcessing && !historyBusy && !conversationBusy;
  const handleStop = useCallback(() => {
    void chatConversation.stop();
    focusComposer();
  }, [focusComposer]);

  // Esc stops a running turn: from anywhere in the panel, or from the page
  // itself when nothing else has focus. An open dialog or menu keeps its Esc.
  useEffect(() => {
    if (!canStop) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || e.defaultPrevented || isImeComposing(e)) return;
      const target = e.target as Node | null;
      const inPanel = !!target && !!rootRef.current?.contains(target);
      if (!inPanel && target !== document.body) return;
      if (document.querySelector('[role="dialog"][aria-modal="true"], [role="alertdialog"]')) return;
      e.preventDefault();
      handleStop();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [canStop, handleStop]);

  /** "Support Bot said:" — what the screen reader hears before a reply. */
  const speaker = useCallback(
    () =>
      t("conversation.speaker.agent", "{{name}} said:", {
        name: selectedAgentName?.trim() || t("conversation.speaker.agentDefault", "Assistant"),
      }),
    [t, selectedAgentName],
  );

  // ── Edit the last message, regenerate the last reply (T1.6) ──
  // Edit is undo + say: the last step is taken back and its text put into the
  // composer to be changed and sent again. Regenerate reruns the last step.
  // Both only where the agent allows undo/redo (conversationControls, B4).
  // Neither takes back what a tool did — see `renderTurnNotice`.
  const [draft, setDraft] = useState<{ text: string; nonce: number } | null>(null);
  const lastTurn = useMemo(() => {
    let user = -1;
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i]!.role === "user") {
        user = i;
        break;
      }
    }
    let reply = -1;
    for (let i = messages.length - 1; i > user; i--) {
      const m = messages[i]!;
      if (m.role === "agent" && m.kind !== "notice" && !m.isError && m.content) {
        reply = i;
        break;
      }
    }
    return { user, reply };
  }, [messages]);
  const lastUser = lastTurn.user >= 0 ? messages[lastTurn.user] : undefined;
  const turnIdle = Boolean(conversationId) && !isProcessing && !conversationBusy && !isPaused && !isLoadingConversation && !reviewPending;
  const canEditLast =
    controls.undo &&
    turnIdle &&
    undoAvailable &&
    !!lastUser &&
    !!lastUser.content.trim() &&
    lastUser.content !== MASKED_BUBBLE &&
    // Its files are not re-sent by an edit; say nothing that would lose them.
    !lastUser.attachments?.length;
  const canRegenerate = controls.undo && controls.redo && turnIdle && lastTurn.reply >= 0;

  // The tool calls of the turns this page watched (T1.9), and where their cards go.
  const toolTurns = useToolTurns(chatToolActivity, conversationId);
  const toolCardsAt = useMemo(() => toolCardPositions(messages, toolTurns), [messages, toolTurns]);
  const turnNoticeId = useId();

  const handleEditLast = useCallback(() => {
    if (!canEditLast || !lastUser) return;
    const text = lastUser.content;
    // Read before the undo takes the turn off the screen: once the question
    // is back in the composer, the notice beside Edit is gone with it.
    const ranTools = toolNames(toolTurns.get(lastStepIndex(messages) ?? -1));
    undoConversation.mutate(undefined, {
      onSuccess: (moved) => {
        if (moved === false) return;
        setDraft({ text, nonce: Date.now() });
        if (ranTools.length > 0) {
          const tools = formatToolList(ranTools, i18n.language, (n) =>
            t("toolActivity.more", "+{{n}} more", { n }),
          );
          toast.warning(
            t("toolActivity.notice.edited", "Your message is back in the composer. What {{tools}} did was not undone.", {
              tools,
            }),
          );
        }
      },
      onError: (err) => toast.error(getErrorMessage(err)),
    });
  }, [canEditLast, lastUser, undoConversation, toolTurns, messages, i18n.language, t]);

  /** By default: which tools the turn ran, when it ran any (T1.6). */
  const turnNotice = (context: TurnNoticeContext): ReactNode => {
    if (renderTurnNotice) return renderTurnNotice(context);
    const activity = context.stepIndex === null ? undefined : toolTurns.get(context.stepIndex);
    if (toolNames(activity).length === 0) return null;
    return <ToolTurnNotice activity={activity} action={context.action} id={`${turnNoticeId}-${context.action}`} />;
  };

  const clearRatedStep = ratings.clearStep;
  const handleRerun = useCallback(() => {
    // A rerun replaces the last reply, so its thumb no longer applies (locally;
    // the server keeps the stored rating — no hook in the rerun path).
    const rerunStep = lastStepIndex(messages);
    rerunConversation.mutate(undefined, {
      onSuccess: () => {
        if (rerunStep !== null) clearRatedStep(rerunStep);
        toast.success(t("chat.retrySuccess", "Step re-executed"));
      },
      onError: () => toast.error(t("chat.retryError", "Retry failed")),
    });
  }, [rerunConversation, t, messages, clearRatedStep]);

  return (
    <div ref={rootRef} className={cn(
      "flex h-full min-w-0 overflow-hidden bg-background",
      !embedded && "rounded-xl border border-border shadow-sm"
    )}>
      {/* History panel — hidden in embedded mode (no room for nested side panels) */}
      {!embedded && (
        <ChatHistory
          open={historyOpen}
          onNewConversation={handleNewConversation}
        />
      )}

      {/* Main chat area — also the file drop zone */}
      <div className="relative flex flex-1 flex-col min-w-0 min-h-0" {...dropHandlers}>
        {isDragOver && <FileDropOverlay />}
        {/* Top bar */}
        <div className={cn(
          "flex items-center border-b border-border",
          embedded ? "gap-1 px-2 py-1.5" : "gap-2 px-4 py-2.5"
        )}>
          {/* History toggle — hidden in embedded mode */}
          {!embedded && (
            <button
              onClick={() => setHistoryOpen((p) => !p)}
              className={cn(
                "flex h-9 w-9 items-center justify-center rounded-lg transition-colors",
                historyOpen
                  ? "bg-primary/10 text-primary"
                  : "text-muted-foreground hover:bg-muted hover:text-foreground"
              )}
              title={t("chat.history")}
              data-testid="history-toggle"
            >
              <History className="h-4 w-4" />
            </button>
          )}

          {/* Agent selector */}
          <div ref={agentSelectorRef} className="relative flex-1">
            <button
              ref={agentTriggerRef}
              onClick={() =>
                agentSelectorOpen ? closeAgentSelector(false) : openAgentSelector()
              }
              onKeyDown={(e) => {
                if (!agentSelectorOpen && (e.key === "ArrowDown" || e.key === "ArrowUp")) {
                  e.preventDefault();
                  openAgentSelector();
                }
              }}
              className="flex w-full items-center gap-2 rounded-lg border border-input bg-card px-3 py-2 text-sm transition-colors hover:bg-muted"
              data-testid="agent-selector"
              aria-haspopup="listbox"
              aria-expanded={agentSelectorOpen}
              aria-controls={agentSelectorOpen ? agentListboxId : undefined}
            >
              <Bot className="h-4 w-4 text-muted-foreground" />
              <span
                className={cn(
                  "flex-1 text-start truncate",
                  !selectedAgentName && "text-muted-foreground"
                )}
              >
                {selectedAgentName ?? t("chat.selectAgent")}
              </span>
              <ChevronDown
                className={cn(
                  "h-4 w-4 text-muted-foreground transition-transform",
                  agentSelectorOpen && "rotate-180"
                )}
              />
            </button>

            {/* Dropdown */}
            {agentSelectorOpen && (
              <div className="absolute inset-s-0 top-full z-50 mt-1 w-full rounded-lg border border-border bg-popover shadow-lg">
              <input
                ref={agentSearchRef}
                type="text"
                role="combobox"
                aria-expanded="true"
                aria-controls={agentListboxId}
                aria-autocomplete="list"
                aria-activedescendant={
                  visibleAgents[agentHighlight]
                    ? `${agentListboxId}-opt-${agentHighlight}`
                    : undefined
                }
                aria-label={t("chat.searchAgents", "Search agents")}
                placeholder={t("chat.searchAgents", "Search agents")}
                value={agentFilter}
                onChange={(e) => {
                  setAgentFilter(e.target.value);
                  setAgentHighlight(0);
                }}
                onKeyDown={(e) => {
                  // Keys that belong to an IME composition are not shortcuts.
                  if (isImeComposing(e)) return;
                  if (e.key === "ArrowDown") {
                    e.preventDefault();
                    setAgentHighlight((h) => Math.min(h + 1, Math.max(visibleAgents.length - 1, 0)));
                  } else if (e.key === "ArrowUp") {
                    e.preventDefault();
                    setAgentHighlight((h) => Math.max(h - 1, 0));
                  } else if (e.key === "Home") {
                    e.preventDefault();
                    setAgentHighlight(0);
                  } else if (e.key === "End") {
                    e.preventDefault();
                    setAgentHighlight(Math.max(visibleAgents.length - 1, 0));
                  } else if (e.key === "Enter") {
                    e.preventDefault();
                    const agent = visibleAgents[agentHighlight];
                    if (agent && !isImeComposing(e)) handleSelectAgent(agent.id, agent.name || agent.id);
                  } else if (e.key === "Escape") {
                    e.preventDefault();
                    e.stopPropagation();
                    closeAgentSelector(true);
                  } else if (e.key === "Tab") {
                    closeAgentSelector(false);
                  }
                }}
                className="m-1 h-8 w-[calc(100%-0.5rem)] rounded-md border border-input bg-background px-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                data-testid="agent-search"
              />
              <div
                id={agentListboxId}
                role="listbox"
                aria-label={t("chat.selectAgent")}
                className="max-h-72 overflow-y-auto p-1"
              >
                {agentsLoading ? (
                  <div className="flex items-center justify-center py-3">
                    <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                  </div>
                ) : !deployedAgents?.length ? (
                  <p className="px-3 py-2 text-xs text-muted-foreground">
                    {t("chat.noAgents")}
                  </p>
                ) : visibleAgents.length === 0 ? (
                  <p className="px-3 py-2 text-xs text-muted-foreground" data-testid="agent-search-empty">
                    {t("chat.noAgentMatches", "No agents match your search")}
                  </p>
                ) : (
                  visibleAgents.map((agent, i) => (
                    <button
                      key={agent.resource}
                      id={`${agentListboxId}-opt-${i}`}
                      role="option"
                      tabIndex={-1}
                      aria-selected={agent.id === selectedAgentId}
                      data-testid={`agent-option-${agent.id}`}
                      onClick={() => handleSelectAgent(agent.id, agent.name || agent.id)}
                      onMouseEnter={() => setAgentHighlight(i)}
                      className={cn(
                        "flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors hover:bg-muted min-w-0",
                        i === agentHighlight && "bg-muted",
                        agent.id === selectedAgentId &&
                          "bg-primary/10 text-primary font-medium"
                      )}
                    >
                      <Bot className="h-4 w-4 shrink-0" />
                      <div className="flex-1 min-w-0 text-start">
                        <p className="truncate font-medium">{agent.name}</p>
                        {agent.description && (
                          <p className="line-clamp-2 text-xs text-muted-foreground leading-snug">
                            {agent.description}
                          </p>
                        )}
                      </div>
                    </button>
                  ))
                )}
              </div>
              </div>
            )}
          </div>

          {/* Streaming toggle — hidden in embedded mode to save space */}
          {!embedded && <StreamingToggle />}

          {/* Activity toggle — hidden in embedded mode */}
          {!embedded && (
            <button
              onClick={toggleShowActivity}
              className={cn(
                "flex h-9 w-9 items-center justify-center rounded-lg transition-colors",
                showActivity
                  ? "bg-primary/10 text-primary"
                  : "text-muted-foreground hover:bg-muted hover:text-foreground",
              )}
              title={showActivity ? t("chat.hideActivity", "Hide Activity") : t("chat.showActivity", "Show Activity")}
              data-testid="activity-toggle"
            >
              <Activity className="h-4 w-4" />
            </button>
          )}

          {/* Top bar actions */}
          {conversationId && (
            <>
              {ratings.profile?.conversationRating && (
                <button
                  onClick={ratings.openCard}
                  className="flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                  title={t("chat.rating.card", "Rate this conversation")}
                  aria-label={t("chat.rating.card", "Rate this conversation")}
                  data-testid="chat-rate-conversation"
                >
                  <Star className="h-4 w-4" />
                </button>
              )}
              <button
                onClick={handleNewConversation}
                className="flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                title={t("chat.newConversation")}
                data-testid="new-conversation"
              >
                <MessageSquarePlus className="h-4 w-4" />
              </button>
              <button
                onClick={() =>
                  // The id on screen at click time, not whatever is current
                  // when the request returns.
                  endConversation.mutate(conversationId, {
                    onSuccess: (endedId) => ratings.onEnded(endedId),
                    onError: (err) =>
                      toast.error(
                        t("chat.endConversationFailed", "Could not end the conversation: {{reason}}", {
                          reason: getErrorMessage(err),
                        }),
                      ),
                  })
                }
                className="flex h-9 w-9 items-center justify-center rounded-lg text-destructive/70 transition-colors hover:bg-destructive/10 hover:text-destructive"
                title={t("chat.endConversation")}
                data-testid="end-conversation"
              >
                <StopCircle className="h-4 w-4" />
              </button>
            </>
          )}
        </div>

        {/* Conversation context header */}
        {conversationId && contextOpen && (
          <div className="flex items-center gap-4 border-b border-border/50 bg-muted/30 px-4 py-1.5 text-[11px] text-muted-foreground">
            <div className="flex items-center gap-1" title={t("chat.context.conversationId", "Conversation ID")}>
              <Hash className="h-3 w-3" />
              <button
                onClick={() => {
                  navigator.clipboard.writeText(conversationId);
                  toast.success(t("common.copied", "Copied!"));
                }}
                className="font-mono hover:text-foreground transition-colors truncate max-w-[120px]"
                title={conversationId}
              >
                {conversationId.slice(0, 12)}…
              </button>
            </div>
            <div className="flex items-center gap-1" title={t("chat.context.stepCount", "turns")}>
              <ListOrdered className="h-3 w-3" />
              <span>{messages.filter((m) => m.role === "user").length} {t("chat.context.stepCount", "turns")}</span>
            </div>
            <div className="flex items-center gap-1" title={t("chat.context.startTime", "Started")}>
              <Clock className="h-3 w-3" />
              <span>
                {messages[0]
                  ? new Date(messages[0].timestamp).toLocaleTimeString(undefined, {
                      hour: "2-digit",
                      minute: "2-digit",
                    })
                  : "—"}
              </span>
            </div>
          </div>
        )}
        {conversationId && !embedded && (
          <button
            onClick={() => setContextOpen((p) => !p)}
            className={cn(
              "flex w-full items-center justify-center py-0.5 text-muted-foreground hover:text-foreground hover:bg-muted/30 transition-colors",
              contextOpen && "border-b border-border/30",
            )}
            // Two keys, not a ternary default: the toggled state has its own words.
            title={contextOpen ? t("chat.context.hide", "Hide conversation info") : t("chat.context.show", "Show conversation info")}
            aria-label={contextOpen ? t("chat.context.hide", "Hide conversation info") : t("chat.context.show", "Show conversation info")}
            aria-expanded={contextOpen}
            data-testid="context-toggle"
          >
            {contextOpen ? (
              <ChevronUp className="h-3 w-3" />
            ) : (
              <Info className="h-3 w-3" />
            )}
          </button>
        )}

        {/* Said before the first message: the agent's maintainers may read
            this conversation, when its deployed version opted in. */}
        <ReviewNotice agentId={selectedAgentId} environment={chatEnvironment} />

        {/* Messages — the FAB must be a SIBLING of the scroller, anchored to
            this wrapper: absolutely positioned inside an overflow container it
            would scroll away with the content, vanishing exactly when the
            user has scrolled up and needs it. */}
        <div className="relative flex-1 min-h-0">
        <div
          ref={scrollContainerRef}
          // A stable gutter: the scrollbar appearing as the transcript grows
          // must not reflow every line of it.
          className="h-full overflow-y-auto [scrollbar-gutter:stable]"
          onScroll={handleScroll}
          // The transcript is a log, but not a live one: a streamed reply would
          // be read token by token. The announcer below says each reply once,
          // when it is complete.
          role="log"
          aria-live="off"
          aria-busy={isProcessing}
          aria-label={t("chat.transcript", "Conversation")}
        >
          {!selectedAgentId ? (
            <EmptyState />
          ) : messages.length === 0 ? (
            <div className="flex h-full items-center justify-center">
              <div className="text-center">
                <Bot className="mx-auto h-12 w-12 text-muted-foreground/30" />
                <p className="mt-3 text-sm text-muted-foreground">
                  {startConversation.isPending || openConversation.isPending || loadConversation.isPending
                    ? t("chat.thinking")
                    : conversationId
                      ? // An opened conversation that turned out to hold no
                        // turns — say so, so it doesn't read as a failed load.
                        t("chat.emptyConversation", "This conversation has no messages yet.")
                      : t("chat.empty")}
                </p>
                {/* A deep link (?agentId=) only preselects the agent and never starts
                    a conversation by itself, so offer the explicit start here —
                    otherwise the input stays disabled and "New conversation" is
                    hidden until the user re-picks the same agent. */}
                {!conversationId && !startConversation.isPending && !openConversation.isPending && !loadConversation.isPending && (
                  <button
                    type="button"
                    onClick={() => handleSelectAgent(selectedAgentId, selectedAgentName ?? selectedAgentId)}
                    className="mt-4 inline-flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
                    data-testid="open-chat"
                  >
                    <MessageSquarePlus className="h-4 w-4" />
                    {t("commandPalette.openChat")}
                  </button>
                )}
                {/* Ending clears the transcript; the ended conversation's card stays. */}
                {ratings.endedCard && (
                  <div className="mt-6 text-start">
                    <ChatRatingCard
                      key={ratings.endedCard.conversationId}
                      profile={ratings.endedCard.profile}
                      current={ratings.endedCard.current}
                      onRate={ratings.rateWhole}
                      onClose={ratings.closeCard}
                    />
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div className={embedded ? "py-2" : "py-4"}>
              {messages.map((msg, i) => {
                const ratingStep = ratings.profile?.messageRating ? (ratableByPosition[i] ?? null) : null;
                const editHere = i === lastTurn.user && canEditLast;
                const regenerateHere = i === lastTurn.reply && canRegenerate;
                const action: TurnNoticeContext["action"] | null = editHere ? "edit" : regenerateHere ? "regenerate" : null;
                const notice = action ? turnNotice({ action, stepIndex: lastStepIndex(messages) }) : null;
                const toolStep = toolCardsAt.get(i);
                const toolActivity = toolStep === undefined ? undefined : toolTurns.get(toolStep);
                return (
                  <Fragment key={msg.id}>
                  <ChatMessage
                    message={msg}
                    ratingStep={ratingStep}
                    rating={ratingStep !== null ? ratings.stepRatings[ratingStep] : undefined}
                    ratingProfile={ratings.profile}
                    onRateStep={ratings.rateReply}
                    actions={
                      action ? (
                        <span className="inline-flex items-center gap-1.5">
                          <button
                            type="button"
                            onClick={editHere ? handleEditLast : handleRerun}
                            // The notice is what the click does not take back; read it with the button.
                            aria-describedby={notice && !renderTurnNotice ? `${turnNoticeId}-${action}` : undefined}
                            className="inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] text-muted-foreground hover:bg-muted/50 hover:text-foreground"
                            title={editHere ? t("chat.editLastHint", "Edit and send again (↑)") : t("chat.regenerateHint", "Ask for a new reply")}
                            data-testid={editHere ? "edit-last" : "regenerate-last"}
                          >
                            {editHere ? <Pencil className="h-3 w-3" aria-hidden="true" /> : <RefreshCw className="h-3 w-3" aria-hidden="true" />}
                            <span>{editHere ? t("chat.editLast", "Edit") : t("chat.regenerate", "Regenerate")}</span>
                          </button>
                          {notice ? (
                            <span className="text-[10px] text-warn-600 dark:text-warn-400" data-testid="turn-notice">
                              {notice}
                            </span>
                          ) : null}
                        </span>
                      ) : undefined
                    }
                  />
                  {toolActivity && (
                    <div className="ps-15 pe-4 pb-1">
                      <ToolActivityCards activity={toolActivity} className="max-w-[min(75%,48rem)] text-xs" />
                    </div>
                  )}
                  </Fragment>
                );
              })}

              {ratings.cardOpen && ratings.profile && (
                <ChatRatingCard
                  key={conversationId ?? "none"}
                  profile={ratings.profile}
                  current={ratings.conversationRating}
                  onRate={ratings.rateWhole}
                  onClose={ratings.closeCard}
                />
              )}

              {/* Live status — the SAME line the operator chat shows:
                  "Thinking…" / "Using {tool}…" + tool-call count, from the
                  turn's live events. The dots indicator covers only the gap
                  before the first event arrives. */}
              {(isProcessing || isThinking) &&
                (currentTurnEvents.length > 0 || liveToolCalls.length > 0 ? (
                  <ChatActivity
                    events={currentTurnEvents}
                    isLive
                    showInternalSteps={false}
                    liveToolCalls={liveToolCalls}
                    liveToolsSettled={liveToolsSettled}
                  />
                ) : (
                  <InlineThinkingIndicator events={currentTurnEvents} />
                ))}
              <GuardrailActivity messages={messages} isProcessing={isProcessing} />

              {/* The stream dropped mid-reply; the turn is being followed on
                  the server until it settles (the controller's reconnect). */}
              {reconnecting && (
                <div
                  className="flex items-center gap-2 px-4 py-2 text-xs text-warn-600 dark:text-warn-400"
                  role="status"
                  data-testid="chat-reconnecting"
                >
                  <WifiOff className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  <span>{t("conversation.indicator.reconnecting", "Connection lost. Reconnecting…")}</span>
                </div>
              )}

              {/* Rerun button — shown when last message is an error */}
              {showRerun && !isProcessing && !stepMovePending && (
                <div className="flex justify-center py-2">
                  <button
                    onClick={handleRerun}
                    disabled={rerunConversation.isPending}
                    className="inline-flex items-center gap-1.5 rounded-full border border-warn-500/30 bg-warn-500/5 px-4 py-1.5 text-xs font-medium text-warn-600 transition-colors hover:bg-warn-500/15 disabled:opacity-50 dark:text-warn-400"
                    data-testid="rerun-btn"
                  >
                    <RefreshCw className={cn("h-3.5 w-3.5", rerunConversation.isPending && "animate-spin")} />
                    {t("chat.retry", "Retry Last Step")}
                  </button>
                </div>
              )}

              <div ref={messagesEndRef} />
            </div>
          )}
          </div>

          <ReplyAnnouncer messages={messages as AnnouncedMessage[]} speaker={speaker} className="sr-only" />

          {/* Scroll-to-bottom FAB with new content pulse */}
          {showScrollFab && (
            <button
              onClick={() => scrollToBottom("smooth")}
              className="absolute bottom-4 inset-x-0 mx-auto z-10 flex h-9 w-9 items-center justify-center rounded-full border border-border bg-card shadow-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-all animate-in fade-in slide-in-from-bottom-2"
              title={t("chat.scrollToBottom", "Scroll to bottom")}
              aria-label={t("chat.scrollToBottom", "Scroll to bottom")}
              data-testid="scroll-to-bottom"
            >
              <ArrowDown className="h-4 w-4" />
              {hasNewContent && (
                <span className="absolute -top-1 -end-1 flex h-3 w-3">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-primary opacity-75" />
                  <span className="relative inline-flex rounded-full h-3 w-3 bg-primary" />
                </span>
              )}
            </button>
          )}
        </div>

        {/* Debug drawer */}
        {conversationId && (
          <DebugDrawer
            conversationId={conversationId}
            agentId={selectedAgentId}
          />
        )}

        {/* Quick replies — hidden while paused so a pill can't fire a send
            against an AWAITING_HUMAN conversation (the input/send are also guarded). */}
        {controls.quickReplies && quickReplies.length > 0 && !isProcessing && !isPaused && !isLoadingConversation && !conversationBusy && !reviewPending && (
          <div className="flex flex-wrap gap-2 border-t border-border px-4 py-2">
            {quickReplies.map((reply, i) => (
              <button
                type="button"
                key={`${reply}-${i}`}
                onClick={() => handleQuickReply(reply)}
                className="rounded-full border border-primary/30 bg-primary/5 px-3 py-1.5 text-xs font-medium text-primary transition-colors hover:bg-primary/15"
                data-testid="quick-reply-btn"
              >
                {reply}
              </button>
            ))}
          </div>
        )}


        {/* Awaiting-approval notice — input is disabled while a turn is paused
            for human approval; the decision is made on the conversation's
            review page (which renders the full approval banner). */}
        {isPaused && conversationId && (
          <PauseNotice
            conversationId={conversationId}
            pausedAt={pausedAt}
            fallbackReason={pauseReason}
            testIdBase="chat-pause"
            // Decided elsewhere (another reviewer, or the timeout policy): unlock
            // the composer instead of leaving it stuck on "awaiting approval".
            onResolved={() => useChatStore.getState().setPaused(false, null)}
          />
        )}

        {/* Input — show SecretInput when backend requests it or user toggles 🔒 */}
        {activeInputField ? (
          <SecretInputField
            label={activeInputField.label}
            placeholder={activeInputField.placeholder}
            defaultValue={activeInputField.defaultValue}
            subType={activeInputField.subType}
            // The send clears the field itself, and hands it back if the
            // backend refuses the message without consuming it.
            onSend={(val) => handleSend(val, true)}
            disabled={isProcessing || isPaused || isLoadingConversation || conversationBusy || reviewPending}
          />
        ) : (
          <ChatInputWithSecretToggle
            onSend={handleSend}
            disabled={!conversationId || isPaused || isLoadingConversation || conversationBusy || reviewPending}
            isProcessing={isProcessing}
            onStop={canStop ? handleStop : undefined}
            isSecretMode={isSecretMode}
            onToggleSecret={toggleSecretMode}
            fileInputRef={fileInputRef}
            onFileChange={handleFileInput}
            onPasteFiles={controls.attachments ? handlePasteFiles : undefined}
            allowAttachments={controls.attachments}
            isUploading={isUploading}
            hasConversation={!!conversationId}
            pendingAttachments={pendingAttachments}
            onRemoveAttachment={removeAttachment}
            onRetryAttachment={retryAttachment}
            hasReadyAttachment={hasReadyAttachment}
            // Disabled while either move (or a rerun) is pending: the flags
            // only change after the re-read, so a double click would otherwise
            // undo a second turn.
            onEditLast={canEditLast ? handleEditLast : undefined}
            draft={draft}
            onUndo={controls.undo && conversationId && undoAvailable && !isProcessing && !conversationBusy
              ? () => undoConversation.mutate(undefined, { onError: (err) => toast.error(getErrorMessage(err)) })
              : undefined}
            onRedo={controls.redo && conversationId && redoAvailable && !isProcessing && !conversationBusy
              ? () => redoConversation.mutate(undefined, { onError: (err) => toast.error(getErrorMessage(err)) })
              : undefined}
            embedded={embedded}
          />
        )}
      </div>
    </div>
  );
}

/* ─── Inline sub-components ──────────────────── */

/** ChatInput enhanced with 🔒/🔓 secret mode toggle */
function ChatInputWithSecretToggle({
  onSend,
  disabled = false,
  isProcessing = false,
  onStop,
  isSecretMode,
  onToggleSecret,
  fileInputRef,
  onFileChange,
  onPasteFiles,
  isUploading = false,
  hasConversation = false,
  pendingAttachments = [],
  onRemoveAttachment,
  onRetryAttachment,
  hasReadyAttachment = false,
  onUndo,
  onRedo,
  onEditLast,
  draft = null,
  embedded = false,
  allowAttachments = true,
}: {
  /** Returns whether the message was sent; the draft is kept when it was not. */
  onSend: (message: string, isSecret?: boolean) => boolean;
  disabled?: boolean;
  isProcessing?: boolean;
  /** Set while a turn runs that can be stopped: the send button becomes Stop. */
  onStop?: () => void;
  isSecretMode: boolean;
  onToggleSecret: () => void;
  fileInputRef: React.RefObject<HTMLInputElement | null>;
  onFileChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  /** Files pasted into the textarea (screenshots, copied files). */
  onPasteFiles?: (files: File[]) => void;
  isUploading?: boolean;
  hasConversation?: boolean;
  pendingAttachments?: PendingAttachment[];
  onRemoveAttachment?: (id: string) => void;
  onRetryAttachment?: (id: string) => void;
  hasReadyAttachment?: boolean;
  onUndo?: () => void;
  onRedo?: () => void;
  /** Set when the last message may be edited: ↑ in an empty composer does it. */
  onEditLast?: () => void;
  /** Text to put into the composer (an edited message coming back), once per nonce. */
  draft?: { text: string; nonce: number } | null;
  embedded?: boolean;
  /** False when the agent switched attachments off (`conversationControls.attachments`). */
  allowAttachments?: boolean;
}) {
  const { t } = useTranslation();
  const [value, setValue] = useState("");
  const [secretVisible, setSecretVisible] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const handleSend = useCallback(() => {
    const trimmed = value.trim();
    // Allow an attachment-only turn (empty text) once a file is uploaded.
    if ((!trimmed && !hasReadyAttachment) || disabled || isProcessing || isUploading) return;
    if (!onSend(trimmed, isSecretMode)) return;
    setValue("");
    if (isSecretMode) {
      onToggleSecret();
      setSecretVisible(false);
    }
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
      // A click on Send moved focus to the button; the next message starts
      // in the composer. (A touch keyboard stays as the person left it.)
      if (!window.matchMedia?.("(pointer: coarse)").matches) textareaRef.current.focus();
    }
  }, [value, hasReadyAttachment, disabled, isProcessing, isUploading, isSecretMode, onSend, onToggleSecret]);

  const handleInput = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
    el.style.overflowY = el.scrollHeight > 160 ? "auto" : "hidden";
  }, []);

  // An edited message comes back into the composer, caret at its end.
  const draftNonce = draft?.nonce;
  useEffect(() => {
    if (!draft) return;
    setValue(draft.text);
    requestAnimationFrame(() => {
      const el = textareaRef.current;
      if (!el) return;
      handleInput();
      el.focus();
      el.setSelectionRange(draft.text.length, draft.text.length);
    });
    // Once per nonce: the same text sent twice still comes back twice.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftNonce]);

  const canSend =
    (value.trim().length > 0 || hasReadyAttachment) &&
    !disabled &&
    !isProcessing &&
    !isUploading;

  return (
    <div className="border-t border-border bg-background p-4 min-w-0" data-tour="chat-input-area">
      {/* Hidden file input for attachments */}
      {allowAttachments && (
      <input
        ref={fileInputRef}
        type="file"
        multiple
        className="hidden"
        onChange={onFileChange}
        data-testid="chat-file-input"
      />
      )}
      {/* Pending attachment chips */}
      {pendingAttachments.length > 0 && (
        <div className="mb-2 flex flex-wrap gap-2" data-testid="pending-attachments">
          {pendingAttachments.map((att) => (
            <PendingAttachmentChip
              key={att.id}
              att={att}
              onRemove={() => onRemoveAttachment?.(att.id)}
              onRetry={onRetryAttachment ? () => onRetryAttachment(att.id) : undefined}
            />
          ))}
        </div>
      )}
      <div className="flex items-end gap-2 min-w-0">
        {/* 📎 Attach button */}
        {allowAttachments && (
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          disabled={!hasConversation || isUploading || isSecretMode}
          className={cn(
            "flex h-11 w-11 shrink-0 items-center justify-center rounded-xl transition-colors",
            isUploading
              ? "bg-primary/10 text-primary animate-pulse"
              : "text-muted-foreground hover:bg-muted hover:text-foreground",
            // Attachments and secret mode are mutually exclusive (a masked turn
            // must not carry a file).
            isSecretMode && "cursor-not-allowed opacity-40"
          )}
          title={t("chat.attachHint", "Attach files — or paste or drop them here")}
          aria-label={t("chat.attach", "Attach file")}
          data-testid="chat-attach-btn"
        >
          {isUploading ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <Paperclip className="h-4 w-4" />
          )}
        </button>
        )}
        {/* 🔒 Secret mode toggle */}
        <button
          type="button"
          onClick={onToggleSecret}
          className={cn(
            "flex h-11 w-11 shrink-0 items-center justify-center rounded-xl transition-colors",
            isSecretMode
              ? "bg-primary/10 text-primary"
              : "text-muted-foreground hover:bg-muted hover:text-foreground"
          )}
          title={isSecretMode ? t("chat.secretModeOn", "Secret mode ON") : t("chat.secretModeOff", "Toggle secret mode")}
          aria-label={isSecretMode ? t("chat.secretModeOn", "Secret mode ON") : t("chat.secretModeOff", "Toggle secret mode")}
          aria-pressed={isSecretMode}
          data-testid="chat-secret-toggle"
        >
          {isSecretMode ? <Lock className="h-4 w-4" /> : <Unlock className="h-4 w-4" />}
        </button>

        {/* Undo / Redo — inline with input icons, only when available */}
        {(onUndo || onRedo) && (
          <>
            <div className="h-5 w-px bg-border/50 shrink-0" />
            {onUndo && (
              <button
                type="button"
                onClick={onUndo}
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                title={t("chat.undo", "Undo")}
                aria-label={t("chat.undo", "Undo")}
                data-testid="undo-btn"
              >
                <Undo2 className="h-3.5 w-3.5" />
              </button>
            )}
            {onRedo && (
              <button
                type="button"
                onClick={onRedo}
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                title={t("chat.redo", "Redo")}
                aria-label={t("chat.redo", "Redo")}
                data-testid="redo-btn"
              >
                <Redo2 className="h-3.5 w-3.5" />
              </button>
            )}
          </>
        )}

        {isSecretMode ? (
          /* Secret mode: password input with eye toggle */
          <div className="relative flex-1">
            <input
              type={secretVisible ? "text" : "password"}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey && !isImeComposing(e)) {
                  e.preventDefault();
                  handleSend();
                }
              }}
              placeholder={t("chat.secretPlaceholder", "Enter secret value...")}
              disabled={disabled}
              autoComplete="off"
              className={cn(
                "w-full rounded-xl border border-primary/60 bg-card px-4 py-3 pe-10 text-sm",
                "placeholder:text-muted-foreground",
                "focus:outline-none focus:ring-2 focus:ring-primary/30",
                "disabled:cursor-not-allowed disabled:opacity-50",
                "min-h-[44px]"
              )}
              data-testid="chat-input"
            />
            <button
              type="button"
              onClick={() => setSecretVisible(!secretVisible)}
              className="absolute inset-e-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
              disabled={disabled}
              aria-label={secretVisible ? t("chat.hide", "Hide") : t("chat.show", "Show")}
              aria-pressed={secretVisible}
              data-testid="chat-eye-toggle"
            >
              {secretVisible ? <Eye className="h-4 w-4" /> : <EyeOff className="h-4 w-4" />}
            </button>
          </div>
        ) : (
          /* Normal mode: auto-growing textarea */
          <textarea
            ref={textareaRef}
            autoFocus={!embedded}
            data-testid="chat-input"
            value={value}
            onChange={(e) => {
              setValue(e.target.value);
              handleInput();
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey && !isImeComposing(e)) {
                e.preventDefault();
                handleSend();
              } else if (
                e.key === "ArrowUp" &&
                !value &&
                onEditLast &&
                !e.shiftKey &&
                !e.altKey &&
                !e.ctrlKey &&
                !e.metaKey &&
                !isImeComposing(e)
              ) {
                // ↑ in an empty composer edits the last message, as in most chats.
                e.preventDefault();
                onEditLast();
              }
            }}
            onPaste={(e) => {
              // Screenshots / copied files paste as attachments. Text pastes
              // (no files on the clipboard) fall through untouched.
              const files = filesFromClipboard(e);
              if (!files.length || !onPasteFiles || !hasConversation) return;
              e.preventDefault();
              onPasteFiles(files);
            }}
            placeholder={t("chat.placeholder")}
            disabled={disabled}
            rows={1}
            className={cn(
              "flex-1 min-w-0 resize-none rounded-xl border border-input bg-card px-4 py-3 text-sm",
              "placeholder:text-muted-foreground",
              "focus:outline-none focus:ring-2 focus:ring-ring",
              "disabled:cursor-not-allowed disabled:opacity-50",
              "max-h-40 min-h-[44px]"
            )}
          />
        )}

        {onStop ? (
          /* While the agent answers, the send button's place is Stop's (Esc too). */
          <button
            type="button"
            onClick={onStop}
            className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl border border-border bg-card text-foreground transition-colors hover:bg-muted"
            title={t("chat.stopWithKey", "Stop (Esc)")}
            aria-label={t("chat.stop", "Stop")}
            data-testid="chat-stop"
          >
            <Square className="h-4 w-4 fill-current" aria-hidden="true" />
          </button>
        ) : (
        <button
          onClick={handleSend}
          disabled={!canSend}
          className={cn(
            "flex h-11 w-11 shrink-0 items-center justify-center rounded-xl transition-colors",
            canSend
              ? "bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer"
              : "bg-muted text-muted-foreground cursor-not-allowed"
          )}
          aria-label={t("chat.send")}
          data-testid="chat-send"
        >
          {isProcessing ? (
            <Loader2 className="h-5 w-5 animate-spin" />
          ) : (
            <Send className="h-5 w-5" />
          )}
        </button>
        )}
      </div>
      {/* Secret mode is a single-line password field — a newline hint there
          would promise something the input cannot do. */}
      {!isSecretMode && <InputHint />}
    </div>
  );
}


function EmptyState() {
  const { t } = useTranslation();
  return (
    <div className="flex h-full items-center justify-center">
      <div className="text-center">
        <div className="mx-auto flex h-20 w-20 items-center justify-center rounded-2xl bg-primary/10">
          <Bot className="h-10 w-10 text-primary" />
        </div>
        <h3 className="mt-4 text-lg font-semibold text-foreground">
          {t("pages.chat.title")}
        </h3>
        <p className="mt-1 max-w-xs text-sm text-muted-foreground">
          {t("chat.empty")}
        </p>
      </div>
    </div>
  );
}

// ==================== Inline Thinking Indicator ====================

// One classifier, not a second hand-synced set — the two drifted once already
// (this one lacked ai.labs.httpcalls while chat-activity had it).

/**
 * Sleek inline indicator shown during agent processing.
 * Shows "Thinking\u2026" with animated dots + any active tool call names.
 */
function InlineThinkingIndicator({ events }: { events: PipelineEvent[] }) {
  const { t } = useTranslation();

  // Extract active tool call names from events (only from non-internal tasks)
  const activeToolNames = useMemo(() => {
    const names: string[] = [];
    for (const ev of events) {
      if (isInternalTask(ev.taskType)) continue;
      if (ev.toolTrace) {
        for (const trace of ev.toolTrace) {
          if (trace.type === "tool_call" && trace.tool && !names.includes(trace.tool)) {
            names.push(trace.tool);
          }
        }
      }
    }
    return names;
  }, [events]);

  // Check for errors in visible events
  const hasError = events.some(
    (e) => e.type === "task_failed" && !isInternalTask(e.taskType),
  );

  return (
    <div
      className="flex items-center gap-2 px-4 py-2 text-xs text-muted-foreground"
      data-testid="thinking-indicator"
    >
      {/* Pulsing dots */}
      <span className="flex items-center gap-0.5">
        <span className="h-1 w-1 animate-bounce rounded-full bg-muted-foreground/40 [animation-delay:0ms]" />
        <span className="h-1 w-1 animate-bounce rounded-full bg-muted-foreground/40 [animation-delay:150ms]" />
        <span className="h-1 w-1 animate-bounce rounded-full bg-muted-foreground/40 [animation-delay:300ms]" />
      </span>

      {/* Status text */}
      <span className="italic">
        {hasError
          ? t("chat.activity.error", "Error occurred")
          : activeToolNames.length > 0
            ? t("chat.usingTools", "Using tools")
            : t("chat.thinking", "Thinking...")}
      </span>

      {/* Tool names — inline, comma-separated */}
      {activeToolNames.length > 0 && (
        <span className="flex items-center gap-1 text-muted-foreground">
          <Wrench className="h-2.5 w-2.5 shrink-0" />
          <span className="truncate max-w-[200px]">
            {activeToolNames.join(", ")}
          </span>
        </span>
      )}
    </div>
  );
}
