/* ──────────────────────────────────────────────
   ChatWidget — Main chat orchestrator
   Manages conversation lifecycle, message flow,
   and composes all sub-components.
   ────────────────────────────────────────────── */

import { useEffect, useRef, useState, useCallback } from "react";
import { useParams, useSearchParams } from "react-router-dom";

import { useChatState, useChatDispatch } from "@/store/chat-store";
import { MessageBubble } from "./MessageBubble";
import { ChatInput } from "./ChatInput";
import { SecretInput } from "./SecretInput";
import { QuickReplies } from "./QuickReplies";
import { TypingIndicator, ThinkingIndicator } from "./Indicators";
import { ScrollToBottom } from "./ScrollToBottom";
import { ChatHeader } from "./ChatHeader";

import {
  startConversation,
  readConversation,
  sendMessage,
  sendMessageStreaming,
  sendManagedAgentMessage,
  loadManagedConversation,
  undoConversation,
  redoConversation,
  fetchAgentDescriptor,
  rerunLastStep,
  endManagedConversation,
  setBaseUrl,
} from "@/api/chat-api";
import { setAuthToken } from "@/api/http";
import {
  isDemoMode,
  demoStartConversation,
  demoSendMessageStreaming,
  demoGetQuickReplies,
} from "@/api/demo-api";
import { buildAttachmentContext } from "@/api/attachments-api";
import { stepsToMessages } from "@/api/snapshot";
import { cancelConversation, type ApprovalStatus } from "@/api/hitl-api";
import { useHitlPolling } from "@/hooks/useHitlPolling";
import { PausedCard } from "./PausedCard";
import { ApiError } from "@/api/http";
import {
  parseDoneSnapshot,
  parseErrorEvent,
  isSkippedTurn,
  skippedTurnMessage,
  isPausedState,
  extractOutputTexts,
  extractOutputImages,
  findInputField,
  isTurnPaused,
  UNCONSUMED_STREAM_ERROR_CODES,
  type OutputImage,
} from "@/api/sse-events";
import type {
  ChatMessage,
  SSEEvent,
  ChatConfig,
  ConversationState,
  ConversationSnapshot,
} from "@/types";

/**
 * Per-turn mutable bookkeeping for the SSE loop. `tokenCount` is what
 * distinguishes a genuinely empty answer from a turn the server dropped.
 */
interface TurnContext {
  tokenCount: number;
  /**
   * The conversation state as it was when this turn was sent. Required to tell
   * a turn that PAUSED (accepted, then gated) from one the server DROPPED
   * (rejected because the conversation was already paused/busy/ended) — both
   * end with zero tokens and the same conversationState.
   */
  stateBeforeSend: ConversationState | null;
  /** Id of this turn's optimistic user bubble, so a dropped turn can withdraw it. */
  userMessageId: string | null;
}

function newTurn(
  stateBeforeSend: ConversationState | null,
  userMessageId: string | null = null,
): TurnContext {
  return { tokenCount: 0, stateBeforeSend, userMessageId };
}

function makeAgentMessage(content: string, images?: OutputImage[]): ChatMessage {
  return {
    id: `agent-${Date.now()}-${Math.random()}`,
    role: "agent",
    content,
    timestamp: Date.now(),
    ...(images?.length ? { images } : {}),
  };
}

/** Transcript copy for a conversation that could not be started. */
function startFailureMessage(err: unknown, environment?: string): string {
  if (err instanceof ApiError && err.status === 404) {
    return environment
      ? `⚠️ This agent is not available in the "${environment}" environment. It may not be deployed there.`
      : "⚠️ This agent is not available. It may not be deployed.";
  }
  if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
    return "⚠️ You are not allowed to start a conversation with this agent.";
  }
  if (err instanceof ApiError && err.status === 400) {
    return "⚠️ The conversation could not be started. Check the address — the environment must be \"production\" or \"test\".";
  }
  return "⚠️ The conversation could not be started. Please try again.";
}

/**
 * Color query params → CSS variable mappings.
 * Usage: ?accentColor=%2300ff88&bgColor=%23111111
 * (URL-encode # as %23)
 */
const COLOR_PARAM_MAP: Record<string, string[]> = {
  accentColor:  ["--chat-accent", "--chat-user-bg", "--chat-input-focus"],
  accentHover:  ["--chat-accent-hover"],
  bgColor:      ["--chat-bg"],
  surfaceColor: ["--chat-surface"],
  textColor:    ["--chat-text"],
  textMuted:    ["--chat-text-muted"],
  agentBg:        ["--chat-agent-bg"],
  agentBorder:    ["--chat-agent-border"],
  agentText:      ["--chat-agent-text"],
  userBg:       ["--chat-user-bg"],
  userText:     ["--chat-user-text"],
  inputBg:      ["--chat-input-bg"],
  inputBorder:  ["--chat-input-border"],
  borderColor:  ["--chat-border"],
  headerBg:     ["--chat-surface"],
  fontFamily:   ["--chat-font"],
};

/** Apply color overrides from query params as CSS custom properties */
function applyColorOverrides(params: URLSearchParams): void {
  const root = document.documentElement;
  for (const [param, vars] of Object.entries(COLOR_PARAM_MAP)) {
    const value = params.get(param);
    if (value) {
      for (const cssVar of vars) {
        root.style.setProperty(cssVar, value);
      }
    }
  }
  // accentColor also derives soft/hover variants automatically
  const accent = params.get("accentColor");
  if (accent) {
    root.style.setProperty("--chat-accent-soft", accent + "22");
    root.style.setProperty("--chat-qr-border", accent + "66");
    root.style.setProperty("--chat-qr-bg", accent + "2e");
    root.style.setProperty("--chat-qr-text", accent);
    root.style.setProperty("--chat-qr-hover", accent + "55");
  }
}

/** Read feature toggles from query parameters */
function parseConfigFromQuery(params: URLSearchParams): Partial<ChatConfig> {
  const cfg: Partial<ChatConfig> = {};
  if (params.get("hideUndo") === "true") cfg.enableUndo = false;
  if (params.get("hideRedo") === "true") cfg.enableRedo = false;
  if (params.get("hideNewConversation") === "true") cfg.enableNewConversation = false;
  if (params.get("hideQuickReplies") === "true") cfg.enableQuickReplies = false;
  if (params.get("hideStreaming") === "true") cfg.enableStreaming = false;
  if (params.get("hideLogo") === "true") cfg.showLogo = false;
  if (params.get("hideAgentName") === "true") cfg.showAgentName = false;
  if (params.get("theme")) cfg.theme = params.get("theme") as ChatConfig["theme"];
  if (params.get("title")) cfg.title = params.get("title")!;
  if (params.get("accentColor")) cfg.accentColor = params.get("accentColor")!;
  return cfg;
}

