import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { create } from "zustand";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ENVIRONMENTS, type Environment } from "@/lib/constants";
import { deployedEnvironments, withAnyDeployedVersion } from "@/lib/deployment-environments";
import { mapWithConcurrency } from "@/lib/concurrency";
import {
  startConversation,
  readConversation,
  sendMessage,
  sendMessageWithContext,
  sendMessageStreaming,
  endConversation as endConversationApi,
  undoConversation as undoConversationApi,
  redoConversation as redoConversationApi,
  rerunLastStep,
  type ChatMessage,
  type SSEEvent,
} from "@/lib/api/chat";
import { cancelConversation } from "@/lib/api/hitl";
import { type AttachmentRef } from "@/lib/api/attachments";
import {
  getConversationDescriptors,
  parseConversationUri,
  type ConversationDescriptor,
  type SimpleConversationMemorySnapshot,
  type SimpleConversationStep,
  type ConversationOutput,
  type InputField,
  extractInput,
  extractOutputParts,
  describeTurnFailure,
  extractInputField,
  extractQuickReplies,
  SECRET_INPUT_PLACEHOLDER,
} from "@/lib/api/conversations";
import {
  getAgentDescriptors,
  getDeploymentStatuses,
  listDeploymentStatuses,
  type AgentDeploymentSummary,
  type AgentDescriptor,
  parseResourceUri,
} from "@/lib/api/agents";
import {
  UNCONSUMED_STREAM_ERROR_CODES,
  extractOutputImages,
  extractOutputTexts,
  type ApprovalStatus,
  type AttachmentResult,
  type ConversationSnapshot,
  type ConversationState,
} from "@eddi/core";
import {
  AgentConversation,
  initialConversationState,
  type AgentConversationState,
  type AgentTransport,
  type ConversationMessage,
  type ConversationStore,
  type Translate,
  type TurnFailure,
  type TurnFailureContext,
} from "@eddi/conversation";
import { useDebugStore, type ToolTraceEntry } from "@/hooks/use-debug-events";
import { getErrorMessage } from "@/lib/api-client";
import { toast } from "sonner";
import { chatKeys } from "@/lib/query-keys";

/**
 * What a secret turn shows in place of its text, live and on every rebuild from
 * a snapshot (the backend stores its own placeholder there; see
 * displayedUserInput).
 */
const SECRET_BUBBLE = "●●●●●●●●";

/** An uploaded attachment being sent with a turn (context ref + display preview). */
export interface SentAttachment extends AttachmentRef {
  /** Object URL for an inline image preview on the sent bubble. */
  previewUrl?: string;
}

/** Free the object URLs held by message attachment previews before messages are dropped. */
export function revokeMessagePreviews(messages: ChatMessage[]): void {
  for (const message of messages) {
    message.attachments?.forEach((a) => {
      if (a.previewUrl?.startsWith("blob:")) URL.revokeObjectURL(a.previewUrl);
    });
  }
}

/**
 * Carry client-only attachment previews from the previous messages onto rebuilt
 * ones, matched by user-message order. Snapshots (undo/redo/rerun) never carry
 * attachments, so without this an earlier image message loses its thumbnail when
 * a later turn is undone. (On a fresh load the previous list is already empty.)
 */
function preserveAttachments(prev: ChatMessage[], next: ChatMessage[]): ChatMessage[] {
  const prevUserAttachments = prev.filter((m) => m.role === "user").map((m) => m.attachments);
  let i = 0;
  return next.map((m) => {
    if (m.role !== "user") return m;
    const att = prevUserAttachments[i++];
    return att && att.length ? { ...m, attachments: att } : m;
  });
}

/** Revoke only the preview URLs present in `prev` that no message in `next` still references. */
function revokeOrphanedPreviews(prev: ChatMessage[], next: ChatMessage[]): void {
  const kept = new Set<string>();
  next.forEach((m) => m.attachments?.forEach((a) => a.previewUrl && kept.add(a.previewUrl)));
  prev.forEach((m) =>
    m.attachments?.forEach((a) => {
      if (a.previewUrl?.startsWith("blob:") && !kept.has(a.previewUrl)) URL.revokeObjectURL(a.previewUrl);
    }),
  );
}

/**
 * Monotonic ticket for conversation loads — see loadConversationIntoStore.
 * Also advanced whenever the transcript is replaced (agent picked, cleared,
 * reset), so a load already in flight can never install over the replacement.
 */
let latestLoadRequest = 0;

/** Fields the conversation controller owns beside the Manager's, reset with the transcript. */
const CONTROLLER_FIELDS_RESET: Pick<
  ChatState,
  | "conversationState"
  | "endReason"
  | "isEscalating"
  | "activeTool"
  | "historyBusy"
  | "pendingAttachments"
  | "approvalStatus"
  | "restoreDraft"
  | "startFailed"
  | "openedAgentId"
> = {
  conversationState: null,
  endReason: null,
  isEscalating: false,
  activeTool: null,
  historyBusy: false,
  pendingAttachments: [],
  approvalStatus: null,
  restoreDraft: null,
  startFailed: false,
  openedAgentId: undefined,
};

// --- Zustand Store ---

interface ChatState {
  messages: ChatMessage[];
  conversationId: string | null;
  selectedAgentId: string | null;
  selectedAgentName: string | null;
  isProcessing: boolean;
  isThinking: boolean;
  streamingEnabled: boolean;
  undoAvailable: boolean;
  redoAvailable: boolean;
  quickReplies: string[];
  /** Set when the backend requests a specific input field (e.g. password). */
  activeInputField: InputField | null;
  /** Set when the user toggles the 🔒 secret mode on the chat input. */
  isSecretMode: boolean;
  /** True while the current conversation is paused awaiting a human approval
   *  (backend conversationState === AWAITING_HUMAN). Input is disabled and a
   *  review affordance is shown while set. */
  isPaused: boolean;
  /** Approver-facing reason for the pause, when the backend reported one. */
  pauseReason: string | null;
  /** When the pause on screen started (`hitlPausedAt`), so a notice can tell THIS pause from another. */
  pausedAt: string | null;
  /**
   * Bumped whenever the transcript on screen stops being the one a send in
   * flight belongs to: another agent picked, another conversation loaded, a
   * new one started, the store reset. A send captures it when it starts and
   * writes nothing once it has moved on.
   *
   * Without it a streamed reply kept appending to "the last agent message" of
   * whatever the store held by then. Switch conversation or agent mid-stream
   * and A's tokens landed in B's bubble, and A's `done` snapped B's last reply
   * to A's text. The conversation id alone is not enough: reloading the SAME
   * conversation rebuilds the transcript under the stream too.
   */
  conversationEpoch: number;
  /**
   * The conversation a load is currently reading, or null when none is.
   *
   * A load reads before it replaces anything, so for the length of that read
   * the transcript on screen — and its `conversationId` — still belong to the
   * conversation being left. A send in that window went to the OLD
   * conversation on the server, and the load then installed over it, so the
   * message vanished from view while still having been delivered. Sends are
   * refused while this is set (see {@link SendBlockedError}) and the inputs
   * are disabled.
   */
  loadingConversationId: string | null;

  /* Owned by the conversation controller (@eddi/conversation), which reads and
     writes this store; see chatConversationStore. */
  /** The conversation's state as the last turn or read reported it. */
  conversationState: ConversationState | null;
  endReason: string | null;
  /** A cascade moved to a stronger model; cleared by the next token. */
  isEscalating: boolean;
  /** The tool the agent is running right now, from the live `tool_call` event. */
  activeTool: string | null;
  historyBusy: boolean;
  /** The controller's own attachment staging; the Manager's composers keep theirs. */
  pendingAttachments: AttachmentResult[];
  approvalStatus: ApprovalStatus | null;
  restoreDraft: string | null;
  startFailed: boolean;
  openedAgentId: string | null | undefined;

  // Actions
  setSelectedAgent: (agentId: string | null, agentName: string | null) => void;
  setConversationId: (id: string | null) => void;
  addMessage: (message: ChatMessage) => void;
  appendToLastAgentMessage: (token: string) => void;
  finishStreaming: () => void;
  setProcessing: (v: boolean) => void;
  setThinking: (v: boolean) => void;
  toggleStreaming: () => void;
  clearMessages: () => void;
  setUndoRedo: (undo: boolean, redo: boolean) => void;
  setQuickReplies: (replies: string[]) => void;
  replaceMessages: (messages: ChatMessage[]) => void;
  setInputField: (field: InputField) => void;
  clearInputField: () => void;
  toggleSecretMode: () => void;
  setPaused: (isPaused: boolean, reason?: string | null, pausedAt?: string | null) => void;
  reset: () => void;
}

const loadStreamingPref = (): boolean => {
  try {
    return localStorage.getItem("eddi-chat-streaming") !== "false";
  } catch {
    return true;
  }
};

export const useChatStore = create<ChatState>((set) => ({
  messages: [],
  conversationId: null,
  selectedAgentId: null,
  selectedAgentName: null,
  isProcessing: false,
  isThinking: false,
  streamingEnabled: loadStreamingPref(),
  undoAvailable: false,
  redoAvailable: false,
  quickReplies: [],
  activeInputField: null,
  isSecretMode: false,
  isPaused: false,
  pauseReason: null,
  pausedAt: null,
  conversationEpoch: 0,
  loadingConversationId: null,
  ...CONTROLLER_FIELDS_RESET,

  // Everything that belonged to the previous agent's conversation goes with it.
  // Its quick replies and a requested input field used to survive the switch,
  // so the new agent opened offering the old one's buttons, or asking for the
  // old one's API key. isProcessing/isThinking are reset because a send still
  // running for the old conversation is detached (see conversationEpoch) and
  // must not keep the new one's input locked.
  setSelectedAgent: (agentId, agentName) =>
    set((s) => {
      revokeMessagePreviews(s.messages);
      latestLoadRequest++;
      // The live status line belongs to the transcript being replaced; move
      // any leftover events into the turn history instead of showing them
      // under the next conversation until its first send.
      useDebugStore.getState().finalizeTurn();
      return {
        selectedAgentId: agentId,
        selectedAgentName: agentName,
        conversationId: null,
        messages: [],
        isProcessing: false,
        isThinking: false,
        undoAvailable: false,
        redoAvailable: false,
        quickReplies: [],
        activeInputField: null,
        isSecretMode: false,
        isPaused: false,
        pauseReason: null,
        conversationEpoch: s.conversationEpoch + 1,
        // latestLoadRequest moved on above, so a load in flight will not
        // install and will not clear this itself.
        loadingConversationId: null,
        ...CONTROLLER_FIELDS_RESET,
      };
    }),

  setConversationId: (id) => set({ conversationId: id }),

  addMessage: (message) =>
    set((s) => ({ messages: [...s.messages, message] })),

  appendToLastAgentMessage: (token) =>
    set((s) => {
      const msgs = [...s.messages];
      const last = msgs[msgs.length - 1];
      if (last?.role === "agent") {
        msgs[msgs.length - 1] = { ...last, content: last.content + token };
      }
      return { messages: msgs };
    }),

  finishStreaming: () =>
    set((s) => {
      const msgs = [...s.messages];
      const last = msgs[msgs.length - 1];
      if (last?.role === "agent") {
        msgs[msgs.length - 1] = { ...last, isStreaming: false };
      }
      return { messages: msgs, isProcessing: false };
    }),

  setProcessing: (v) => set({ isProcessing: v }),

  setThinking: (v) => set({ isThinking: v }),

  toggleStreaming: () =>
    set((s) => {
      const next = !s.streamingEnabled;
      try {
        localStorage.setItem("eddi-chat-streaming", String(next));
      } catch {
        /* noop */
      }
      return { streamingEnabled: next };
    }),

  clearMessages: () =>
    set((s) => {
      revokeMessagePreviews(s.messages);
      latestLoadRequest++;
      // The live status line belongs to the transcript being replaced; move
      // any leftover events into the turn history instead of showing them
      // under the next conversation until its first send.
      useDebugStore.getState().finalizeTurn();
      return {
        messages: [],
        conversationId: null,
        // A send still in flight belongs to the transcript being cleared; it is
        // detached (conversationEpoch) and must not hold the next one's input.
        isProcessing: false,
        isThinking: false,
        undoAvailable: false,
        redoAvailable: false,
        quickReplies: [],
        activeInputField: null,
        isSecretMode: false,
        isPaused: false,
        pauseReason: null,
        conversationEpoch: s.conversationEpoch + 1,
        // latestLoadRequest moved on above, so a load in flight will not
        // install and will not clear this itself.
        loadingConversationId: null,
        ...CONTROLLER_FIELDS_RESET,
      };
    }),

  setUndoRedo: (undo, redo) => set({ undoAvailable: undo, redoAvailable: redo }),

  setQuickReplies: (replies) => set({ quickReplies: replies }),

  replaceMessages: (messages) =>
    set((s) => {
      // Undo/redo/rerun rebuild from a snapshot (no attachments). Carry the
      // client-side previews forward so thumbnails survive, and revoke only the
      // previews that are genuinely gone.
      const merged = preserveAttachments(s.messages, messages);
      revokeOrphanedPreviews(s.messages, merged);
      return { messages: merged };
    }),

  setInputField: (field) => set({ activeInputField: field }),

  clearInputField: () => set({ activeInputField: null }),

  toggleSecretMode: () => set((s) => ({ isSecretMode: !s.isSecretMode })),

  setPaused: (isPaused, reason = null, pausedAt = null) =>
    set({ isPaused, pauseReason: reason ?? null, pausedAt: isPaused ? (pausedAt ?? null) : null }),

  reset: () =>
    set((s) => {
      revokeMessagePreviews(s.messages);
      latestLoadRequest++;
      // The live status line belongs to the transcript being replaced; move
      // any leftover events into the turn history instead of showing them
      // under the next conversation until its first send.
      useDebugStore.getState().finalizeTurn();
      return {
        messages: [],
        conversationId: null,
        selectedAgentId: null,
        selectedAgentName: null,
        isProcessing: false,
        isThinking: false,
        undoAvailable: false,
        redoAvailable: false,
        quickReplies: [],
        activeInputField: null,
        isSecretMode: false,
        isPaused: false,
        pauseReason: null,
        conversationEpoch: s.conversationEpoch + 1,
        // latestLoadRequest moved on above, so a load in flight will not
        // install and will not clear this itself.
        loadingConversationId: null,
        ...CONTROLLER_FIELDS_RESET,
      };
    }),
}));

// --- TanStack Query Hooks ---