export function ChatWidget() {
  const state = useChatState();
  const dispatch = useChatDispatch();

  const { environment, agentId, userId: userIdParam, intent } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  /**
   * `?token=` is read ONCE and then removed from the address bar. Left there,
   * a bearer token sat in the visible URL, the history entry, and anything the
   * user copied or bookmarked.
   */
  const [urlToken] = useState(() => searchParams.get("token"));
  useEffect(() => {
    if (!searchParams.has("token")) return;
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete("token");
        return next;
      },
      { replace: true },
    );
  }, [searchParams, setSearchParams]);

  const userId =
    userIdParam ?? searchParams.get("userId") ?? undefined;
  const apiServer = searchParams.get("apiServer");
  const isManagedAgent = !!intent;
  const isDemo = isDemoMode(environment, agentId);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  const [showScrollBtn, setShowScrollBtn] = useState(false);
  const initializedRef = useRef(false);
  /** Controller for the in-flight SSE read, so it can be stopped on demand. */
  const abortRef = useRef<AbortController | null>(null);
  /**
   * Live conversation state. handleSend must read this at CLICK time; taking it
   * from the callback closure made it stale by however long the memo lived, so
   * skipped-turn detection compared against an out-of-date state.
   */
  const conversationStateRef = useRef(state.conversationState);
  /**
   * Mirrors isProcessing for reads at click time. The composer disables itself
   * while busy, but QuickReplies and SecretInput call handleSend directly, so
   * the guard has to live in handleSend rather than in each caller.
   */
  const isProcessingRef = useRef(state.isProcessing);
  /**
   * Synced after commit, not during render. Assigning during render is impure:
   * a render React discards — StrictMode's double invoke, or a concurrent
   * re-render that never commits — would leave these holding state the user
   * never saw, and handleSend would then guard against it. Every reader is a
   * click handler, which cannot run before the commit, so this is timing
   * equivalent and strictly safer.
   */
  useEffect(() => {
    conversationStateRef.current = state.conversationState;
    isProcessingRef.current = state.isProcessing;
  }, [state.conversationState, state.isProcessing]);
  /**
   * Bumped whenever the widget switches conversation. Async continuations
   * capture it and bail if it moved, so an abandoned stream cannot write into
   * the conversation that replaced it.
   */
  const generationRef = useRef(0);
  /**
   * Raw texts sent this session with secret mode on. The backend stores
   * `input:initial` unmasked, so a transcript rebuild would print them in
   * clear; this is the only thing that can mask them client-side.
   *
   * Session-scoped by nature. After a reload, the turn output's `input` —
   * "<secret input>" for a secret turn — is what masks it (stepsToMessages);
   * this set covers a backend that does not send that key.
   */
  const secretTextsRef = useRef<Set<string>>(new Set());
  /**
   * Raw input of in-flight turns, keyed by the user message's id. A single
   * slot let a late-failing turn withdraw a newer, unrelated message.
   */
  const pendingTurnsRef = useRef<
    Map<string, { text: string; attachments: typeof state.pendingAttachments; isSecret: boolean }>
  >(new Map());

  /* ─── Apply query param config + colors on mount ── */
  useEffect(() => {
    const queryCfg = parseConfigFromQuery(searchParams);
    if (Object.keys(queryCfg).length > 0) {
      dispatch({ type: "SET_CONFIG", config: queryCfg });
    }
    // Apply CSS custom property color overrides
    applyColorOverrides(searchParams);
  }, [searchParams, dispatch]);


  /* ─── Set base URL + auth on mount ──────────── */
  useEffect(() => {
    setBaseUrl(apiServer ?? state.config.apiBaseUrl ?? "");
  }, [apiServer, state.config.apiBaseUrl]);

  useEffect(() => {
    // Query param is a convenience for embedding; config is the real channel.
    setAuthToken(urlToken ?? state.config.authToken ?? null);
  }, [urlToken, state.config.authToken]);

  /* ─── SSE event handler (declared early to avoid reference issues) ──
     Returns `true` when the stream is logically complete (done / error),
     so the caller can break out of the for-await loop.                  */
  const handleSSEEvent = useCallback(
    (event: SSEEvent, turn: TurnContext): boolean => {
      switch (event.type) {
        case "token":
          turn.tokenCount += 1;
          dispatch({ type: "SET_THINKING", value: false });
          // Text is flowing, so whichever model won the cascade is answering.
          dispatch({ type: "SET_ESCALATING", value: false });
          dispatch({ type: "APPEND_TO_LAST_AGENT", token: event.data });
          return false;

        // Pipeline progress. The backend emits no "thinking" event, so these
        // are what actually tell us the agent is working before any text.
        case "task_start":
          if (turn.tokenCount === 0) {
            dispatch({ type: "SET_THINKING", value: true });
          }
          return false;

        case "task_complete":
          return false;

        case "task_failed":
          // Structured per-task failure (#593). The turn may still recover
          // (cascade escalation, retry), so `done`/`error` decides the final
          // outcome — but stop implying the agent is still composing.
          dispatch({ type: "SET_THINKING", value: false });
          return false;

        // Step starts are pure observability — task_start already raised the
        // thinking indicator, and it is guarded on tokenCount so it cannot come
        // back after text has begun. Raising it again here would undo that: a
        // guaranteed-accept step may stream live, time out mid-stream, and be
        // followed by the next step's start.
        case "cascade_step_start":
          return false;

        // An escalation means a cheaper model was abandoned mid-turn. The wait
        // that follows is silent — a buffered cascade emits its whole answer as
        // one token — so say something rather than leave a bare spinner.
        case "cascade_escalation":
          dispatch({ type: "SET_ESCALATING", value: true });
          return false;

        case "done": {
          const snapshot = parseDoneSnapshot(event.data);
          const lastOutput = snapshot?.conversationOutputs?.length
            ? snapshot.conversationOutputs[snapshot.conversationOutputs.length - 1]
            : undefined;
          const outputText = extractOutputTexts(lastOutput?.output).join("\n\n");
          const outputImages = extractOutputImages(lastOutput?.output);
          const skipped = isSkippedTurn(snapshot, turn.tokenCount, turn.stateBeforeSend);

          // A requested input field (a password prompt) arrives here on the
          // streaming path, which is the default. Reading it only from the
          // non-streaming snapshot meant the key went into the plain textarea,
          // was shown in clear and was sent without `secretInput`. A skipped
          // turn carries the PREVIOUS step's outputs, so it must not re-apply.
          const inputField = skipped ? null : findInputField(lastOutput?.output);
          if (inputField) {
            dispatch({ type: "SET_INPUT_FIELD", field: inputField });
          }

          if (skipped) {
            // The server dropped this turn without consuming it. The payload
            // carries the PREVIOUS step's outputs, so its quick replies must
            // not be applied — doing so re-offered stale buttons as if new.
            //
            // The user's own bubble must go too: leaving it there asserts the
            // message was sent when it never reached the agent. Withdrawing it
            // also hands the text back to the composer so it can be resent.
            const pending = turn.userMessageId
              ? pendingTurnsRef.current.get(turn.userMessageId)
              : undefined;
            if (turn.userMessageId) {
              pendingTurnsRef.current.delete(turn.userMessageId);
              dispatch({
                type: "WITHDRAW_LAST_USER_MESSAGE",
                messageId: turn.userMessageId,
                draft: pending?.text,
                attachments: pending?.attachments,
                wasSecret: pending?.isSecret,
              });
            } else {
              dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
            }
            dispatch({
              type: "ADD_MESSAGE",
              message: makeAgentMessage(
                skippedTurnMessage(snapshot?.conversationState),
              ),
            });
          } else if (turn.tokenCount === 0) {
            // No tokens, but the turn WAS accepted. Any text lives only in the
            // done payload — including HITL's pending-approval placeholder,
            // which arrives as a bare string. Dropping it left a paused turn
            // rendering as "No response".
            dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
            if (outputText || outputImages.length) {
              dispatch({
                type: "ADD_MESSAGE",
                message: makeAgentMessage(outputText, outputImages),
              });
            }
            if (!isTurnPaused(snapshot, turn.stateBeforeSend)) {
              dispatch({
                type: "SET_QUICK_REPLIES",
                replies: lastOutput?.quickReplies ?? [],
              });
            }
          } else if (snapshot?.conversationOutputs?.length) {
            // Tokens already rendered the answer, but responseValidation can
            // SUPERSEDE them (fallback text) after the fact. The snapshot is
            // authoritative.
            if (outputText) {
              dispatch({ type: "RECONCILE_LAST_AGENT", content: outputText });
            }
            // Images never stream as tokens; the snapshot is their only source.
            if (outputImages.length) {
              dispatch({
                type: "ADD_MESSAGE",
                message: makeAgentMessage("", outputImages),
              });
            }
            dispatch({
              type: "SET_QUICK_REPLIES",
              replies: lastOutput?.quickReplies ?? [],
            });
          }

          if (snapshot?.conversationState) {
            dispatch({
              type: "SET_CONVERSATION_STATE",
              state: snapshot.conversationState,
            });
          }

          dispatch({ type: "FINISH_STREAMING" });
          dispatch({ type: "SET_PROCESSING", value: false });
          return true;
        }

        case "error": {
          // Payload is {"message":"…","code":"…"}, not a bare string.
          const { message, code } = parseErrorEvent(event.data);
          if (
            turn.tokenCount === 0 &&
            code !== null &&
            UNCONSUMED_STREAM_ERROR_CODES.has(code)
          ) {
            // The server refused this turn before running it — the streaming
            // twin of a 409. Withdraw the optimistic bubble and hand the draft
            // (a secret included, masked) back, instead of leaving a message
            // in the transcript that never reached the agent. The refresh after
            // the stream then picks up the paused state for awaiting_approval.
            const pending = turn.userMessageId
              ? pendingTurnsRef.current.get(turn.userMessageId)
              : undefined;
            if (turn.userMessageId) {
              pendingTurnsRef.current.delete(turn.userMessageId);
              dispatch({
                type: "WITHDRAW_LAST_USER_MESSAGE",
                messageId: turn.userMessageId,
                draft: pending?.text,
                attachments: pending?.attachments,
                wasSecret: pending?.isSecret,
              });
            } else {
              dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
            }
            dispatch({
              type: "ADD_MESSAGE",
              message: makeAgentMessage(
                code === "awaiting_approval"
                  ? "⚠️ Your message was not sent — this conversation is waiting on a decision."
                  : `⚠️ Your message was not sent — ${message}`,
              ),
            });
            dispatch({ type: "FINISH_STREAMING" });
            dispatch({ type: "SET_PROCESSING", value: false });
            return true;
          }
          if (turn.tokenCount === 0) {
            dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
            dispatch({
              type: "ADD_MESSAGE",
              message: makeAgentMessage(`⚠️ ${message}`),
            });
          } else {
            dispatch({
              type: "APPEND_TO_LAST_AGENT",
              token: `\n\n⚠️ ${message}`,
            });
          }
          dispatch({ type: "FINISH_STREAMING" });
          dispatch({ type: "SET_PROCESSING", value: false });
          return true;
        }

        default:
          // Unknown event: ignore rather than render. New backend events must
          // never leak into the transcript as visible text.
          return false;
      }
    },
    [dispatch],
  );

  /* ─── Process conversation snapshot ─────────── */
  const processSnapshot = useCallback(
    /**
     * @param dedupe Suppress texts already rendered from an identical snapshot
     *   slot. ONLY for reads that deliberately revisit a step already shown —
     *   retry and the post-approval refresh. It must stay off for ordinary
     *   sends: `returnCurrentStepOnly=true` pins every response to one output
     *   at index 0, so the key degenerates to the reply text and a repeated
     *   utterance (a fallback, a re-prompt) would be silently swallowed.
     */
    (snapshot: Partial<ConversationSnapshot>, { dedupe = false }: { dedupe?: boolean } = {}) => {
      // Managed-agent mode never calls startConversation, so without this the
      // widget has no conversationId — disabling HITL polling, cancel, retry
      // and attachments for the entire managed route.
      if (snapshot.conversationId) {
        dispatch({ type: "SET_CONVERSATION_ID", id: snapshot.conversationId });
      }
      if (snapshot.conversationState) {
        dispatch({
          type: "SET_CONVERSATION_STATE",
          state: snapshot.conversationState,
        });
      }

      // Track undo/redo availability
      dispatch({
        type: "SET_UNDO_REDO",
        undoAvailable: snapshot.undoAvailable ?? false,
        redoAvailable: snapshot.redoAvailable ?? false,
      });

      // Handle the "conversationOutputs" format (from POST /agents responses)
      if (snapshot.conversationOutputs?.length) {
        snapshot.conversationOutputs.forEach((output, index, outputs) => {
          // Extract agent replies and detect input field requests
          const agentReplies: unknown[] = output.output ?? [];

          // inputField items configure the composer rather than the transcript,
          // and only the LATEST turn's request is still open: a full-snapshot
          // refresh (HITL resume) would otherwise re-raise a password prompt
          // from an earlier turn that was already answered.
          if (index === outputs.length - 1) {
            const field = findInputField(agentReplies);
            if (field) dispatch({ type: "SET_INPUT_FIELD", field });
          }

          // Handles bare-string entries too — HITL's pending-approval
          // placeholder and reviewer-rejection message arrive as raw strings.
          extractOutputTexts(agentReplies).forEach((text) => {
            const message = makeAgentMessage(text);
            dispatch({
              type: dedupe ? "ADD_SNAPSHOT_MESSAGE" : "ADD_MESSAGE",
              message,
            });
          });
          const images = extractOutputImages(agentReplies);
          if (images.length && !dedupe) {
            dispatch({ type: "ADD_MESSAGE", message: makeAgentMessage("", images) });
          }
        });

        // Quick replies from the last output (most recent step)
        const lastOutput =
          snapshot.conversationOutputs[snapshot.conversationOutputs.length - 1];
        dispatch({
          type: "SET_QUICK_REPLIES",
          replies: lastOutput.quickReplies ?? [],
        });
      }

      // conversationSteps is a FALLBACK, not an additional source. The backend
      // populates BOTH lists from the same memory for every response
      // (ConversationMemoryUtilities:172-209) and only nulls one out when the
      // caller passes returningFields, which this client never sends. Rendering
      // both showed every reply twice — and echoed the user's `input:initial`,
      // which is the raw text even for a secret turn.
      //
      // This block was inert before the step shape was corrected, which is why
      // the duplication only appeared once the mapping started working.
      else if (snapshot.conversationSteps?.length) {
        for (const message of stepsToMessages(
          snapshot.conversationSteps,
          secretTextsRef.current,
          snapshot.conversationOutputs,
        )) {
          dispatch({
            type: dedupe ? "ADD_SNAPSHOT_MESSAGE" : "ADD_MESSAGE",
            message,
          });
        }
      }
    },
    [dispatch],
  );

  /** Show the agent's name, once per open, when the descriptor is readable. */
  const loadAgentName = useCallback(
    (snapshot: { agentId?: string; agentVersion?: number }, gen: number) => {
      if (isDemo || state.config.showAgentName === false) return;
      const id = snapshot.agentId || agentId;
      const version = snapshot.agentVersion;
      if (!id || typeof version !== "number") return;
      fetchAgentDescriptor(id, version).then((desc) => {
        if (desc.name && gen === generationRef.current) {
          dispatch({ type: "SET_AGENT_NAME", name: desc.name });
        }
      });
    },
    [dispatch, isDemo, agentId, state.config.showAgentName],
  );

  /**
   * Open a conversation: the one the route names on first load, a fresh one on
   * restart. Every await re-checks the generation: the first load used to have
   * no such check, so "New conversation" clicked while its welcome read was
   * still in flight got the OLD conversation's greeting grafted onto the new
   * one, and its id could overwrite the new id.
   */
  const openConversation = useCallback(
    async (fresh: boolean) => {
      const gen = generationRef.current;
      try {
        if (isDemo) {
          // Demo mode: use mock data
          const result = await demoStartConversation();
          if (gen !== generationRef.current) return;
          dispatch({ type: "SET_CONVERSATION_ID", id: result.conversationId });
          dispatch({ type: "ADD_MESSAGE", message: result.welcomeMessage });
          dispatch({ type: "SET_QUICK_REPLIES", replies: result.quickReplies });
          dispatch({ type: "SET_CONVERSATION_STATE", state: "READY" });
        } else if (isManagedAgent && intent && userId) {
          // A managed load always returns the CURRENT conversation, so a
          // restart must end it first — otherwise "New conversation" re-showed
          // the same one and the agent kept its whole context.
          if (fresh) await endManagedConversation(intent, userId);
          const snapshot = await loadManagedConversation(intent, userId);
          if (gen !== generationRef.current) return;
          processSnapshot(snapshot);
          loadAgentName(snapshot, gen);
        } else if (environment && agentId) {
          // Direct agent: POST to create conversation
          const convId = await startConversation(environment, agentId, userId);
          if (gen !== generationRef.current) return;
          dispatch({ type: "SET_CONVERSATION_ID", id: convId });

          // GET to pick up welcome message
          const snapshot = await readConversation(environment, agentId, convId);
          if (gen !== generationRef.current) return;
          processSnapshot(snapshot);
          loadAgentName(snapshot, gen);
        }
      } catch (err) {
        if (gen !== generationRef.current) return;
        console.error("Failed to start conversation:", err);
        // A failed start used to reach the console only, leaving the widget
        // on "Starting conversation…" for good.
        dispatch({
          type: "ADD_MESSAGE",
          message: makeAgentMessage(startFailureMessage(err, environment)),
        });
      }
    },
    [
      dispatch,
      isDemo,
      isManagedAgent,
      intent,
      userId,
      environment,
      agentId,
      processSnapshot,
      loadAgentName,
    ],
  );

  /* ─── Auto-start conversation ───────────────── */
  useEffect(() => {
    if (initializedRef.current) return;
    initializedRef.current = true;
    openConversation(false);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /* ─── Send message ──────────────────────────── */
  const handleSend = useCallback(
    async (text: string, isSecret?: boolean) => {
      // Re-entrancy guard. QuickReplies and SecretInput bypass the composer's
      // disabled state, so a click during an in-flight turn used to start a
      // second one — two streams writing into the same transcript.
      if (isProcessingRef.current) return;

      // Conversation identity for THIS turn. Every async continuation below
      // must re-check it: New Conversation can land while a request is in
      // flight, and an unguarded continuation then grafts the abandoned turn
      // onto the conversation that replaced it.
      const sendGen = generationRef.current;

      // Attachments staged in the composer travel with THIS turn as
      // attachment_N context entries — the only path the backend reads.
      const attachments = state.pendingAttachments;
      const attachmentContext = buildAttachmentContext(attachments);

      const secretContext = isSecret
        ? { secretInput: { type: "string" as const, value: "true" } }
        : undefined;

      const context =
        secretContext || Object.keys(attachmentContext).length > 0
          ? { ...secretContext, ...attachmentContext }
          : undefined;

      // Add user message (display masked if secret). Attachments are named in
      // the transcript so the user can see what was actually sent.
      // A file above the forward limit was stored but will not be inlined. The
      // model gets a note in its place rather than the bytes, so the agent may
      // or may not be able to reach the content — say the part we know is true.
      // "assistant", not "model": nothing else user-facing here uses that word.
      //
      // Note this line lives in the bubble TEXT, so it does not survive an
      // undo/redo — stepsToMessages rebuilds user bubbles from the raw
      // input:initial. Do not treat it as a durable record.
      const attachmentLine = attachments.length
        ? attachments
            .map((a) =>
              a.forwardableInline === false
                ? `📎 ${a.fileName} — too large to send directly`
                : `📎 ${a.fileName}`,
            )
            .join("\n")
        : "";
      const displayed = isSecret ? "●●●●●●●●" : text;
      const userMsg: ChatMessage = {
        id: `user-${Date.now()}-${Math.random()}`,
        role: "user",
        content: [attachmentLine, displayed].filter(Boolean).join("\n\n"),
        timestamp: Date.now(),
      };
      // Remember the REAL input per turn: the bubble content is display text
      // (masked secrets, "📎 name" lines) and must never be what we hand back.
      const turnId = userMsg.id;
      pendingTurnsRef.current.set(turnId, {
        text,
        attachments,
        isSecret: !!isSecret,
      });
      if (isSecret && text.trim()) secretTextsRef.current.add(text.trim());
      const withdrawTurn = () => {
        const pending = pendingTurnsRef.current.get(turnId);
        pendingTurnsRef.current.delete(turnId);
        dispatch({
          type: "WITHDRAW_LAST_USER_MESSAGE",
          messageId: turnId,
          draft: pending?.text,
          attachments: pending?.attachments,
          wasSecret: pending?.isSecret,
        });
      };

      dispatch({ type: "ADD_MESSAGE", message: userMsg });
      dispatch({ type: "CLEAR_ATTACHMENTS" });
      // A requested input field lasts for ONE reply. A quick reply answers it
      // too, and must not leave a stale password prompt over the next turn.
      dispatch({ type: "CLEAR_INPUT_FIELD" });
      dispatch({ type: "SET_QUICK_REPLIES", replies: [] });
      dispatch({ type: "SET_PROCESSING", value: true });
      dispatch({ type: "SET_THINKING", value: true });
      // Start every turn un-escalated, however the previous one ended.
      dispatch({ type: "SET_ESCALATING", value: false });

      try {
        if (isDemo) {
          // Demo mode: simulated streaming response
          dispatch({
            type: "ADD_MESSAGE",
            message: {
              id: `agent-${Date.now()}-${Math.random()}`,
              role: "agent",
              content: "",
              timestamp: Date.now(),
              isStreaming: true,
            },
          });

          const events = demoSendMessageStreaming(text);
          const demoTurn = newTurn(conversationStateRef.current);
          let demoDone = false;
          for await (const event of events) {
            if (handleSSEEvent(event, demoTurn)) demoDone = true;
          }
          // Safety net: finish streaming if the stream closed without a done event
          if (!demoDone) dispatch({ type: "FINISH_STREAMING" });

          // Set quick replies after streaming completes
          const qrs = demoGetQuickReplies(text);
          dispatch({ type: "SET_QUICK_REPLIES", replies: qrs });
        } else if (isManagedAgent && intent && userId) {
          // Managed agent (non-streaming only)
          const snapshot = await sendManagedAgentMessage(
            intent,
            userId,
            text,
            context,
          );
          if (sendGen !== generationRef.current) return;
          dispatch({ type: "SET_THINKING", value: false });
          processSnapshot(snapshot);
          dispatch({ type: "SET_PROCESSING", value: false });
        } else if (
          state.config.enableStreaming &&
          environment &&
          agentId &&
          state.conversationId
        ) {
          // Streaming path
          dispatch({
            type: "ADD_MESSAGE",
            message: {
              id: `agent-${Date.now()}-${Math.random()}`,
              role: "agent",
              content: "",
              timestamp: Date.now(),
              isStreaming: true,
            },
          });

          // AbortController: break out of the for-await when "done" fires.
          // Proxies (Vite dev, nginx) may not forward the SSE close signal,
          // so reader.read() would hang forever without this.
          const abort = new AbortController();
          abortRef.current = abort;
          const gen = generationRef.current;
          const turn = newTurn(conversationStateRef.current, turnId);
          let streamDone = false;

          const events = sendMessageStreaming(
            environment,
            agentId,
            state.conversationId,
            text,
            context,
            abort.signal,
          );

          try {
            for await (const event of events) {
              // New Conversation (or another swap) happened mid-stream: stop
              // writing tokens, state and undo/redo into the conversation that
              // replaced this one.
              if (gen !== generationRef.current) break;
              const isDone = handleSSEEvent(event, turn);
              if (isDone) {
                streamDone = true;
                abort.abort();
                break;
              }
            }
          } catch (e) {
            if (e instanceof DOMException && e.name === "AbortError") {
              // expected after abort — swallow
            } else {
              throw e;
            }
          }
          // Every continuation past this point belongs to the turn we just
          // read. If the conversation was swapped mid-stream, this turn is
          // abandoned — including its safety net, which would otherwise clear
          // isProcessing/isThinking and un-stream a bubble in the conversation
          // that replaced it.
          if (gen !== generationRef.current) return;

          // Safety net: finish streaming if the stream closed without a done event
          if (!streamDone) dispatch({ type: "FINISH_STREAMING" });

          // The `done` payload is a trimmed snapshot carrying only
          // conversationState and conversationOutputs — undoAvailable and
          // redoAvailable are absent, so without this re-read the undo/redo
          // buttons stay permanently greyed out on the streaming path.
          try {
            const after = await readConversation(
              "",
              "",
              state.conversationId,
              true,
            );
            if (gen !== generationRef.current) return;
            dispatch({
              type: "SET_UNDO_REDO",
              undoAvailable: after.undoAvailable ?? false,
              redoAvailable: after.redoAvailable ?? false,
            });
            // A stream that fails sends `error` and closes WITHOUT a `done`,
            // so nothing else on this path learns the conversation is now
            // ERROR / EXECUTION_INTERRUPTED — leaving the recovery banner
            // unreachable on the default transport.
            //
            // But this read RACES a Stop: handleStop cancels and then sets
            // EXECUTION_INTERRUPTED, and a refresh issued before that lands
            // afterwards carrying a stale READY, wiping the state Stop just
            // set — and with it the recovery banner. handleStop clears
            // abortRef, so a controller that is no longer current means this
            // turn was stopped and its refresh must not speak for the state.
            const wasStopped = abortRef.current !== abort;
            if (after.conversationState && !wasStopped) {
              dispatch({
                type: "SET_CONVERSATION_STATE",
                state: after.conversationState,
              });
            }
          } catch {
            // Availability refresh is best-effort; a failure here must not
            // sink an otherwise successful turn.
          }
        } else if (environment && agentId && state.conversationId) {
          // Non-streaming path — pass context for secret input
          const snapshot = await sendMessage(
            environment,
            agentId,
            state.conversationId,
            text,
            userId,
            context,
          );
          if (sendGen !== generationRef.current) return;
          dispatch({ type: "SET_THINKING", value: false });
          processSnapshot(snapshot);
          dispatch({ type: "SET_PROCESSING", value: false });
        }
      } catch (err) {
        dispatch({ type: "SET_PROCESSING", value: false });
        dispatch({ type: "SET_THINKING", value: false });
        // This path never reaches FINISH_STREAMING, so clear it here too.
        dispatch({ type: "SET_ESCALATING", value: false });

        if (err instanceof ApiError && err.status === 409) {
          // The turn was refused and NEVER consumed — most often because the
          // conversation is awaiting a human decision. Withdraw the optimistic
          // bubble, hand the text back to the composer, and say why.
          withdrawTurn();
          dispatch({
            type: "ADD_MESSAGE",
            message: makeAgentMessage(
              err.body?.trim()
                ? `⚠️ ${err.body.trim()}`
                : "⚠️ Your message was not sent — this conversation is waiting on a decision.",
            ),
          });
          // Re-read so the paused state (and its card) appears immediately.
          // Gated on conversationId ALONE: readConversation ignores
          // environment/agentId, and the managed route never has them — so
          // requiring them meant the managed route never learned it had been
          // refused, leaving the composer live against a paused conversation.
          if (state.conversationId) {
            try {
              const snap = await readConversation(
                environment ?? "",
                agentId ?? "",
                state.conversationId,
                true,
              );
              if (snap.conversationState) {
                dispatch({
                  type: "SET_CONVERSATION_STATE",
                  state: snap.conversationState,
                });
              }
            } catch {
              // best effort
            }
          }
          return;
        }

        if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
          withdrawTurn();
          dispatch({
            type: "ADD_MESSAGE",
            message: makeAgentMessage(
              "⚠️ You are not allowed to continue this conversation. It may belong to a different user.",
            ),
          });
          return;
        }

        console.error("Failed to send message:", err);
        // Nothing further will withdraw this turn, so release its record.
        pendingTurnsRef.current.delete(turnId);
        // Without this the empty placeholder stays in the transcript flagged
        // isStreaming forever, rendering as a perpetually-typing bubble.
        dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
        dispatch({ type: "FINISH_STREAMING" });
        dispatch({
          type: "ADD_MESSAGE",
          message: makeAgentMessage(
            "⚠️ Your message could not be sent. Please try again.",
          ),
        });
      } finally {
        // Release this turn's record. Only the failure paths deleted it, so a
        // long session accumulated one entry (text + attachments) per
        // successful turn, forever.
        pendingTurnsRef.current.delete(turnId);
      }
    },
    [
      dispatch,
      processSnapshot,
      handleSSEEvent,
      isDemo,
      isManagedAgent,
      intent,
      userId,
      environment,
      agentId,
      state.conversationId,
      state.config.enableStreaming,
      state.pendingAttachments,
    ],
  );

  /* ─── Undo ──────────────────────────────────── */
  const handleUndo = useCallback(async () => {
    // environment/agentId are unused by the API layer (v6 paths are
    // conversation-scoped) and are undefined on the managed route, where these
    // guards made undo/redo/retry silently inert.
    if (!state.conversationId) return;
    const gen = generationRef.current;
    if (isDemo) return; // Demo mode doesn't support undo

    try {
      dispatch({ type: "SET_PROCESSING", value: true });
      // undo returns 200 with an EMPTY body; re-read the snapshot to rebuild.
      await undoConversation("", "", state.conversationId);
      const snapshot = await readConversation("", "", state.conversationId);

      // Rebuild from the shape the endpoint really returns. An empty result
      // means "could not rebuild", NOT "the conversation is empty" — replacing
      // a populated transcript with [] is how this wiped the whole chat.
      // A New Conversation while this was in flight must not have its
      // transcript replaced by the old conversation's history.
      if (gen !== generationRef.current) return;
      const msgs = stepsToMessages(
        snapshot.conversationSteps,
        secretTextsRef.current,
        snapshot.conversationOutputs,
      );
      if (msgs.length) {
        dispatch({ type: "REPLACE_MESSAGES", messages: msgs });
      }
      dispatch({
        type: "SET_UNDO_REDO",
        undoAvailable: snapshot.undoAvailable ?? false,
        redoAvailable: snapshot.redoAvailable ?? true,
      });
    } catch (err) {
      // 409 is expected while the conversation is paused or a turn is running —
      // undo/redo availability is deliberately NOT pause-aware server-side, so
      // the button can be enabled while the operation is refused.
      dispatch({
        type: "ADD_MESSAGE",
        message: makeAgentMessage(
          err instanceof ApiError && err.status === 409
            ? "⚠️ Undo is not possible right now."
            : "⚠️ Undo failed.",
        ),
      });
    } finally {
      dispatch({ type: "SET_PROCESSING", value: false });
    }
  }, [dispatch, state.conversationId, isDemo]);

  /* ─── Redo ──────────────────────────────────── */
  const handleRedo = useCallback(async () => {
    if (!state.conversationId) return;
    const gen = generationRef.current;
    if (isDemo) return;

    try {
      dispatch({ type: "SET_PROCESSING", value: true });
      // redo returns 200 with an EMPTY body; re-read the snapshot to rebuild.
      await redoConversation("", "", state.conversationId);
      const snapshot = await readConversation("", "", state.conversationId);

      // Rebuild from the shape the endpoint really returns. An empty result
      // means "could not rebuild", NOT "the conversation is empty" — replacing
      // a populated transcript with [] is how this wiped the whole chat.
      // A New Conversation while this was in flight must not have its
      // transcript replaced by the old conversation's history.
      if (gen !== generationRef.current) return;
      const msgs = stepsToMessages(
        snapshot.conversationSteps,
        secretTextsRef.current,
        snapshot.conversationOutputs,
      );
      if (msgs.length) {
        dispatch({ type: "REPLACE_MESSAGES", messages: msgs });
      }
      dispatch({
        type: "SET_UNDO_REDO",
        undoAvailable: snapshot.undoAvailable ?? true,
        redoAvailable: snapshot.redoAvailable ?? false,
      });
    } catch (err) {
      dispatch({
        type: "ADD_MESSAGE",
        message: makeAgentMessage(
          err instanceof ApiError && err.status === 409
            ? "⚠️ Redo is not possible right now."
            : "⚠️ Redo failed.",
        ),
      });
    } finally {
      dispatch({ type: "SET_PROCESSING", value: false });
    }
  }, [dispatch, state.conversationId, isDemo]);

  /* ─── Quick reply handler ───────────────────── */
  const handleQuickReply = useCallback(
    (value: string) => {
      handleSend(value);
    },
    [handleSend],
  );

  /* ─── Restart conversation ──────────────────── */
  const handleRestart = useCallback(async () => {
    // Abandon any in-flight turn first. Without this the old stream kept
    // writing tokens, conversation state and undo/redo flags into the NEW
    // conversation.
    abortRef.current?.abort();
    abortRef.current = null;
    generationRef.current += 1;
    pendingTurnsRef.current.clear();
    dispatch({ type: "CLEAR_MESSAGES" });
    await openConversation(true);
  }, [dispatch, openConversation]);

  /* ─── HITL: watch a paused conversation ─────── */
  const isPaused = isPausedState(state.conversationState);

  const handleApprovalStatus = useCallback(
    (status: ApprovalStatus | null) => {
      dispatch({ type: "SET_APPROVAL_STATUS", status });
    },
    [dispatch],
  );

  const handlePauseResolved = useCallback(async (
    isStale: () => boolean,
  ): Promise<boolean> => {
    // The pause ended — by a reviewer, or automatically by timeout policy.
    // Nothing was pushed to us, so re-read to pick up the resumed turn.
    // Returning false keeps the watch alive: a dropped refresh must not strand
    // the widget in a paused state it can never leave.
    if (!state.conversationId) return false;
    try {
      const snapshot = await readConversation(
        environment ?? "",
        agentId ?? "",
        state.conversationId,
        true,
      );
      // The watch may have been torn down while this read was in flight (a
      // restart, an unmount). Applying the snapshot then would graft the old
      // conversation's transcript onto the new one.
      if (isStale()) return true;
      // Re-reading the step that was already rendered when the turn paused.
      processSnapshot(snapshot, { dedupe: true });
      return true;
    } catch (err) {
      console.error("Failed to refresh after approval:", err);
      return false;
    }
  }, [environment, agentId, state.conversationId, processSnapshot]);

  useHitlPolling({
    conversationId: state.conversationId,
    paused: isPaused && !isDemo,
    onStatus: handleApprovalStatus,
    onResolved: handlePauseResolved,
  });

  const handleCancel = useCallback(async () => {
    if (!state.conversationId) return;
    try {
      dispatch({ type: "SET_PROCESSING", value: true });
      await cancelConversation(state.conversationId);
      dispatch({ type: "SET_APPROVAL_STATUS", status: null });
      dispatch({ type: "SET_CONVERSATION_STATE", state: "EXECUTION_INTERRUPTED" });
      dispatch({
        type: "ADD_MESSAGE",
        message: makeAgentMessage("This request was cancelled."),
      });
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        // Nothing to cancel — it resolved between render and click.
        dispatch({ type: "SET_APPROVAL_STATUS", status: null });
      } else {
        console.error("Cancel failed:", err);
      }
    } finally {
      dispatch({ type: "SET_PROCESSING", value: false });
    }
  }, [dispatch, state.conversationId]);

  /* ─── Recovery from a stuck conversation ────── */
  const isStuck =
    state.conversationState === "ERROR" ||
    state.conversationState === "EXECUTION_INTERRUPTED";

  const handleRetry = useCallback(async () => {
    if (!state.conversationId) return;
    const gen = generationRef.current;
    dispatch({ type: "SET_PROCESSING", value: true });
    try {
      await rerunLastStep(state.conversationId);
      const snapshot = await readConversation("", "", state.conversationId, true);
      if (gen !== generationRef.current) return;
      if (snapshot.conversationState) {
        dispatch({ type: "SET_CONVERSATION_STATE", state: snapshot.conversationState });
      }
      // Retry re-reads the same step that failed; its output is already shown.
      processSnapshot(snapshot, { dedupe: true });
    } catch (err) {
      dispatch({
        type: "ADD_MESSAGE",
        message: makeAgentMessage(
          err instanceof ApiError && err.status === 409
            ? "⚠️ There is nothing to retry right now."
            : "⚠️ Retrying failed. You can start a new conversation instead.",
        ),
      });
    } finally {
      dispatch({ type: "SET_PROCESSING", value: false });
    }
  }, [dispatch, state.conversationId, processSnapshot]);

  /* ─── Stop generating ───────────────────────── */
  const handleStop = useCallback(async () => {
    // Abort the local read first so tokens stop arriving immediately, then ask
    // the server to stop producing them.
    abortRef.current?.abort();
    abortRef.current = null;
    // Stopping before the first token left an empty bubble rendering
    // "No response", implying the agent answered with nothing.
    dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
    dispatch({ type: "FINISH_STREAMING" });
    dispatch({ type: "SET_PROCESSING", value: false });
    dispatch({ type: "SET_THINKING", value: false });

    if (!state.conversationId || isDemo) return;
    try {
      await cancelConversation(state.conversationId);
      dispatch({ type: "SET_CONVERSATION_STATE", state: "EXECUTION_INTERRUPTED" });
    } catch (err) {
      // 409 = nothing to cancel; the turn finished as we clicked.
      if (!(err instanceof ApiError && err.status === 409)) {
        console.error("Stop failed:", err);
      }
    }
  }, [dispatch, state.conversationId, isDemo]);

  /* ─── Auto-scroll ───────────────────────────── */
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [state.messages, state.isProcessing]);

  const handleScroll = useCallback(() => {
    const el = messagesContainerRef.current;
    if (!el) return;
    const atBottom =
      el.scrollHeight - el.scrollTop - el.clientHeight <= 20;
    setShowScrollBtn(!atBottom);
  }, []);

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, []);

  /* ─── Derived state ─────────────────────────── */
  const isEnded = state.conversationState === "ENDED";
  const showInput =
    !isEnded && (state.quickReplies.length === 0 || state.messages.length > 0);

  return (
    <div className="chat-root">
      <ChatHeader />

      <div
        className="chat-messages"
        ref={messagesContainerRef}
        onScroll={handleScroll}
        // Without a live region no reply was ever announced to a screen
        // reader. aria-busy holds the announcement until a streamed reply is
        // complete, instead of reading it out token by token.
        role="log"
        aria-live="polite"
        aria-label="Conversation"
        aria-busy={state.isProcessing}
        data-testid="chat-transcript"
      >
        {state.messages.length === 0 && !state.isProcessing && !isPaused ? (
          <div className="chat-empty">
            <div className="chat-empty__icon">💬</div>
            <p className="chat-empty__text">
              Starting conversation…
            </p>
          </div>
        ) : (
          <>
            {state.messages.map((msg) => (
              <MessageBubble
                key={msg.id}
                message={msg}
                enableMarkdown={state.config.enableMarkdown !== false}
                enableMath={state.config.enableMath !== false}
                enableCodeHighlight={state.config.enableCodeHighlight !== false}
              />
            ))}

            {isPaused && state.approvalStatus ? (
              <PausedCard
                status={state.approvalStatus}
                onCancel={handleCancel}
                cancelDisabled={state.isProcessing}
              />
            ) : (
              <>
                {(state.isThinking || state.isEscalating) && (
                  <ThinkingIndicator escalating={state.isEscalating} />
                )}
                {state.isProcessing && !state.isThinking && !state.isEscalating && (
                  <TypingIndicator />
                )}
              </>
            )}

            <div ref={messagesEndRef} />
          </>
        )}
      </div>

      <div style={{ position: "relative" }}>
        <ScrollToBottom visible={showScrollBtn} onClick={scrollToBottom} />
      </div>

      {!isEnded && !isPaused &&
        state.config.enableQuickReplies !== false && (
          <QuickReplies
            replies={state.quickReplies}
            onSelect={handleQuickReply}
          />
        )}

      {isStuck && (
        <div className="recovery-banner" role="status" data-testid="recovery-banner">
          <span className="recovery-banner__text">
            {state.conversationState === "EXECUTION_INTERRUPTED"
              ? "This request was interrupted before it finished."
              : "Something went wrong on the last step."}
          </span>
          <div className="recovery-banner__actions">
            <button
              className="recovery-banner__btn"
              onClick={handleRetry}
              disabled={state.isProcessing}
              data-testid="recovery-retry"
            >
              Try again
            </button>
            <button
              className="recovery-banner__btn"
              onClick={handleRestart}
              disabled={state.isProcessing}
              data-testid="recovery-restart"
            >
              Start over
            </button>
          </div>
        </div>
      )}

      {isEnded ? (
        <div className="chat-ended">
          <span className="chat-ended__label">Conversation Ended</span>
          <button className="chat-ended__restart" onClick={handleRestart}>
            Start New Conversation
          </button>
        </div>
      ) : (
        showInput && (
          <div className="chat-input-area">
            {/* Action bar — undo / redo / restart near the input */}
            {state.conversationId && (
              <div className="chat-actions">
                <div className="chat-actions__left">
                  {state.config.enableUndo !== false && (
                    <button
                      className="chat-actions__btn"
                      onClick={handleUndo}
                      disabled={!state.undoAvailable || state.isProcessing}
                      title="Undo last message"
                      data-testid="undo-btn"
                      style={{ opacity: state.undoAvailable && !state.isProcessing ? 1 : 0.35 }}
                    >
                      ↩
                    </button>
                  )}
                  {state.config.enableRedo !== false && (
                    <button
                      className="chat-actions__btn"
                      onClick={handleRedo}
                      disabled={!state.redoAvailable || state.isProcessing}
                      title="Redo message"
                      data-testid="redo-btn"
                      style={{ opacity: state.redoAvailable && !state.isProcessing ? 1 : 0.35 }}
                    >
                      ↪
                    </button>
                  )}
                </div>
                <div className="chat-actions__right">
                  {state.isProcessing && !isPaused && (
                    <button
                      className="chat-actions__btn chat-actions__btn--stop"
                      onClick={handleStop}
                      title="Stop generating"
                      aria-label="Stop generating"
                      data-testid="chat-stop"
                    >
                      ■
                    </button>
                  )}
                  {state.config.enableNewConversation !== false && (
                    <button
                      className="chat-actions__btn"
                      onClick={handleRestart}
                      title="New conversation"
                      data-testid="restart-btn"
                    >
                      ↻
                    </button>
                  )}
                </div>
              </div>
            )}

            {state.activeInputField ? (
              <SecretInput
                label={state.activeInputField.label}
                placeholder={state.activeInputField.placeholder}
                defaultValue={state.activeInputField.defaultValue}
                subType={state.activeInputField.subType}
                onSend={handleSend}
                disabled={state.isProcessing}
              />
            ) : (
              <ChatInput
                onSend={handleSend}
                disabled={(!state.conversationId && !isManagedAgent) || isPaused}
                conversationId={state.conversationId}
              />
            )}
          </div>
        )
      )}
    </div>
  );
}