/** Fetch agent descriptors and filter to only those that are deployed. */
export function useDeployedAgents() {
  return useQuery({
    queryKey: chatKeys.deployedAgents,
    queryFn: async () => {
      const descriptors = await getAgentDescriptors(500, 0, "");
      // One entry per agent ID, keeping its latest version. This used to key
      // on the NAME, so two distinct agents that happened to share one (two
      // "Support Bot"s, a copy that kept its original's name) collapsed into a
      // single picker entry and the other could not be chatted with at all.
      const grouped = new Map<
        string,
        AgentDescriptor & { id: string; version: number }
      >();
      for (const agent of descriptors) {
        const { id, version } = parseResourceUri(agent.resource);
        const existing = grouped.get(id);
        if (!existing || version > existing.version) {
          grouped.set(id, { ...agent, id, version });
        }
      }
      const agents = Array.from(grouped.values());

      // Status across EVERY environment, not just production. Filtering on
      // production alone kept a test-only agent out of the chat picker
      // entirely — it could not be selected, so "I can't see it in the UI" was
      // literally true. Each agent now carries the environments it is live in,
      // so the picker can both list it and start the conversation in the right
      // place.
      // BOUNDED. `getAgentDescriptors` pages at 500 and each agent costs one
      // request per environment, so an unbounded fan-out here could open ~1000
      // sockets on a single picker refresh. Per-agent failures resolve to "no
      // environments" and drop out below, exactly as the previous allSettled
      // did — one unreachable agent must not empty the picker.
      // One environment-wide listing each, so an agent still live at an older
      // version (every save bumps the version) is not dropped from the picker.
      // A failed listing just means no fallback for that environment.
      const listings = await Promise.all(
        ENVIRONMENTS.map((env) => listDeploymentStatuses(env).catch(() => undefined)),
      );
      const deployed = Object.fromEntries(
        ENVIRONMENTS.map((env, i) => [env, listings[i]]),
      ) as Partial<Record<Environment, AgentDeploymentSummary[] | undefined>>;
      const results = await mapWithConcurrency(agents, 8, async (agent) => {
        try {
          const statuses = withAnyDeployedVersion(
            await getDeploymentStatuses(agent.id, agent.version),
            deployed,
            agent.id,
            agent.version,
          );
          return { agent, environments: deployedEnvironments(statuses) };
        } catch {
          return { agent, environments: [] as Environment[] };
        }
      });

      return results
        .filter((r) => r.environments.length > 0)
        .map((r) => ({ ...r.agent, environments: r.environments }));
    },
    staleTime: 60_000,
  });
}

/** Start a new conversation with a agent, then GET to pick up welcome messages. */
/**
 * Starts a conversation in a SPECIFIC environment.
 *
 * `environment` is required rather than defaulted: it used to be hardcoded
 * "production" here (and then discarded by `startConversation`, which never sent
 * it), so an agent deployed only to `test` could not be chatted with at all and
 * the failure looked like a broken agent. Making callers name the environment
 * means a new call site has to think about which one it means.
 */
export function useStartConversation() {
  const store = useChatStore;
  return useMutation({
    mutationFn: async ({
      agentId,
      environment,
      epoch: requestedEpoch,
    }: {
      agentId: string;
      environment: Environment;
      /**
       * The transcript generation this start was requested in, when a caller
       * did async work before calling it (the resume fallback). Defaults to
       * the current one.
       */
      epoch?: number;
    }) => {
      // The store may move on while this runs (another agent picked, another
      // conversation opened). The conversation is still created, but it must
      // not be installed over whatever the user is looking at by then.
      const epoch = requestedEpoch ?? store.getState().conversationEpoch;
      const isCurrent = () => store.getState().conversationEpoch === epoch;

      const conversationId = await startConversation(environment, agentId);
      if (!isCurrent()) return conversationId;
      store.getState().setConversationId(conversationId);

      // GET immediately to pick up any welcome message
      const snapshot = await readConversation(
        environment,
        agentId,
        conversationId,
        false
      );
      if (!isCurrent()) return conversationId;

      // Convert welcome steps to ChatMessages — one bubble per output part
      const outputs = snapshot.conversationOutputs ?? [];
      // A greeting can already ask for a secret ("paste your API key"). Only
      // the newest output's request is live.
      const inputField = extractInputField(outputs[outputs.length - 1]);
      if (inputField) store.getState().setInputField(inputField);
      for (const output of outputs) {
        const parts = extractOutputParts(output);
        for (const part of parts) {
          store.getState().addMessage({
            id: `welcome-${Date.now()}-${Math.random()}`,
            role: "agent",
            content: part,
            timestamp: Date.now(),
          });
        }
        // Extract quick replies from the last output
        const qr = extractQuickReplies(output);
        if (qr.length > 0) {
          store.getState().setQuickReplies(qr);
        }
      }

      return conversationId;
    },
  });
}

/**
 * A send refused on the client before anything was sent or shown: a
 * conversation load is still reading (see `loadingConversationId`). Nothing
 * reached the transcript or the server, so there is nothing to roll back.
 */
class SendBlockedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SendBlockedError";
  }
}

/**
 * Stream error codes that mean the input was refused BEFORE the turn started,
 * so it was never consumed — `@eddi/core`'s list, shared with the Chat UI. A
 * failure DURING a turn arrives as an opaque error without a code and keeps the
 * error bubble, because that turn did run.
 *
 * Re-exported for tests, which pin the list against the backend's.
 */
export { UNCONSUMED_STREAM_ERROR_CODES };

/**
 * How long a stream detached from the transcript on screen is left to finish
 * before it is aborted. Draining lets the turn the user already sent complete
 * on the server (closing the stream cancels it there), but a proxy that
 * swallows the terminal frame would otherwise keep the fetch and its mutation
 * open for good, one more per switch.
 */
export const DETACHED_STREAM_GRACE_MS = 120_000;

/* ─── The turn loop: @eddi/conversation's AgentConversation ───────────────
 *
 * Sending, streaming, the `done` reconcile, refusals and the detached-stream
 * grace are the controller's, shared with the Chat UI (phase 3c of the frontend
 * unification plan). What stays here is the Manager's: its store (the
 * controller reads and writes it through `chatConversationStore`), its copy
 * (error bubbles, toasts, the pause banner), its debug events and the pause's
 * identity. Load, resume, undo, redo and rerun install snapshots with the
 * Manager's secret masking and attachment previews, below.
 */

/** The translator the controller's hooks use: the latest `useSendMessage` render's. */
let managerT: TFunction | null = null;
const tr = (key: string, fallback: string, vars?: Record<string, unknown>): string =>
  managerT ? managerT(key, fallback, vars) : fallback.replace(/\{\{(\w+)\}\}/g, (_, k: string) => String(vars?.[k] ?? ""));

/**
 * The chat store as the controller sees it. Same fields, except quick replies:
 * the Manager keeps their values, the controller `{ value }` objects. The view
 * is cached per store state, so an unchanged store reads as the same object.
 */
let viewSource: ChatState | null = null;
let view: AgentConversationState = initialConversationState;

function controllerView(s: ChatState): AgentConversationState {
  if (s === viewSource) return view;
  const quickReplies =
    viewSource && viewSource.quickReplies === s.quickReplies ? view.quickReplies : s.quickReplies.map((value) => ({ value }));
  viewSource = s;
  view = {
    messages: s.messages as ConversationMessage[],
    conversationId: s.conversationId,
    conversationState: s.conversationState,
    endReason: s.endReason,
    quickReplies,
    isProcessing: s.isProcessing,
    isThinking: s.isThinking,
    isEscalating: s.isEscalating,
    activeTool: s.activeTool,
    historyBusy: s.historyBusy,
    undoAvailable: s.undoAvailable,
    redoAvailable: s.redoAvailable,
    activeInputField: s.activeInputField,
    isSecretMode: s.isSecretMode,
    pendingAttachments: s.pendingAttachments,
    approvalStatus: s.approvalStatus,
    restoreDraft: s.restoreDraft,
    startFailed: s.startFailed,
    openedAgentId: s.openedAgentId,
  };
  return view;
}

const chatConversationStore: ConversationStore = {
  get: () => controllerView(useChatStore.getState()),
  set: (next) => {
    const previous = controllerView(useChatStore.getState());
    const patch: Record<string, unknown> = {};
    for (const key of Object.keys(next) as (keyof AgentConversationState)[]) {
      if (next[key] === previous[key]) continue;
      // A snapshot's quick replies may be bare strings as well as `{ value }`
      // (extractQuickReplies has always taken both).
      patch[key] =
        key === "quickReplies"
          ? next.quickReplies
              .map((r: unknown) => (typeof r === "string" ? r : (r as { value?: unknown })?.value))
              .filter((v): v is string => typeof v === "string")
          : next[key];
    }
    if (Object.keys(patch).length > 0) useChatStore.setState(patch as Partial<ChatState>);
  },
  subscribe: (listener) => useChatStore.subscribe(() => listener()),
};

/** The agent the chat is talking to: the transport reads it per call. */
const selectedAgent = () => useChatStore.getState().selectedAgentId ?? "";

/** The Manager's API binding as a transport (its token refresh, its errors, its mocks in tests). */
const chatTransport: AgentTransport = {
  mode: "conversation",
  stream: (conversationId, text, { context, signal }) =>
    sendMessageStreaming(
      "production",
      selectedAgent(),
      conversationId ?? "",
      context ? { input: text, context } : { input: text },
      signal,
    ),
  say: (conversationId, text, { context }) =>
    (context
      ? sendMessageWithContext("production", selectedAgent(), conversationId ?? "", { input: text, context })
      : sendMessage("production", selectedAgent(), conversationId ?? "", text)) as unknown as Promise<
      Partial<ConversationSnapshot>
    >,
  read: (conversationId, currentStepOnly) =>
    readConversation("production", selectedAgent(), conversationId, currentStepOnly ?? false) as unknown as Promise<
      ConversationSnapshot
    >,
  undo: (conversationId) => undoConversationApi("production", selectedAgent(), conversationId),
  redo: (conversationId) => redoConversationApi("production", selectedAgent(), conversationId),
  rerun: (conversationId) => rerunLastStep(conversationId),
  cancel: (conversationId) => cancelConversation(conversationId),
};

/** The last output of a snapshot, in the Manager's type. */
function lastOutputOf(snapshot: Partial<ConversationSnapshot>): ConversationOutput | undefined {
  const outputs = (snapshot as SimpleConversationMemorySnapshot).conversationOutputs;
  return outputs?.length ? outputs[outputs.length - 1] : undefined;
}

/** Replace the last agent bubble's fields, as the Manager's error copy does. */
function patchLastAgent(patch: (last: ChatMessage) => ChatMessage): void {
  useChatStore.setState((s) => {
    const msgs = [...s.messages];
    const last = msgs[msgs.length - 1];
    if (last?.role === "agent") msgs[msgs.length - 1] = patch(last);
    return { messages: msgs };
  });
}

/**
 * How the Manager shows a turn that did not end with a reply.
 *
 * - A refusal the server answered before running the turn (a 409, or a stream
 *   error code for an unconsumed turn) takes the message back: the pause banner
 *   when the conversation really is paused, otherwise a toast saying why. The
 *   field it answered comes back, with the typed answer, or 🔒 mode does.
 * - An error the stream reported while the turn ran goes into the reply,
 *   flagged for "Retry Last Step".
 * - Anything else (a dropped connection, a 5xx, another 4xx) keeps the message
 *   and adds an error bubble.
 */
async function presentChatFailure(failure: TurnFailure, ctx: TurnFailureContext): Promise<boolean> {
  const store = useChatStore;
  if (failure.kind === "streamError") {
    // A known code gets a sentence in the reader's language that says what to
    // do next. The backend message is kept as the fallback: an unrecognised
    // code is still worth showing, and its text is the only thing that explains it.
    const reason = translateStreamError(failure.code ?? undefined, managerT ?? fallbackT) ?? failure.message;
    patchLastAgent((last) => ({
      ...last,
      content: `${last.content}\n\n⚠️ ${tr("chat.errorWithReason", "Error: {{reason}}", { reason })}`,
      isError: true,
    }));
    return true;
  }

  const refusal = failure.kind === "refused" && (failure.status === 409 || failure.code !== null);
  if (refusal) {
    // Paused is decided by the conversation's actual state, not by the status:
    // a 409 also answers "processing another turn", "agent version mismatch"
    // and "the conversation changed while your message was queued", and each
    // used to show the approval banner over a conversation nobody could
    // approve. Only a confirmed AWAITING_HUMAN is a pause; a failed read is a
    // refusal, with the input left enabled.
    let paused = failure.code === "awaiting_approval";
    let pausedAt: string | null = null;
    if (failure.status === 409 && ctx.conversationId) {
      try {
        const snapshot = await readConversation("production", "", ctx.conversationId, true);
        paused = snapshot.conversationState === "AWAITING_HUMAN";
        pausedAt = paused ? (snapshot.hitlPausedAt ?? null) : null;
      } catch {
        // Unconfirmed: a refusal, not a pause.
      }
    }
    if (!ctx.isCurrent()) return true;
    const secretModeBefore = store.getState().isSecretMode;
    const rejected = store.getState().messages.find((m) => m.id === ctx.userMessageId);
    if (rejected) revokeMessagePreviews([rejected]);
    // Takes the bubble (and an empty placeholder) away. The Manager's composer
    // keeps its own draft, so nothing is handed back through the store — a
    // secret must not linger there.
    ctx.withdraw();
    store.setState({ restoreDraft: null, isThinking: false, quickReplies: [] });
    if (ctx.answeredField) {
      // The question the refused message answered is still open, and the
      // answer the user typed comes back with it: the field remounts from
      // `defaultValue`, which would otherwise be the agent's own default.
      store.setState({
        activeInputField: ctx.isSecret ? { ...ctx.answeredField, defaultValue: ctx.text } : ctx.answeredField,
        isSecretMode: secretModeBefore,
      });
    } else if (!ctx.isSecret) {
      store.setState({ isSecretMode: secretModeBefore });
    }
    // (A 🔒-mode message: the withdraw switched secret mode back on, or the
    // retry would go out in clear, unflagged.)
    if (paused) {
      store.getState().setPaused(true, null, pausedAt);
    } else {
      const reason = failure.code
        ? (translateStreamError(failure.code, managerT ?? fallbackT) ?? failure.message)
        : failure.message;
      toast.error(tr("chat.sendRejected", "Your message was not sent: {{reason}}", { reason }));
    }
    return true;
  }

  // Consumed, or its fate is unknown: the user's message stays. The placeholder
  // this send opened does not — an empty one goes, a partly streamed one is
  // closed — or the typing indicator would keep running under the error.
  store.setState((s) => {
    const msgs = [...s.messages];
    const last = msgs[msgs.length - 1];
    if (last?.role === "agent" && last.isStreaming) {
      if (!last.content.trim()) msgs.pop();
      else msgs[msgs.length - 1] = { ...last, isStreaming: false };
    }
    msgs.push({
      id: `agent-error-${Date.now()}`,
      role: "agent",
      content: `⚠️ ${tr("chat.errorWithReason", "Error: {{reason}}", { reason: getErrorMessage(failure.error ?? failure.message) })}`,
      timestamp: Date.now(),
      isError: true,
    });
    return { messages: msgs, isThinking: false, quickReplies: [] };
  });
  return true;
}

/** A translator for code that runs before any component rendered (tests). */
const fallbackT = ((key: string, fallback?: unknown) => (typeof fallback === "string" ? fallback : key)) as unknown as TFunction;

/** The Manager's conversation controller: one for the chat store. */
export const chatConversation = new AgentConversation({
  transport: chatTransport,
  store: chatConversationStore,
  generation: () => useChatStore.getState().conversationEpoch,
  detachedGraceMs: DETACHED_STREAM_GRACE_MS,
  // Undo/redo come from the `done` payload (onTurnSnapshot), as they always did.
  refreshAfterStream: false,
  attachmentDisplay: "chips",
  // @eddi/core's reading of an output (links, buttons, inputField skipped),
  // falling back to the flat `output:text:*` keys older snapshots carry.
  outputTexts: (output) => {
    const texts = extractOutputTexts(output.output);
    return texts.length > 0 ? texts : extractOutputParts(output as ConversationOutput);
  },
  onStreamEvent: (event) => recordDebugEvent(event),
  // Turn boundary at turn START, not only on the happy endings: a previous
  // turn that ended without a done/error frame leaves its events in the debug
  // store, and the live status line renders straight off that array.
  // finalizeTurn() moves leftovers into the turn history and is a no-op when
  // the previous turn ended cleanly. A new turn also clears a prior pause; it
  // is set again if this turn pauses.
  onTurnStarting: () => {
    useDebugStore.getState().finalizeTurn();
    useChatStore.getState().setPaused(false, null);
  },
  onTurnEnd: () => useDebugStore.getState().finalizeTurn(),
  onTurnSnapshot: (snapshot) => {
    const s = snapshot as SimpleConversationMemorySnapshot;
    // A turn just landed, so there is now something to undo.
    useChatStore.getState().setUndoRedo(...undoRedoFlags(s));
    // A pause commits as AWAITING_HUMAN; its pendingMessage is already the
    // reply. The reason is null on purpose — no snapshot carries one; the
    // banner overlays the real reason from approval-status — but the pause's
    // instant is kept, so the notice verifies THIS pause.
    if (s.conversationState === "AWAITING_HUMAN") {
      useChatStore.getState().setPaused(true, null, s.hitlPausedAt ?? null);
    }
  },
  turnFailure: (snapshot) =>
    describeTurnFailure(lastOutputOf(snapshot), snapshot.conversationState, turnFailedFallback(managerT ?? fallbackT)),
  presentFailure: presentChatFailure,
});

/** Send a message — streamed or not, by the toggle — on the chat's controller. */
export function useSendMessage() {
  // Threaded into the controller's hooks rather than reached for through the
  // i18n singleton: every other non-component translator in this repo takes a
  // `TFunction`.
  const { t } = useTranslation();
  return useMutation({
    mutationFn: async ({
      message,
      isSecret,
      attachments,
    }: {
      message: string;
      isSecret?: boolean;
      /** Already-uploaded attachments to forward to the LLM this turn. */
      attachments?: SentAttachment[];
    }) => {
      const state = useChatStore.getState();
      // Until the load finishes, `conversationId` is still the conversation
      // being left: sending now would deliver the message there and the load
      // would then install over it. The inputs are disabled for this interval;
      // this is the backstop for every caller (panel, drawer, quick replies).
      if (state.loadingConversationId) {
        throw new SendBlockedError(
          t(
            "chat.sendWhileLoading",
            "Your message was not sent: the conversation is still loading. Try again in a moment.",
          ),
        );
      }
      if (!state.selectedAgentId || !state.conversationId) {
        throw new Error("No active conversation");
      }
      managerT = t;
      chatConversation.setOptions({ streaming: state.streamingEnabled, t: t as unknown as Translate });
      const result = await chatConversation.send(message, isSecret, { attachments: attachments ?? [] });
      if (result.outcome === "refused" || result.outcome === "failed") {
        throw result.failure.error ?? new Error(result.failure.message);
      }
    },
    onError: (error) => {
      // Refused before it touched the transcript: say so, change nothing.
      // Every other failure was already shown by presentChatFailure.
      if (error instanceof SendBlockedError) toast.error(error.message);
    },
  });
}

/** What a failed turn says when the backend gave no reason. */
function turnFailedFallback(t: TFunction): string {
  return t(
    "chat.turnFailed",
    "The agent could not answer this message. Check the server log or the conversation's audit trail for the reason.",
  );
}

/**
 * A sentence for a stream error EDDI classified, or null to keep its own text.
 *
 * `buildKnownConditionOrOpaqueErrorEvent` exists on the backend to turn the
 * conditions the streaming endpoint rejects synchronously into machine-readable
 * codes; its non-streaming twin answers a status for the same conditions.
 * `conversation_not_found` is the newest of them — the twin gained a 404 where
 * it used to answer 500 — and without a case here it reached the user as a raw
 * backend sentence naming an id they cannot act on.
 *
 * Deliberately not exhaustive over every code the backend may grow: an
 * unrecognised one falls back to the message it came with, which is more useful
 * than a generic apology.
 */
export function translateStreamError(
  code: string | undefined,
  t: TFunction,
): string | null {
  switch (code) {
    case "conversation_not_found":
      return t(
        "chat.errorConversationNotFound",
        "This conversation no longer exists. Start a new one to continue.",
      );
    case "conversation_ended":
      return t("chat.errorConversationEnded", "This conversation has ended.");
    case "agent_not_ready":
      return t(
        "chat.errorAgentNotReady",
        "This agent is not deployed to the selected environment yet.",
      );
    case "agent_mismatch":
      return t(
        "chat.errorAgentMismatch",
        "This conversation belongs to a different version of the agent.",
      );
    case "quota_accounting_unavailable":
      return t(
        "chat.errorQuotaUnavailable",
        "Usage limits could not be checked right now. Try again in a moment.",
      );
    default:
      return null;
  }
}

/** One field of a stream event's JSON payload. */
type Payload = Record<string, unknown>;

function parsePayload(data: string): Payload | null {
  try {
    const parsed: unknown = JSON.parse(data);
    return parsed && typeof parsed === "object" ? (parsed as Payload) : null;
  } catch {
    return null;
  }
}

const str = (v: unknown): string | undefined => (typeof v === "string" ? v : undefined);
const num = (v: unknown): number | undefined => (typeof v === "number" ? v : undefined);

/**
 * Feed one streamed event to the debug store: the activity card, the pipeline
 * trace and the live "Using {tool}…" line. Pure observability — the controller
 * applies the event to the transcript.
 */
function recordDebugEvent(event: SSEEvent): void {
  const debug = useDebugStore.getState();
  const p = event.type === "token" || event.type === "done" ? null : parsePayload(event.data);
  const taskId = (fallback: string) => str(p?.taskId) ?? str(p?.id) ?? fallback;
  const taskType = () => str(p?.taskType) ?? str(p?.type) ?? (p ? "unknown" : event.data || "unknown");
  switch (event.type) {
    case "token":
      // Output resuming IS the tool-finished signal — see liveToolsSettled.
      debug.markToolsSettled();
      return;
    case "task_start":
      debug.addEvent({ type: "task_start", taskId: taskId("unknown"), taskType: taskType(), index: num(p?.index) ?? 0, timestamp: Date.now() });
      return;
    case "task_complete":
      debug.addEvent({
        type: "task_complete",
        taskId: taskId("unknown"),
        taskType: taskType(),
        index: num(p?.index) ?? 0,
        durationMs: num(p?.durationMs) ?? num(p?.duration),
        actions: Array.isArray(p?.actions) ? (p.actions as string[]) : undefined,
        confidence: num(p?.confidence),
        toolTrace: Array.isArray(p?.toolTrace) ? (p.toolTrace as ToolTraceEntry[]) : undefined,
        timestamp: Date.now(),
      });
      return;
    case "task_failed":
      // Classified per-task failure for real-time admin monitoring; lights up
      // the "error" status in the activity card and pipeline views.
      debug.addEvent({
        type: "task_failed",
        taskId: taskId("unknown"),
        taskType: taskType(),
        index: num(p?.index) ?? 0,
        durationMs: num(p?.durationMs) ?? num(p?.duration),
        errorType: str(p?.errorType),
        errorSummary: str(p?.error) ?? str(p?.errorSummary),
        timestamp: Date.now(),
      });
      return;
    case "tool_call": {
      // The backend emits the NAME right before each tool executes (arguments
      // arrive later, redacted, in task_complete's toolTrace).
      const tool = str(p?.tool);
      if (tool) debug.addToolCall(tool);
      return;
    }
    case "cascade_step_start": {
      const stepIndex = num(p?.stepIndex) ?? 0;
      debug.addEvent({
        type: "cascade_step_start",
        taskId: taskId("cascade"),
        taskType: str(p?.modelType) ?? str(p?.type) ?? "unknown",
        index: stepIndex,
        stepIndex,
        modelName: str(p?.modelName) ?? str(p?.model),
        totalSteps: num(p?.totalSteps),
        timestamp: Date.now(),
      });
      return;
    }
    case "cascade_escalation": {
      const fromStep = num(p?.fromStep) ?? 0;
      debug.addEvent({
        type: "cascade_escalation",
        taskId: taskId("cascade"),
        taskType: "cascade",
        index: fromStep,
        fromStep,
        toStep: num(p?.toStep) ?? 0,
        confidence: num(p?.confidence),
        threshold: num(p?.threshold),
        reason: str(p?.reason),
        durationMs: num(p?.durationMs) ?? num(p?.duration),
        timestamp: Date.now(),
      });
      return;
    }
    default:
      return;
  }
}
/**
 * The user's input for one turn as the transcript should show it.
 *
 * A turn is masked on any of three signals, checked before any text is used:
 *
 * - it answered a field the agent asked for: the previous reply carried an
 *   `inputField`, and every such field (password, text or email) sends its
 *   answer with the secret flag and shows it masked while live;
 * - the turn's conversation OUTPUT carries EDDI's placeholder as its `input`;
 * - its `input:initial` IS the placeholder (a turn the backend scrubbed).
 *
 * The last is all `displayUserInput` (lib/api/conversations) checks; the chat
 * uses this superset instead, and shows the same glyph as a live secret bubble.
 *
 * The first is the one the Manager can rely on against any backend. The
 * simple (`returnDetailed=false`) snapshot every rebuild reads keeps only
 * `input:initial*`, `actions*`, `output*` and `quickReplies*` from an output,
 * so the output's `input` never arrives there, and a backend that does not
 * scrub a secret turn leaves the raw text in `input:initial` (only a
 * `scope:"secret"` property vaults it). Rebuilding from `input:initial` alone
 * put a pasted API key back on screen after every reload, resume, undo or
 * rerun. A quick reply chosen instead of filling the field is masked too,
 * which errs toward hiding. A 🔒-mode message sent without a field asking for it carries no
 * signal of its own in that snapshot; masking it on reload needs the backend
 * to return the masked display `input` or scrub `input:initial`.
 */
function displayedUserInput(
  step: SimpleConversationStep | undefined,
  output: ConversationOutput | undefined,
  previousOutput: ConversationOutput | undefined,
): string | undefined {
  const shown = output?.input;
  const raw = step ? extractInput(step) : undefined;
  const text = typeof shown === "string" && shown.trim() ? shown : raw;
  if (!text) return undefined;
  const answeredInputField = extractInputField(previousOutput) !== undefined;
  if (answeredInputField || shown === SECRET_INPUT_PLACEHOLDER || raw === SECRET_INPUT_PLACEHOLDER) {
    return SECRET_BUBBLE;
  }
  return text;
}

/** Helper: rebuild messages from a conversation snapshot */
function snapshotToMessages(snapshot: SimpleConversationMemorySnapshot): ChatMessage[] {
  const messages: ChatMessage[] = [];
  const outputs = snapshot.conversationOutputs ?? [];
  for (let i = 0; i < (snapshot.conversationSteps ?? []).length; i++) {
    const step = snapshot.conversationSteps[i];
    const input = displayedUserInput(step, outputs[i], i > 0 ? outputs[i - 1] : undefined);
    const parts = extractOutputParts(outputs[i]);
    if (input) {
      messages.push({
        id: `user-${messages.length}-${Date.now()}`,
        role: "user",
        content: input,
        timestamp: Date.now(),
        stepIndex: i,
      });
    }
    for (const part of parts) {
      messages.push({
        id: `agent-${messages.length}-${Date.now()}-${Math.random()}`,
        role: "agent",
        content: part,
        timestamp: Date.now(),
        stepIndex: i,
      });
    }
    // Designer images, as a live turn shows them (the controller's rule).
    const images = extractOutputImages(outputs[i]?.output);
    if (images.length) {
      messages.push({
        id: `agent-${messages.length}-${Date.now()}-${Math.random()}`,
        role: "agent",
        content: "",
        timestamp: Date.now(),
        stepIndex: i,
        images,
      });
    }
  }
  return messages;
}

/**
 * The `[undo, redo]` availability a snapshot reports.
 *
 * The backend computes `undoAvailable` from the FULL memory (more than one step:
 * step 0 is the conversation start and cannot be undone), so it is correct even
 * on the current-step-only snapshot a send returns. The step count is only a
 * fallback for a backend that omits the flag — and it is `> 1`, not `> 0`: the
 * old `> 0` offered Undo on a fresh conversation, whose only step the backend
 * then refused with a 409. Exported for tests.
 */
export function undoRedoFlags(
  snapshot: Pick<SimpleConversationMemorySnapshot, "undoAvailable" | "redoAvailable"> & {
    conversationSteps?: unknown[] | null;
  },
): [boolean, boolean] {
  return [
    snapshot.undoAvailable ?? (snapshot.conversationSteps?.length ?? 0) > 1,
    snapshot.redoAvailable ?? false,
  ];
}

/** Fetch conversation history for the selected agent. */
export function useConversationHistory(agentId: string | null) {
  return useQuery({
    queryKey: chatKeys.history(agentId),
    queryFn: () => getConversationDescriptors(50, 0, "", agentId ?? ""),
    enabled: !!agentId,
    staleTime: 30_000,
  });
}

/**
 * Read a conversation from the backend and make it the store's active one.
 *
 * `epoch` is the transcript generation the load was requested in (defaults to
 * the current one). A load whose generation has moved on by the time its read
 * returns is dropped: the user picked another agent, started a new
 * conversation or opened another one meanwhile. Without this a slow read of
 * agent A's history landed AFTER agent B had been picked and its conversation
 * started, and installed A's conversation under B's name, so every send then
 * went to agent A.
 */
/** The conversation to load belongs to a different agent than the one requested. */
export class ConversationAgentMismatchError extends Error {
  constructor() {
    super("This conversation belongs to a different agent than the one selected.");
    this.name = "ConversationAgentMismatchError";
  }
}

async function loadConversationIntoStore(
  agentId: string,
  conversationId: string,
  epoch: number = useChatStore.getState().conversationEpoch,
) {
  // Loads can overlap: two clicks in the render gap before the list disables
  // itself, or a resume racing a history pick. The store must reflect the most
  // recently *requested* conversation, not whichever read happened to finish
  // last — otherwise the pane shows one conversation while the list highlights
  // another.
  const request = ++latestLoadRequest;
  const store = useChatStore;

  // Sends are refused from here until the load settles — see
  // `loadingConversationId`. Only the latest request clears it: a load that
  // was superseded leaves it to whichever one superseded it (a later load, or
  // an action that replaced the transcript and cleared it itself).
  store.setState({ loadingConversationId: conversationId });
  let snapshot: Awaited<ReturnType<typeof readConversation>>;
  try {
    // Read first, touch the store second. Clearing up front meant a failed
    // read wiped the conversation the user was looking at and left a blank
    // pane with nothing to go back to.
    snapshot = await readConversation("production", agentId, conversationId, false);
  } finally {
    if (request === latestLoadRequest) store.setState({ loadingConversationId: null });
  }
  // Superseded mid-flight, by a later load or by anything else that replaced
  // the transcript (see the epoch note above).
  if (request !== latestLoadRequest || store.getState().conversationEpoch !== epoch) {
    return snapshot;
  }

  // The read is by conversation id alone, so a link that pairs one agent's
  // name (and its review notice) with another agent's conversation would show
  // the first while every send went to the second. The conversation's own
  // agent is authoritative: refuse the mismatch rather than install it.
  if (snapshot.agentId && snapshot.agentId !== agentId) {
    throw new ConversationAgentMismatchError();
  }

  store.getState().clearMessages();
  store.getState().setConversationId(conversationId);
  installSnapshot(snapshot);

  // A turn still executing (typically: the user left this conversation while
  // its reply streamed, and came back before it finished) is not in this read,
  // and the stream that would have delivered it was detached when they left.
  // Follow it until it settles. `clearMessages` above advanced both the epoch
  // and the load ticket, so these are the values that identify this install.
  if (snapshot.conversationState === "IN_PROGRESS") {
    void followExecutingTurn(conversationId, store.getState().conversationEpoch, latestLoadRequest);
  }

  return snapshot;
}

/** How often a reopened conversation whose turn is still executing is re-read. */
export const EXECUTING_TURN_POLL_MS = 1_500;
/**
 * How long such a turn is followed before the transcript is left as read. The
 * same ceiling the operator chat gives a turn it can see executing: a turn that
 * chains model calls and tools legitimately runs for minutes.
 */
export const EXECUTING_TURN_FOLLOW_MS = 300_000;

/**
 * Re-read a conversation that was installed while `IN_PROGRESS` until it
 * leaves that state, then install the settled transcript. The input shows as
 * processing meanwhile, so nothing is sent into a busy conversation. Stops
 * without writing as soon as anything else replaces the transcript (another
 * load, an agent switch, a new conversation). A failed read or the ceiling
 * leaves the transcript as it was read and releases the input.
 */
async function followExecutingTurn(conversationId: string, epoch: number, request: number) {
  const store = useChatStore;
  const owns = () => request === latestLoadRequest && store.getState().conversationEpoch === epoch;
  store.getState().setProcessing(true);
  const deadline = Date.now() + EXECUTING_TURN_FOLLOW_MS;
  try {
    while (Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, EXECUTING_TURN_POLL_MS));
      if (!owns()) return;
      const snapshot = await readConversation("production", "", conversationId, false);
      if (!owns()) return;
      if (snapshot.conversationState !== "IN_PROGRESS") {
        installSnapshot(snapshot);
        return;
      }
    }
  } catch {
    // Unreadable: keep what is on screen; a reload shows the rest.
  } finally {
    if (owns()) store.getState().setProcessing(false);
  }
}

/**
 * Put a snapshot of the conversation already selected in the store on screen:
 * its transcript, undo/redo, pause state and any requested input field.
 */
function installSnapshot(snapshot: SimpleConversationMemorySnapshot) {
  const store = useChatStore;
  const messages = snapshotToMessages(snapshot);
  store.getState().replaceMessages(messages);
  store.getState().setUndoRedo(...undoRedoFlags(snapshot));
  // Re-establish the pause state when loading a conversation that is still
  // AWAITING_HUMAN — otherwise a paused conversation opened from history
  // would show an enabled input with no banner until a send is rejected 409.
  // The reason is null on purpose: the comment here used to claim "the
  // snapshot carries the backend hitlPauseReason" — it never did; the field
  // was a phantom on the TS type and this read was undefined on every path.
  // The rendered reason comes from approval-status, which the banner overlays.
  store.getState().setPaused(
    snapshot.conversationState === "AWAITING_HUMAN",
    null,
    snapshot.hitlPausedAt ?? null,
  );
  // A conversation reopened while its last reply asks for a secret must offer
  // the masked field again, or the answer goes into the plain textarea. Set
  // either way: a settled turn that asks for nothing replaces the field its
  // predecessor asked for. While a turn executes, the last stored reply's
  // field is the one being answered, so none is offered.
  const outputs = snapshot.conversationOutputs ?? [];
  const settled = snapshot.conversationState !== "IN_PROGRESS";
  const inputField = settled ? extractInputField(outputs[outputs.length - 1]) : undefined;
  store.setState({ activeInputField: inputField ?? null });
  // The quick replies the last reply offered are still the open question: a
  // resumed conversation used to show none until the next turn. Same rule as
  // AgentConversation's resume (@eddi/conversation); set either way, so a
  // reply that offers none clears its predecessor's buttons. This stays here
  // until ChatPanel moves onto that controller.
  store.getState().setQuickReplies(settled ? extractQuickReplies(outputs[outputs.length - 1]) : []);
}

/** Load an existing conversation to resume it. */
export function useLoadConversation() {
  return useMutation({
    mutationFn: ({
      agentId,
      conversationId,
    }: {
      agentId: string;
      conversationId: string;
    }) => loadConversationIntoStore(agentId, conversationId),
  });
}

/**
 * Conversation states a user can pick up where they left off. `ENDED` and
 * `ERROR` are terminal, and `IN_PROGRESS` means a turn is still executing —
 * dropping into any of those would be worse than a clean start.
 */
const RESUMABLE_STATES: ReadonlySet<string> = new Set(["READY", "AWAITING_HUMAN"]);

/**
 * Pick the conversation to reopen for an agent: the most recently touched one
 * the user can continue, or `null` when there is nothing worth resuming.
 * Exported for tests.
 */
export function pickResumableConversation(
  descriptors: ConversationDescriptor[] | undefined,
  environment?: Environment,
): ConversationDescriptor | null {
  if (!descriptors?.length) return null;
  return (
    [...descriptors]
      .filter((c) => RESUMABLE_STATES.has(c.conversationState))
      // Resume only within the environment being opened. A conversation belongs
      // to the deployment it was created against, and the descriptors for an
      // agent span every environment — so without this, choosing "chat in test"
      // would silently resume the newest PRODUCTION conversation and the
      // environment choice would quietly not apply.
      //
      // A descriptor with no `environment` predates the field. Treated as
      // production, matching the backend's own default for the parameter that
      // would have created it — a legacy conversation is not evidence of a test
      // one, and guessing the other way would resume production history into a
      // test chat, which is the bug this filter exists to prevent.
      .filter((c) => environment === undefined || (c.environment ?? "production") === environment)
      // The backend does not promise an order — sort rather than trust index 0.
      .sort((a, b) => (b.lastModifiedOn ?? 0) - (a.lastModifiedOn ?? 0))[0] ?? null
  );
}

/**
 * Open an agent's chat: resume the conversation the user was last in, and only
 * start a new one when there is nothing to resume.
 *
 * Opening a chat used to always POST a new conversation, so every visit — and
 * every page reload — minted another empty one. History filled up with untouched
 * entries that looked broken when clicked (they load fine; there is simply
 * nothing in them), and the user never landed back where they left off.
 * Starting fresh is still available explicitly, via "New Conversation".
 */
export function useResumeOrStartConversation() {
  const queryClient = useQueryClient();
  const startConversationMutation = useStartConversation();

  return useMutation({
    mutationFn: async ({ agentId, environment }: { agentId: string; environment: Environment }) => {
      // The transcript this open was requested for. The history fetch below is
      // a network round trip; if the user has moved on by the time it returns,
      // neither a resumed nor a new conversation may be installed.
      const epoch = useChatStore.getState().conversationEpoch;
      let resumable: ConversationDescriptor | null = null;
      try {
        const descriptors = await queryClient.fetchQuery({
          queryKey: chatKeys.history(agentId),
          queryFn: () => getConversationDescriptors(50, 0, "", agentId),
          staleTime: 30_000,
        });
        resumable = pickResumableConversation(descriptors, environment);
      } catch {
        // History is a convenience, not a precondition — fall through to a new
        // conversation rather than leaving the user with a dead chat panel.
      }
      if (useChatStore.getState().conversationEpoch !== epoch) return null;

      if (resumable) {
        const conversationId = parseConversationUri(resumable.resource);
        try {
          await loadConversationIntoStore(agentId, conversationId, epoch);
          return conversationId;
        } catch {
          // The descriptor pointed at something we cannot read (deleted,
          // migrated, permissions) — start clean instead of failing.
        }
        // The failed read was a second round trip. If the user picked another
        // agent (or opened another conversation) while it ran, a fallback start
        // now would create a conversation with THIS agent — the one they left —
        // and install it under the one they moved to.
        if (useChatStore.getState().conversationEpoch !== epoch) return null;
      }

      // Environment-explicit: this used to inherit a hardcoded "production"
      // that was then discarded on the wire, so an agent live only in `test` had
      // no reachable conversation at all. Bound to the epoch this open was
      // requested in, not whatever is current when the start begins.
      return startConversationMutation.mutateAsync({ agentId, environment, epoch });
    },
  });
}

/**
 * Undo or redo one step, then re-read the conversation.
 *
 * The backend answers these with an empty 200 (moved) or a 409 (nothing to
 * move, or a concurrent turn / HITL pause got there first) — never with a
 * snapshot. So the transcript and both flags come from a fresh read either way:
 * after a 409 the server's view is exactly what the user needs to see, because
 * the buttons were evidently out of date.
 *
 * One move at a time: the flags only change after the re-read, so a second
 * click (or a second caller) in the meantime would POST another undo and take
 * back a second turn. A call made while one is in flight is dropped — it
 * resolves `false` without touching the server.
 *
 * Results are bound to the conversation they were issued for: if the user
 * switched conversation meanwhile, nothing is written to the store. (The chat
 * store has no conversation epoch on main yet; comparing the id is the guard
 * available here, and moving these onto an epoch is a follow-up.)
 */
let stepMoveInFlight = false;

async function moveStepAndReload(move: "undo" | "redo"): Promise<boolean> {
  const store = useChatStore;
  const { selectedAgentId, conversationId } = store.getState();
  if (!selectedAgentId || !conversationId) throw new Error("No active conversation");
  if (stepMoveInFlight) return false;
  stepMoveInFlight = true;
  try {
    const moved = move === "undo"
      ? await undoConversationApi("production", selectedAgentId, conversationId)
      : await redoConversationApi("production", selectedAgentId, conversationId);

    let snapshot: SimpleConversationMemorySnapshot;
    try {
      snapshot = await readConversation("production", selectedAgentId, conversationId, false);
    } catch (err) {
      // The move may already have happened, so the transcript on screen can no
      // longer be trusted and neither can the buttons: disable both until the
      // conversation is reloaded, rather than inviting another blind move.
      if (store.getState().conversationId === conversationId) {
        store.getState().setUndoRedo(false, false);
      }
      throw err;
    }
    if (store.getState().conversationId !== conversationId) return moved;
    store.getState().replaceMessages(snapshotToMessages(snapshot));
    store.getState().setUndoRedo(...undoRedoFlags(snapshot));
    store.getState().setPaused(
      snapshot.conversationState === "AWAITING_HUMAN",
      null,
      snapshot.hitlPausedAt ?? null,
    );
    return moved;
  } finally {
    stepMoveInFlight = false;
  }
}

/** Undo the last conversation step. Resolves `false` when the backend had nothing to undo. */
export function useUndoConversation() {
  return useMutation({ mutationFn: () => moveStepAndReload("undo") });
}

/** Redo a previously undone step. Resolves `false` when the backend had nothing to redo. */
export function useRedoConversation() {
  return useMutation({ mutationFn: () => moveStepAndReload("redo") });
}

/**
 * End a conversation — the one passed, which callers capture when the user
 * clicks, or else the current one.
 *
 * The local transcript is cleared only when the end succeeded AND that
 * conversation is still the one on screen: the request is a round trip, and
 * the user may have opened another conversation meanwhile, which must not be
 * wiped for the one they left. A failure rejects, so the caller can say so.
 */
export function useEndConversation() {
  const store = useChatStore;
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (requestedId?: string | null) => {
      const conversationId = requestedId ?? store.getState().conversationId;
      if (!conversationId) throw new Error("No active conversation");
      await endConversationApi(conversationId);
      if (store.getState().conversationId === conversationId) {
        store.getState().clearMessages();
      }
      return conversationId;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: chatKeys.history() });
    },
  });
}

/** Rerun the last conversation step (retry after error), then reload. */
export function useRerunConversation() {
  const store = useChatStore;
  return useMutation({
    mutationFn: async () => {
      const { selectedAgentId, conversationId } = store.getState();
      if (!selectedAgentId || !conversationId) throw new Error("No active conversation");

      // Trigger server-side re-execution
      await rerunLastStep(conversationId);

      // Reload conversation to pick up the new output
      const snapshot = await readConversation(
        "production",
        selectedAgentId,
        conversationId,
        false
      );
      // Same guard as undo/redo: a rerun that finishes after the user switched
      // conversation must not replace the new transcript with the old one.
      if (store.getState().conversationId !== conversationId) return snapshot;
      const messages = snapshotToMessages(snapshot);
      store.getState().replaceMessages(messages);
      store.getState().setUndoRedo(...undoRedoFlags(snapshot));
      return snapshot;
    },
  });
}
