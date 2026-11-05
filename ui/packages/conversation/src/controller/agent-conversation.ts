/* ──────────────────────────────────────────────
   AgentConversation — the 1:1 turn state machine

   One conversation with one agent: open it, send turns (streamed or not),
   undo, redo, rerun, stop, cancel a paused turn, restart, and pick the
   conversation back up after a pause resolves. Framework-free: a plain store
   with `subscribe` and `getSnapshot`, read in React through
   `useSyncExternalStore` (`useAgentConversation`), testable without React.

   Moved out of the Chat UI's ChatWidget in phase 3b of the frontend
   unification plan; every guard below is a bug that was once shipped. The
   protocol rules it relies on (six states, ten events, the 409 that restores
   the draft, dropped vs paused turns, `inputField` on `done`) are pinned in
   @eddi/core's README and tests.
   ────────────────────────────────────────────── */

import {
  ApiError,
  buildAttachmentContext,
  extractOutputImages,
  extractOutputTexts,
  findInputField,
  isSkippedTurn,
  isTurnPaused,
  lastStepIndex,
  outputsCarryStepIndexes,
  parseDoneSnapshot,
  parseErrorEvent,
  parseToolCallName,
  stepsToMessages,
  UNCONSUMED_STREAM_ERROR_CODES,
  END_REASON_AGENT_VERSION_RETIRED,
  type AttachmentResult,
  type ConversationSnapshot,
  type ConversationState,
  type DoneSnapshot,
  type OutputImage,
  type SSEEvent,
  type ApprovalStatus,
  type InputField,
} from "@eddi/core";
import { englishText, type Translate } from "../i18n/text";
import { skippedTurnMessage, startFailureMessage } from "./notices";
import {
  conversationReducer,
  initialConversationState,
  type AgentConversationState,
  type ConversationAction,
  type ConversationMessage,
  type MessageAttachment,
} from "./state";
import type { AgentTransport } from "./transport";

/** What the user's bubble shows for a secret turn. */
export const SECRET_BUBBLE = "●●●●●●●●";

export interface AgentConversationOptions {
  transport: AgentTransport;
  /** The app's translator for the notices the controller writes. English by default. */
  t?: Translate;
  /** Stream turns (the default) or ask for one snapshot per turn. */
  streaming?: boolean;
  /**
   * Whether a turn may be sent at all right now. The Chat UI holds sends until
   * the agent's review notice has had its chance to appear; quick replies and
   * the requested field call `send` directly, so the gate lives here.
   */
  canSend?: () => boolean;
  /** For a start failure's copy: the environment the route named. */
  environment?: string;
  /** A rerun replaced this step's reply: a rating given to the old one no longer applies. */
  onStepRerun?: (stepIndex: number) => void;

  /* ─── Host hooks (phase 3c): how a host with its own store and its own copy
     drives the same turn loop. Every one is optional; without them the
     controller behaves as it does for the Chat UI. ─── */

  /**
   * Keep the state in the host's store instead of inside the controller. The
   * Manager keeps its chat in a Zustand store that a dozen components read; the
   * controller reads and writes through this, and `subscribe` delegates to it.
   */
  store?: ConversationStore;
  /**
   * The host's own conversation generation, when something other than
   * `reset()` replaces the conversation on screen (the Manager's agent picker,
   * its history list). A turn whose generation moved on writes nothing more.
   */
  generation?: () => number;
  /**
   * Let a turn whose conversation was replaced keep reading its stream, for at
   * most this long, so the turn the user sent finishes on the server (closing
   * the stream cancels it there). Without it, a replaced conversation's stream
   * stops being read at once. Nothing a detached turn reads is applied.
   */
  detachedGraceMs?: number;
  /**
   * Re-read the conversation after a streamed turn for undo/redo and a state
   * the stream did not report (the default). A host that takes both from the
   * `done` payload (`onTurnSnapshot`) turns it off.
   */
  refreshAfterStream?: boolean;
  /**
   * How a sent message shows its files: as `📎 name` lines in its text (the
   * default) or as `attachments` on the message, which `Message` renders as chips.
   */
  attachmentDisplay?: "text" | "chips";
  /** The texts of one output entry. @eddi/core's `extractOutputTexts` of its `output` list by default. */
  outputTexts?: (output: Record<string, unknown>) => string[];
  /** Every event of the turn on screen, before the controller applies it (a debug panel). */
  onStreamEvent?: (event: SSEEvent) => void;
  /** A turn starts (as the instance property `onTurnStart`, which the Chat UI sets). */
  onTurnStarting?: () => void;
  /** A turn on screen ended, however it ended. */
  onTurnEnd?: () => void;
  /** The snapshot a finished turn answered with: the `done` payload, or the non-streamed reply. */
  onTurnSnapshot?: (snapshot: Partial<ConversationSnapshot>) => void;
  /**
   * Why a turn that finished on the server failed (an ERROR turn's task errors),
   * shown with its reply. Null when it did not fail.
   */
  turnFailure?: (snapshot: Partial<ConversationSnapshot>) => string | null;
  /**
   * Show a refused or failed turn the host's way. Return true when the host
   * presented it (and withdrew the bubble, if it wanted to); the controller then
   * only ends the turn. Return false for the controller's own notices.
   */
  presentFailure?: (failure: TurnFailure, context: TurnFailureContext) => boolean | Promise<boolean>;
}

/** A store the controller keeps its state in, when the host owns it. */
export interface ConversationStore {
  get(): AgentConversationState;
  set(next: AgentConversationState): void;
  subscribe(listener: () => void): () => void;
}

/** A turn that did not end with a reply. */
export interface TurnFailure {
  /**
   * `refused`: the server turned the turn down before it ran (a 4xx, or a
   * stream error code for an unconsumed turn). `failed`: the request failed (a
   * dropped connection, a 5xx). `streamError`: the stream reported an error
   * while the turn ran.
   */
  kind: "refused" | "failed" | "streamError";
  /** The HTTP status, when the failure was one. */
  status: number | null;
  /** The stream error code, when the backend sent one. */
  code: string | null;
  /** The server's own sentence, or the error's message. */
  message: string;
  /** The error that was thrown, if any. */
  error?: unknown;
  /** Part of the reply had already arrived. */
  partial: boolean;
}

export interface TurnFailureContext {
  /** The conversation the turn was sent to. */
  conversationId: string | null;
  /** The id of the turn's user message. */
  userMessageId: string;
  /** What the user sent, unmasked. */
  text: string;
  isSecret: boolean;
  /** The input field the turn answered (cleared when it was sent). */
  answeredField: InputField | null;
  /** Withdraw the turn's bubble and hand its input back to the composer. */
  withdraw(): void;
  /** Whether the conversation the turn belongs to is still on screen. */
  isCurrent(): boolean;
}

/** How `send` ended. `failed` and `refused` carry what happened. */
export type TurnResult =
  | { outcome: "sent" | "ignored" }
  | { outcome: "refused" | "failed"; failure: TurnFailure };

/** A file sent with a turn: what the context names, and what the bubble shows. */
export interface TurnAttachment {
  storageRef: string;
  fileName: string;
  mimeType?: string;
  sizeBytes?: number;
  forwardableInline?: boolean;
  /** A local preview (an object URL) for an image. */
  previewUrl?: string;
}

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
  /**
   * This turn's own `done` said AWAITING_HUMAN. A read right after it can still
   * see the previous stored state (READY), because a backend may announce the
   * pause before persisting it; such a read must not take the pause back.
   */
  announcedPause: boolean;
  /**
   * The stream ended with a `done` whose payload could not be parsed. The turn
   * finished on the server, but its outputs, quick replies and state are not
   * known yet: the caller re-reads the conversation and applies that instead.
   */
  doneUnreadable: boolean;
  /** An error the stream reported, kept for the host to present once the stream ends. */
  failure: TurnFailure | null;
}

function newTurn(stateBeforeSend: ConversationState | null, userMessageId: string | null = null): TurnContext {
  return { tokenCount: 0, stateBeforeSend, userMessageId, announcedPause: false, doneUnreadable: false, failure: null };
}

function uid(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random()}`;
}

export function makeAgentMessage(content: string, images?: OutputImage[], stepIndex?: number): ConversationMessage {
  return {
    id: uid("agent"),
    role: "agent",
    content,
    timestamp: Date.now(),
    ...(images?.length ? { images } : {}),
    ...(stepIndex !== undefined ? { stepIndex } : {}),
  };
}

/**
 * Text the client itself wrote — a refused send, a failed undo — as opposed to
 * something the agent said. It renders as a system notice, never as an agent
 * bubble, so a reader cannot take "Undo failed" for the agent speaking.
 */
export function makeNotice(content: string): ConversationMessage {
  return { id: uid("notice"), role: "agent", kind: "notice", content, timestamp: Date.now() };
}

function streamingPlaceholder(): ConversationMessage {
  return { id: uid("agent"), role: "agent", content: "", timestamp: Date.now(), isStreaming: true };
}

function isAbortError(e: unknown): boolean {
  return e instanceof DOMException && e.name === "AbortError";
}

interface PendingTurn {
  text: string;
  /** Files to put back into the composer's staging; files the host passed in stay the host's. */
  attachments: AttachmentResult[];
  isSecret: boolean;
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

function toMessageAttachment(a: TurnAttachment): MessageAttachment {
  return {
    fileName: a.fileName,
    ...(a.mimeType !== undefined ? { mimeType: a.mimeType } : {}),
    ...(a.sizeBytes !== undefined ? { sizeBytes: a.sizeBytes } : {}),
    ...(a.previewUrl !== undefined ? { previewUrl: a.previewUrl } : {}),
    ...(a.forwardableInline !== undefined ? { forwardableInline: a.forwardableInline } : {}),
  };
}

type Listener = () => void;

export class AgentConversation {
  private localState: AgentConversationState;
  private readonly listeners = new Set<Listener>();
  private options: AgentConversationOptions;
  /**
   * Bumped whenever the conversation is switched. Async continuations capture
   * it and bail if it moved, so an abandoned stream cannot write into the
   * conversation that replaced it.
   */
  private generation = 0;
  /** Controller for the in-flight turn, so Stop and New conversation can end it. */
  private abort: AbortController | null = null;
  /**
   * Raw texts sent this session with secret mode on. The backend scrubs a
   * secret turn when it ends and masks older stored turns on read, so after a
   * reload the turn output's `input` and `input:initial` are what mask it
   * (stepsToMessages). This set remains a fallback for a backend that sends
   * neither.
   */
  private readonly secretTexts = new Set<string>();
  /**
   * Raw input of in-flight turns, keyed by the user message's id. A single
   * slot let a late-failing turn withdraw a newer, unrelated message.
   */
  private readonly pendingTurns = new Map<string, PendingTurn>();

  constructor(options: AgentConversationOptions, initial: AgentConversationState = initialConversationState) {
    this.options = options;
    this.localState = initial;
  }

  /* ─── Store ─────────────────────────────────── */

  /** The state, from the host's store when it keeps one. */
  private get state(): AgentConversationState {
    return this.options.store ? this.options.store.get() : this.localState;
  }

  subscribe = (listener: Listener): (() => void) => {
    if (this.options.store) return this.options.store.subscribe(listener);
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getSnapshot = (): AgentConversationState => this.state;

  /** Apply one action — the composer's own (attachments, secret mode) come in here too. */
  dispatch = (action: ConversationAction): void => {
    const previous = this.state;
    const next = conversationReducer(previous, action);
    if (next === previous) return;
    if (this.options.store) {
      this.options.store.set(next);
      return;
    }
    this.localState = next;
    for (const listener of [...this.listeners]) listener();
  };

  /** Change options in place: the route, the streaming toggle, the translator. */
  setOptions(options: Partial<AgentConversationOptions>): void {
    this.options = { ...this.options, ...options };
  }

  /** Whether a continuation started at `gen` still belongs to the conversation on screen. */
  isCurrent(gen: number): boolean {
    return gen === this.currentGeneration;
  }

  /** The current generation, for a host's own async continuations (a HITL watch). */
  get currentGeneration(): number {
    return this.options.generation ? this.options.generation() : this.generation;
  }

  /** The texts of one output entry (see `outputTexts`). */
  private textsOf(output: unknown): string[] {
    if (!output || typeof output !== "object") return [];
    if (this.options.outputTexts) return this.options.outputTexts(output as Record<string, unknown>);
    return extractOutputTexts((output as { output?: unknown }).output);
  }

  private get t(): Translate {
    return this.options.t ?? englishText;
  }

  private get transport(): AgentTransport {
    return this.options.transport;
  }

  /* ─── Snapshots ─────────────────────────────── */

  /**
   * Apply a conversation read.
   *
   * @param dedupe Suppress texts already rendered from an identical snapshot
   *   slot. ONLY for reads that deliberately revisit a step already shown —
   *   retry and the post-approval refresh. It must stay off for ordinary
   *   sends: `returnCurrentStepOnly=true` pins every response to one output at
   *   index 0, so the key degenerates to the reply text and a repeated
   *   utterance (a fallback, a re-prompt) would be silently swallowed.
   * @param fullHistory The read lists every step (an open or a resume), so
   *   output positions are the server's step indexes.
   */
  applySnapshot(
    snapshot: Partial<ConversationSnapshot>,
    { dedupe = false, fullHistory = false }: { dedupe?: boolean; fullHistory?: boolean } = {},
  ): void {
    const dispatch = this.dispatch;
    // Managed mode never calls start, so without this the client has no
    // conversationId — disabling HITL polling, cancel, retry and attachments
    // for the entire managed route.
    if (snapshot.conversationId) dispatch({ type: "SET_CONVERSATION_ID", id: snapshot.conversationId });
    if (snapshot.conversationState) {
      dispatch({
        type: "SET_CONVERSATION_STATE",
        state: snapshot.conversationState,
        endReason: snapshot.endReason ?? null,
      });
    }

    dispatch({
      type: "SET_UNDO_REDO",
      undoAvailable: snapshot.undoAvailable ?? false,
      redoAvailable: snapshot.redoAvailable ?? false,
    });

    // On a full read the outputs' positions are the server's step indexes —
    // but only when they agree with conversationSteps. When the lists
    // disagree, the transcript is rebuilt from the steps instead (below), which
    // carry the person's turns and the real indexes: output-only agent messages
    // left unstamped would all count as step 0 and key every reply rating to
    // the greeting.
    const outputsList = snapshot.conversationOutputs;
    const useOutputs =
      !!outputsList?.length &&
      (!fullHistory ||
        !snapshot.conversationSteps?.length ||
        outputsCarryStepIndexes(outputsList, snapshot.conversationSteps));
    if (useOutputs && outputsList) {
      outputsList.forEach((output, index, outputs) => {
        const agentReplies: unknown[] = output.output ?? [];
        // inputField items configure the composer rather than the transcript,
        // and only the LATEST turn's request is still open: a full-snapshot
        // refresh (HITL resume) would otherwise re-raise a password prompt from
        // an earlier turn that was already answered.
        if (index === outputs.length - 1) {
          const field = findInputField(agentReplies);
          if (field) dispatch({ type: "SET_INPUT_FIELD", field });
        }
        // Handles bare-string entries too — HITL's pending-approval placeholder
        // and reviewer-rejection message arrive as raw strings.
        for (const text of this.textsOf(output)) {
          // A full read lists every step, so the position is the server's step
          // index (reply ratings are keyed by it). A turn response carries only
          // its own step, at index 0, and is counted instead.
          dispatch({
            type: dedupe ? "ADD_SNAPSHOT_MESSAGE" : "ADD_MESSAGE",
            message: makeAgentMessage(text, undefined, fullHistory ? index : undefined),
          });
        }
        const images = extractOutputImages(agentReplies);
        if (images.length && !dedupe) {
          dispatch({ type: "ADD_MESSAGE", message: makeAgentMessage("", images, fullHistory ? index : undefined) });
        }
      });
      // Quick replies from the last output (most recent step) — on a resume
      // too: the buttons the agent offered last are still the open question.
      const lastOutput = outputsList[outputsList.length - 1];
      dispatch({ type: "SET_QUICK_REPLIES", replies: lastOutput?.quickReplies ?? [] });
    }
    // conversationSteps is a FALLBACK, not an additional source. The backend
    // populates BOTH lists from the same memory for every response and only
    // nulls one out when the caller passes returningFields, which this client
    // never sends. Rendering both showed every reply twice — and echoed the
    // user's `input:initial`, which is the raw text even for a secret turn.
    else if (snapshot.conversationSteps?.length) {
      for (const message of stepsToMessages(
        snapshot.conversationSteps,
        this.secretTexts,
        snapshot.conversationOutputs,
        fullHistory,
      )) {
        dispatch({ type: dedupe ? "ADD_SNAPSHOT_MESSAGE" : "ADD_MESSAGE", message });
      }
      // Rebuilt from the steps although outputs exist: the latest output still
      // carries the open input-field request and the quick replies.
      const lastOutput = outputsList?.length ? outputsList[outputsList.length - 1] : null;
      if (lastOutput) {
        const field = findInputField(lastOutput.output ?? []);
        if (field) dispatch({ type: "SET_INPUT_FIELD", field });
        dispatch({ type: "SET_QUICK_REPLIES", replies: lastOutput.quickReplies ?? [] });
      }
    }
  }

  /**
   * Rebuild the view after an undo or redo from the re-read conversation —
   * transcript, undo/redo availability, and what the composer is offering
   * (quick replies, a requested input field). The last two used to be left as
   * the undone turn had set them, so Undo kept showing the buttons of a step
   * that no longer existed.
   */
  private applyHistorySnapshot(
    snapshot: ConversationSnapshot,
    fallback: { undoAvailable: boolean; redoAvailable: boolean },
  ): void {
    const dispatch = this.dispatch;
    // An empty result usually means "could not rebuild", NOT "the conversation
    // is empty" — replacing a populated transcript with [] is how this wiped
    // the whole chat. Only an explicitly empty step list, with no outputs, is
    // empty.
    const msgs = stepsToMessages(snapshot.conversationSteps, this.secretTexts, snapshot.conversationOutputs, true);
    if (msgs.length) {
      dispatch({ type: "REPLACE_MESSAGES", messages: msgs });
    } else if (
      Array.isArray(snapshot.conversationSteps) &&
      snapshot.conversationSteps.length === 0 &&
      !snapshot.conversationOutputs?.length
    ) {
      dispatch({ type: "REPLACE_MESSAGES", messages: [] });
    }
    dispatch({
      type: "SET_UNDO_REDO",
      undoAvailable: snapshot.undoAvailable ?? fallback.undoAvailable,
      redoAvailable: snapshot.redoAvailable ?? fallback.redoAvailable,
    });
    const outputs = snapshot.conversationOutputs;
    const last = outputs?.length ? outputs[outputs.length - 1] : undefined;
    dispatch({ type: "SET_QUICK_REPLIES", replies: last?.quickReplies ?? [] });
    const field = findInputField(last?.output);
    if (field) dispatch({ type: "SET_INPUT_FIELD", field });
    else dispatch({ type: "CLEAR_INPUT_FIELD" });
  }

  /* ─── Finishing a turn ──────────────────────── */

  /** Withdraw a refused turn's bubble and hand its text (and files) back to the composer. */
  private withdraw(turnId: string | null): void {
    if (!turnId) {
      this.dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
      return;
    }
    const pending = this.pendingTurns.get(turnId);
    this.pendingTurns.delete(turnId);
    this.dispatch({
      type: "WITHDRAW_LAST_USER_MESSAGE",
      messageId: turnId,
      draft: pending?.text,
      attachments: pending?.attachments,
      wasSecret: pending?.isSecret,
    });
  }

  /**
   * Apply a finished turn: the `done` payload, or — when that was unreadable —
   * the conversation re-read after the stream, which carries the same fields.
   */
  private applyDone(snapshot: DoneSnapshot | null, turn: TurnContext): void {
    const dispatch = this.dispatch;
    const outputs = snapshot?.conversationOutputs;
    const lastOutput = outputs?.length ? outputs[outputs.length - 1] : undefined;
    const outputText = this.textsOf(lastOutput).join("\n\n");
    const outputImages = extractOutputImages(lastOutput?.output);
    const skipped = isSkippedTurn(snapshot, turn.tokenCount, turn.stateBeforeSend);
    // Why the turn failed on the server, shown with whatever it said (a host's
    // copy; the Chat UI has none). Never for a skipped turn: its payload is the
    // previous step's.
    const failure = skipped || !snapshot ? null : (this.options.turnFailure?.(snapshot) ?? null);
    const failureLine = failure ? `⚠️ ${failure}` : "";
    if (snapshot && !skipped) this.options.onTurnSnapshot?.(snapshot);

    // A requested input field (a password prompt) arrives here on the streaming
    // path, which is the default. Reading it only from the non-streaming
    // snapshot meant the key went into the plain textarea, was shown in clear
    // and was sent without `secretInput`. A skipped turn carries the PREVIOUS
    // step's outputs, so it must not re-apply.
    const inputField = skipped ? null : findInputField(lastOutput?.output);
    if (inputField) dispatch({ type: "SET_INPUT_FIELD", field: inputField });

    if (skipped) {
      // The server dropped this turn without consuming it. The payload carries
      // the PREVIOUS step's outputs, so its quick replies must not be applied.
      // The user's own bubble goes too: leaving it asserts the message was
      // sent when it never reached the agent; withdrawing it hands the text
      // back to the composer.
      this.withdraw(turn.userMessageId);
      dispatch({ type: "ADD_MESSAGE", message: makeNotice(skippedTurnMessage(snapshot?.conversationState, this.t)) });
    } else if (turn.tokenCount === 0) {
      // No tokens, but the turn WAS accepted. Any text lives only in the done
      // payload — including HITL's pending-approval placeholder, which arrives
      // as a bare string. Dropping it left a paused turn rendering "No response".
      // A blocked or rewritten answer often arrives with no token at all: the
      // guardrail notice that explains it rides on the placeholder, and must
      // survive the placeholder's replacement.
      const placeholder = this.state.messages.find((m) => m.role === "agent" && m.isStreaming && m.content === "");
      const guardrails = placeholder?.guardrails;
      dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
      // A failed turn's reason goes into its reply rather than beside it, so
      // it does not leave an empty bubble above its explanation.
      const content = [outputText, failureLine].filter(Boolean).join("\n\n");
      if (content || outputImages.length) {
        dispatch({
          type: "ADD_MESSAGE",
          message: { ...makeAgentMessage(content, outputImages), ...(guardrails ? { guardrails } : {}) },
        });
      }
      if (!isTurnPaused(snapshot, turn.stateBeforeSend)) {
        dispatch({ type: "SET_QUICK_REPLIES", replies: lastOutput?.quickReplies ?? [] });
      }
    } else if (outputs?.length) {
      // Tokens already rendered the answer, but responseValidation can
      // SUPERSEDE them (fallback text) after the fact. The snapshot is
      // authoritative.
      if (outputText) dispatch({ type: "RECONCILE_LAST_AGENT", content: outputText });
      // After the reconcile, which would take an appended reason with it.
      if (failureLine) dispatch({ type: "APPEND_TO_LAST_AGENT", token: `\n\n${failureLine}` });
      // Images never stream as tokens; the snapshot is their only source.
      if (outputImages.length) dispatch({ type: "ADD_MESSAGE", message: makeAgentMessage("", outputImages) });
      dispatch({ type: "SET_QUICK_REPLIES", replies: lastOutput?.quickReplies ?? [] });
    }

    if (snapshot?.conversationState) {
      // Remembered for the post-stream re-read, which may still see READY.
      if (snapshot.conversationState === "AWAITING_HUMAN") turn.announcedPause = true;
      dispatch({ type: "SET_CONVERSATION_STATE", state: snapshot.conversationState });
    }

    dispatch({ type: "FINISH_STREAMING" });
    dispatch({ type: "SET_PROCESSING", value: false });
  }

  /**
   * Apply one stream event. Returns `true` when the stream is logically
   * complete (done / error), so the caller can stop reading.
   */
  private handleEvent(event: SSEEvent, turn: TurnContext): boolean {
    const dispatch = this.dispatch;
    switch (event.type) {
      case "token":
        turn.tokenCount += 1;
        dispatch({ type: "SET_THINKING", value: false });
        // Text is flowing, so whichever model won the cascade is answering.
        dispatch({ type: "SET_ESCALATING", value: false });
        // No event says a tool finished; resumed output is that signal.
        dispatch({ type: "SET_ACTIVE_TOOL", tool: null });
        dispatch({ type: "APPEND_TO_LAST_AGENT", token: event.data });
        return false;

      // Pipeline progress. The backend emits no "thinking" event, so these are
      // what actually tell us the agent is working before any text.
      case "task_start":
        if (turn.tokenCount === 0) dispatch({ type: "SET_THINKING", value: true });
        return false;

      case "task_complete":
        return false;

      case "task_failed":
        // The turn may still recover (cascade escalation, retry), so done/error
        // decides the outcome — but stop implying the agent is composing.
        dispatch({ type: "SET_THINKING", value: false });
        dispatch({ type: "SET_ACTIVE_TOOL", tool: null });
        return false;

      // Emitted right before each tool runs — the name only. Deliberately NOT
      // guarded on tokenCount: a model may write a sentence and then call a
      // tool, and the silence while that tool runs is the wait worth
      // explaining. The next token clears it.
      case "tool_call": {
        const tool = parseToolCallName(event.data);
        if (tool) dispatch({ type: "SET_ACTIVE_TOOL", tool });
        return false;
      }

      // Step starts are pure observability — task_start already raised the
      // thinking indicator, guarded on tokenCount so it cannot come back after
      // text has begun.
      case "cascade_step_start":
        return false;

      // A cheaper model was abandoned mid-turn. The wait that follows is
      // silent — a buffered cascade emits its whole answer as one token — so
      // say something rather than leave a bare spinner.
      case "cascade_escalation":
        dispatch({ type: "SET_ESCALATING", value: true });
        return false;

      // Why the reply's text is what it is (a block message, a rewrite). The
      // payload is `{type, phase, action, taskId}` and never the screened text.
      case "guardrail_triggered": {
        try {
          const parsed = JSON.parse(event.data) as Record<string, unknown>;
          if (typeof parsed.type === "string" && parsed.type) {
            dispatch({
              type: "ADD_GUARDRAIL",
              notice: {
                type: parsed.type,
                phase: typeof parsed.phase === "string" ? parsed.phase : "",
                action: typeof parsed.action === "string" ? parsed.action : "",
              },
            });
          }
        } catch {
          // Malformed payload — nothing to show.
        }
        return false;
      }

      case "done": {
        const snapshot = parseDoneSnapshot(event.data);
        if (!snapshot) {
          // The turn finished on the server, but its payload is unreadable (a
          // proxy truncated it, say). Stop reading; the caller re-reads the
          // conversation and applies that instead.
          turn.doneUnreadable = true;
          return true;
        }
        this.applyDone(snapshot, turn);
        return true;
      }

      case "error": {
        // Payload is {"message":"…","code":"…"}, not a bare string.
        const { message, code } = parseErrorEvent(event.data);
        const unconsumed = turn.tokenCount === 0 && code !== null && UNCONSUMED_STREAM_ERROR_CODES.has(code);
        if (this.options.presentFailure) {
          // The host shows it, once the stream has ended (it may want a read).
          turn.failure = {
            kind: unconsumed ? "refused" : "streamError",
            status: null,
            code,
            message,
            partial: turn.tokenCount > 0,
          };
          return true;
        }
        if (unconsumed) {
          // Refused before it ran — the streaming twin of a 409. Withdraw the
          // optimistic bubble and hand the draft (a secret included, masked)
          // back. The refresh after the stream picks up the paused state.
          this.withdraw(turn.userMessageId);
          dispatch({
            type: "ADD_MESSAGE",
            message: makeNotice(
              code === "awaiting_approval"
                ? this.t("conversation.send.awaiting")
                : this.t("conversation.send.notSent", { message }),
            ),
          });
          dispatch({ type: "FINISH_STREAMING" });
          dispatch({ type: "SET_PROCESSING", value: false });
          return true;
        }
        if (turn.tokenCount === 0) {
          dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
          dispatch({ type: "ADD_MESSAGE", message: makeNotice(`⚠️ ${message}`) });
        } else {
          dispatch({ type: "APPEND_TO_LAST_AGENT", token: `\n\n⚠️ ${message}` });
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
  }

  /* ─── Open ──────────────────────────────────── */

  /**
   * Open a conversation: the one the route names on first load, a fresh one on
   * restart. Every await re-checks the generation: "New conversation" clicked
   * while a welcome read was in flight used to get the OLD conversation's
   * greeting grafted onto the new one.
   */
  async open(fresh: boolean): Promise<void> {
    const gen = this.currentGeneration;
    const dispatch = this.dispatch;
    const transport = this.transport;
    dispatch({ type: "SET_START_FAILED", value: false });
    try {
      if (transport.mode === "local" && transport.welcome) {
        const result = await transport.welcome();
        if (!this.isCurrent(gen)) return;
        dispatch({ type: "SET_CONVERSATION_ID", id: result.conversationId });
        dispatch({ type: "ADD_MESSAGE", message: result.message });
        dispatch({ type: "SET_QUICK_REPLIES", replies: result.quickReplies });
        dispatch({ type: "SET_CONVERSATION_STATE", state: "READY" });
      } else if (transport.mode === "managed" && transport.openManaged) {
        const snapshot = await transport.openManaged(fresh);
        if (!this.isCurrent(gen)) return;
        // The route names an intent; the snapshot names the agent, whose review
        // notice must be shown like any other's.
        dispatch({ type: "SET_OPENED_AGENT", agentId: snapshot.agentId || null });
        this.applySnapshot(snapshot, { fullHistory: true });
        this.onOpened?.(snapshot, gen);
      } else if (transport.mode === "conversation" && transport.start) {
        const conversationId = await transport.start();
        if (!this.isCurrent(gen)) return;
        dispatch({ type: "SET_CONVERSATION_ID", id: conversationId });
        // Pick up the welcome message.
        const snapshot = await transport.read(conversationId);
        if (!this.isCurrent(gen)) return;
        this.applySnapshot(snapshot, { fullHistory: true });
        this.onOpened?.(snapshot, gen);
      }
    } catch (err) {
      if (!this.isCurrent(gen)) return;
      // A managed conversation that did not load names no agent; waiting on its
      // notice would keep the input closed for good.
      if (transport.mode === "managed" && this.state.openedAgentId === undefined) {
        dispatch({ type: "SET_OPENED_AGENT", agentId: null });
      }
      console.error("Failed to start conversation:", err);
      // A failed start used to reach the console only, leaving the client on
      // "Starting conversation…" for good.
      dispatch({ type: "ADD_MESSAGE", message: makeNotice(startFailureMessage(err, this.t, this.options.environment)) });
      // The composer is closed until a conversation exists; this is the way out.
      dispatch({ type: "SET_START_FAILED", value: true });
    }
  }

  /**
   * Called with the snapshot an open read, for what the host shows from it
   * (the agent's name). Not called for a stale open.
   */
  onOpened?: (snapshot: Partial<ConversationSnapshot>, generation: number) => void;

  /**
   * Resume an existing conversation by id: install its full history — the
   * transcript, undo/redo, the open input-field request, the pause, AND the
   * quick replies the last reply offered, which a resumed conversation used to
   * lose (the Manager showed none until the next turn).
   */
  async resume(conversationId: string): Promise<boolean> {
    const gen = this.currentGeneration;
    try {
      const snapshot = await this.transport.read(conversationId);
      if (!this.isCurrent(gen)) return false;
      this.dispatch({ type: "CLEAR_MESSAGES" });
      this.dispatch({ type: "SET_CONVERSATION_ID", id: conversationId });
      this.applySnapshot(snapshot, { fullHistory: true });
      return true;
    } catch (err) {
      if (!this.isCurrent(gen)) return false;
      console.error("Failed to resume conversation:", err);
      return false;
    }
  }

  /* ─── Send ──────────────────────────────────── */

  /**
   * Send a turn. Quick replies and the requested field call this directly.
   *
   * @param extras.attachments Files the host staged itself (the Manager's
   *   composer), sent instead of the controller's `pendingAttachments`.
   * @returns How the turn ended. The Chat UI ignores it; the Manager's mutation
   *   rejects on `refused` and `failed`.
   */
  async send(text: string, isSecret?: boolean, extras: { attachments?: readonly TurnAttachment[] } = {}): Promise<TurnResult> {
    const dispatch = this.dispatch;
    const t = this.t;
    const transport = this.transport;
    // Re-entrancy guard. Quick replies and the requested field bypass the
    // composer's disabled state, so a click during an in-flight turn used to
    // start a second one — two streams writing into the same transcript.
    if (this.state.isProcessing) return { outcome: "ignored" };
    // Nothing is sent before the host says it may be (the review notice).
    if (this.options.canSend && !this.options.canSend()) return { outcome: "ignored" };

    // Conversation identity for THIS turn. Every async continuation below must
    // re-check it: New Conversation can land while a request is in flight.
    const sendGen = this.currentGeneration;
    const conversationId = this.state.conversationId;
    const answeredField = this.state.activeInputField;

    // Attachments staged in the composer travel with THIS turn as attachment_N
    // context entries — the only path the backend reads. A secret turn never
    // forwards a file: a masked turn must not leak one to the model (the
    // composers block the combination; a field the agent asked for can still
    // bring it about). The staged files stay staged.
    const fromHost = extras.attachments !== undefined;
    const staged: readonly TurnAttachment[] = isSecret ? [] : (extras.attachments ?? this.state.pendingAttachments);
    const attachmentContext = buildAttachmentContext(staged);
    const secretContext = isSecret ? { secretInput: { type: "string" as const, value: "true" } } : undefined;
    const context =
      secretContext || Object.keys(attachmentContext).length > 0
        ? { ...secretContext, ...attachmentContext }
        : undefined;

    // Attachments are named in the transcript so the user can see what was
    // actually sent: as chips on the message, or as text lines. A file above
    // the forward limit was stored but will not be inlined; the model gets a
    // note in its place, so the agent may or may not reach the content — say
    // the part we know is true. The text line does not survive an undo/redo.
    const chips = this.options.attachmentDisplay === "chips";
    const attachmentLine =
      staged.length && !chips
        ? staged
            .map((a) =>
              a.forwardableInline === false ? t("conversation.attach.largeLine", { file: a.fileName }) : `📎 ${a.fileName}`,
            )
            .join("\n")
        : "";
    const displayed = isSecret ? SECRET_BUBBLE : text;
    const userMsg: ConversationMessage = {
      id: uid("user"),
      role: "user",
      content: [attachmentLine, displayed].filter(Boolean).join("\n\n"),
      timestamp: Date.now(),
      ...(chips && staged.length ? { attachments: staged.map(toMessageAttachment) } : {}),
    };
    // Remember the REAL input per turn: the bubble content is display text
    // (masked secrets, "📎 name" lines) and must never be what we hand back.
    const turnId = userMsg.id;
    this.pendingTurns.set(turnId, {
      text,
      attachments: fromHost || isSecret ? [] : this.state.pendingAttachments,
      isSecret: !!isSecret,
    });
    if (isSecret && text.trim()) this.secretTexts.add(text.trim());

    dispatch({ type: "ADD_MESSAGE", message: userMsg });
    if (!fromHost && !isSecret) dispatch({ type: "CLEAR_ATTACHMENTS" });
    // A requested input field lasts for ONE reply. A quick reply answers it too.
    dispatch({ type: "CLEAR_INPUT_FIELD" });
    dispatch({ type: "SET_QUICK_REPLIES", replies: [] });
    dispatch({ type: "SET_PROCESSING", value: true });
    dispatch({ type: "SET_THINKING", value: true });
    // Start every turn un-escalated and tool-less, however the previous one ended.
    dispatch({ type: "SET_ESCALATING", value: false });
    dispatch({ type: "SET_ACTIVE_TOOL", tool: null });

    // This turn's controller. Every transport honours it, so Stop and New
    // conversation actually stop a turn instead of only hiding it.
    const abort = new AbortController();
    this.abort = abort;
    /** True once Stop or New conversation has taken this turn over. */
    const stopped = () => this.abort !== abort;
    const turn = newTurn(this.state.conversationState, turnId);
    const failureContext: TurnFailureContext = {
      conversationId,
      userMessageId: turnId,
      text,
      isSecret: !!isSecret,
      answeredField,
      withdraw: () => this.withdraw(turnId),
      isCurrent: () => this.isCurrent(sendGen),
    };
    this.onTurnStart?.();
    this.options.onTurnStarting?.();
    let result: TurnResult = { outcome: "sent" };

    try {
      if (transport.mode === "local" && transport.stream) {
        dispatch({ type: "ADD_MESSAGE", message: streamingPlaceholder() });
        const localTurn = newTurn(this.state.conversationState);
        let localDone = false;
        for await (const event of transport.stream(null, text, { signal: abort.signal })) {
          if (this.handleEvent(event, localTurn)) localDone = true;
        }
        if (localTurn.doneUnreadable) this.applyDone(null, localTurn);
        // Safety net: finish streaming if the stream closed without a done event.
        if (!localDone) dispatch({ type: "FINISH_STREAMING" });
        dispatch({ type: "SET_QUICK_REPLIES", replies: transport.quickRepliesAfter?.(text) ?? [] });
      } else if (transport.mode === "managed" && transport.say) {
        const snapshot = await transport.say(null, text, { context, signal: abort.signal });
        // Stopped, or the conversation was replaced, while the reply was in
        // flight: it belongs to a turn nobody is waiting for any more.
        if (!this.isCurrent(sendGen) || stopped()) return result;
        dispatch({ type: "SET_THINKING", value: false });
        this.applyTurnSnapshot(snapshot);
        dispatch({ type: "SET_PROCESSING", value: false });
      } else if (this.options.streaming !== false && transport.stream && conversationId) {
        dispatch({ type: "ADD_MESSAGE", message: streamingPlaceholder() });
        result = await this.readStream(transport, conversationId, text, context, abort, turn, sendGen, failureContext);
      } else if (transport.say && conversationId) {
        const snapshot = await transport.say(conversationId, text, { context, signal: abort.signal });
        if (!this.isCurrent(sendGen) || stopped()) return result;
        dispatch({ type: "SET_THINKING", value: false });
        this.applyTurnSnapshot(snapshot);
        dispatch({ type: "SET_PROCESSING", value: false });
      }
    } catch (err) {
      result = await this.handleSendFailure(err, { sendGen, turn, turnId, conversationId, stopped, failureContext });
    } finally {
      // Release this turn's record; only the failure paths deleted it.
      this.pendingTurns.delete(turnId);
      if (this.abort === abort) this.abort = null;
      if (this.isCurrent(sendGen)) this.options.onTurnEnd?.();
    }
    return result;
  }

  /** Apply a turn answered by one snapshot: its reply, then why it failed, if it did. */
  private applyTurnSnapshot(snapshot: Partial<ConversationSnapshot>): void {
    this.applySnapshot(snapshot);
    this.options.onTurnSnapshot?.(snapshot);
    const failure = this.options.turnFailure?.(snapshot);
    if (failure) this.dispatch({ type: "ADD_MESSAGE", message: makeAgentMessage(`⚠️ ${failure}`) });
  }

  /** Read one streamed turn to its end, then the conversation once more (see `refreshAfterStream`). */
  private async readStream(
    transport: AgentTransport,
    conversationId: string,
    text: string,
    context: Record<string, { type: "string" | "object"; value: unknown }> | undefined,
    abort: AbortController,
    turn: TurnContext,
    sendGen: number,
    failureContext: TurnFailureContext,
  ): Promise<TurnResult> {
    const dispatch = this.dispatch;
    const t = this.t;
    const stopped = () => this.abort !== abort;
    const stream = transport.stream;
    if (!stream) return { outcome: "sent" };
    // A replaced conversation's stream: stop reading it at once, or — with a
    // grace period — drain it so the turn finishes on the server, and abort it
    // when the grace runs out. A subscription rather than a check per event,
    // because the case being bounded is exactly the one where no event comes.
    const grace = this.options.detachedGraceMs;
    let detachTimer: ReturnType<typeof setTimeout> | undefined;
    const unsubscribe =
      grace !== undefined
        ? this.subscribe(() => {
            if (!this.isCurrent(sendGen) && detachTimer === undefined) {
              detachTimer = setTimeout(() => abort.abort(), grace);
            }
          })
        : null;
    // The controller also breaks the for-await when "done" fires: proxies
    // (Vite dev, nginx) may not forward the SSE close, so the read would
    // hang forever without it.
    let streamDone = false;
    try {
      try {
        for await (const event of stream(conversationId, text, { context, signal: abort.signal })) {
          // New Conversation mid-stream: stop writing into its replacement.
          if (!this.isCurrent(sendGen)) {
            if (grace === undefined) break;
            if (event.type === "done" || event.type === "error") {
              abort.abort();
              break;
            }
            continue;
          }
          this.options.onStreamEvent?.(event);
          if (this.handleEvent(event, turn)) {
            streamDone = true;
            abort.abort();
            break;
          }
        }
      } catch (e) {
        if (!isAbortError(e)) throw e;
      }
    } finally {
      unsubscribe?.();
      if (detachTimer !== undefined) clearTimeout(detachTimer);
    }
    // Past this point everything belongs to the turn just read; a swapped
    // conversation abandons it, safety net included.
    if (!this.isCurrent(sendGen)) return { outcome: "sent" };
    if (!streamDone) dispatch({ type: "FINISH_STREAMING" });

    let result: TurnResult = { outcome: "sent" };
    if (turn.failure) {
      const failure = turn.failure;
      await this.presentHostFailure(failure, failureContext);
      if (failure.kind === "refused") result = { outcome: "refused", failure };
      if (!this.isCurrent(sendGen)) return result;
    }
    if (this.options.refreshAfterStream === false && !turn.doneUnreadable) return result;

    // `done` is a trimmed snapshot — undoAvailable and redoAvailable are
    // absent — so re-read for them.
    try {
      const after = await transport.read(conversationId, true);
      if (!this.isCurrent(sendGen)) return result;
      // An unreadable `done` left the turn's outcome unapplied; this read of
      // the current step carries the same fields, so it stands in.
      if (turn.doneUnreadable && !stopped()) this.applyDone(after, turn);
      dispatch({
        type: "SET_UNDO_REDO",
        undoAvailable: after.undoAvailable ?? false,
        redoAvailable: after.redoAvailable ?? false,
      });
      // A failing stream sends `error` and closes WITHOUT a `done`, so only
      // this read learns the conversation is ERROR / EXECUTION_INTERRUPTED.
      // But it RACES a Stop: a refresh issued before Stop set
      // EXECUTION_INTERRUPTED lands afterwards with a stale READY. A
      // controller that is no longer current means this turn was stopped.
      // And a READY read straight after this turn announced a pause is the
      // store not having caught up: keep the pause.
      const staleOverPause = turn.announcedPause && after.conversationState === "READY";
      if (after.conversationState && !stopped() && !staleOverPause) {
        dispatch({
          type: "SET_CONVERSATION_STATE",
          state: after.conversationState,
          endReason: after.endReason ?? null,
        });
      }
    } catch {
      // Best effort — unless it was also the only source of the turn's
      // outcome: then end the turn, and say so when nothing of the reply is
      // on screen. The message WAS consumed, so the draft is not handed back.
      if (turn.doneUnreadable && this.isCurrent(sendGen) && !stopped()) {
        this.applyDone(null, turn);
        if (turn.tokenCount === 0) {
          dispatch({ type: "ADD_MESSAGE", message: makeNotice(`⚠️ ${t("conversation.send.connectionLost")}`) });
        }
      }
    }
    return result;
  }

  /** Hand a failure to the host, then end the turn whatever it did. */
  private async presentHostFailure(failure: TurnFailure, context: TurnFailureContext): Promise<boolean> {
    const present = this.options.presentFailure;
    if (!present) return false;
    const handled = await present(failure, context);
    if (handled && context.isCurrent()) {
      this.pendingTurns.delete(context.userMessageId);
      this.dispatch({ type: "FINISH_STREAMING" });
      this.dispatch({ type: "SET_PROCESSING", value: false });
    }
    return handled;
  }

  /** Called when a turn starts (the follow-to-bottom hand-off). */
  onTurnStart?: () => void;

  private async handleSendFailure(
    err: unknown,
    ctx: {
      sendGen: number;
      turn: TurnContext;
      turnId: string;
      conversationId: string | null;
      stopped: () => boolean;
      failureContext: TurnFailureContext;
    },
  ): Promise<TurnResult> {
    const { sendGen, turn, turnId, conversationId, stopped, failureContext } = ctx;
    const dispatch = this.dispatch;
    const t = this.t;
    const transport = this.transport;
    const status = err instanceof ApiError ? err.status : typeof (err as { status?: unknown })?.status === "number" ? (err as { status: number }).status : null;
    const refused = status !== null && status >= 400 && status < 500;
    const failure: TurnFailure = {
      kind: refused ? "refused" : "failed",
      status,
      code: null,
      message: errorMessage(err),
      error: err,
      partial: turn.tokenCount > 0,
    };
    const result: TurnResult = { outcome: refused ? "refused" : "failed", failure };
    // A failure after New Conversation belongs to the turn the user abandoned.
    if (!this.isCurrent(sendGen)) return result;
    // Stop (or New conversation) ended this turn on purpose; whoever stopped it
    // reset the UI, and the abort must not be reported as a failure.
    if (transport.mode !== "local" && stopped()) return { outcome: "sent" };

    dispatch({ type: "SET_PROCESSING", value: false });
    dispatch({ type: "SET_THINKING", value: false });
    // This path never reaches FINISH_STREAMING, so clear these here too.
    dispatch({ type: "SET_ESCALATING", value: false });
    dispatch({ type: "SET_ACTIVE_TOOL", tool: null });

    if (await this.presentHostFailure(failure, failureContext)) return result;
    if (!this.isCurrent(sendGen)) return result;

    if (status === 409) {
      // Refused and NEVER consumed — most often because the conversation is
      // awaiting a human decision. Withdraw the bubble, hand the text back, and
      // say why. The server's own text is an operator instruction, not
      // something to show an end user.
      this.withdraw(turnId);
      dispatch({ type: "ADD_MESSAGE", message: makeNotice(t("conversation.send.conflict")) });
      // Re-read so the paused state (and its card) appears immediately. Gated
      // on the conversation id ALONE: the managed route has no agent id, and
      // requiring one left its composer live against a paused conversation.
      if (conversationId) {
        try {
          const snap = await transport.read(conversationId, true);
          if (!this.isCurrent(sendGen)) return result;
          if (snap.conversationState) {
            dispatch({ type: "SET_CONVERSATION_STATE", state: snap.conversationState, endReason: snap.endReason ?? null });
          }
        } catch {
          // best effort
        }
      }
      return result;
    }

    if (status === 410 && conversationId) {
      // The conversation has ended (410 GONE) and the turn was never consumed.
      // Ask why: an agent update that retired the conversation's version gets
      // its own footer.
      let endReason: string | null = null;
      try {
        endReason = (await transport.read(conversationId, true)).endReason ?? null;
      } catch {
        // best effort — the generic ended footer still applies
      }
      if (!this.isCurrent(sendGen)) return result;
      this.withdraw(turnId);
      dispatch({ type: "SET_CONVERSATION_STATE", state: "ENDED", endReason });
      if (endReason !== END_REASON_AGENT_VERSION_RETIRED) {
        dispatch({ type: "ADD_MESSAGE", message: makeNotice(t("conversation.send.ended")) });
      }
      return result;
    }

    // Every other 4xx refused the turn before it ran: withdraw and hand back.
    // Each has its own copy because the remedies differ (sign in again,
    // shorten, wait, ask the operator).
    if (refused) {
      this.withdraw(turnId);
      const copy =
        status === 401
          ? t("conversation.send.sessionExpired")
          : status === 403
            ? t("conversation.send.forbidden")
            : status === 404
              ? t("conversation.send.notFound")
              : status === 413
                ? t("conversation.send.tooLarge")
                : status === 429
                  ? t("conversation.send.rateLimited")
                  : t("conversation.send.failed");
      dispatch({ type: "ADD_MESSAGE", message: makeNotice(copy) });
      return result;
    }

    console.error("Failed to send message:", err);
    // From here the failure is a dropped connection or a server error.
    if (turn.tokenCount === 0) {
      // Nothing of the answer arrived: take the bubble back and put the text in
      // the composer again (withdraw also drops the empty placeholder).
      this.withdraw(turnId);
      dispatch({ type: "FINISH_STREAMING" });
      dispatch({
        type: "ADD_MESSAGE",
        message: makeNotice(status === 503 ? t("conversation.send.unavailable") : t("conversation.send.failed")),
      });
      return result;
    }
    // Part of the answer is already on screen. Keep it, say it may be cut
    // short, and re-read: the server may have finished the turn, or still be
    // running it — the next send must not run on a guessed state.
    this.pendingTurns.delete(turnId);
    dispatch({ type: "FINISH_STREAMING" });
    dispatch({ type: "APPEND_TO_LAST_AGENT", token: `\n\n⚠️ ${t("conversation.send.connectionLost")}` });
    if (conversationId) {
      try {
        const after = await transport.read(conversationId, true);
        if (!this.isCurrent(sendGen) || stopped()) return result;
        dispatch({
          type: "SET_UNDO_REDO",
          undoAvailable: after.undoAvailable ?? false,
          redoAvailable: after.redoAvailable ?? false,
        });
        if (after.conversationState) {
          dispatch({ type: "SET_CONVERSATION_STATE", state: after.conversationState, endReason: after.endReason ?? null });
        }
      } catch {
        // best effort
      }
    }
    return result;
  }

  /* ─── Undo, redo, retry ─────────────────────── */

  private async moveStep(move: "undo" | "redo"): Promise<void> {
    const conversationId = this.state.conversationId;
    // The managed route has no agent id; requiring one made these inert there.
    if (!conversationId) return;
    const gen = this.currentGeneration;
    if (this.transport.mode === "local") return;
    const dispatch = this.dispatch;
    try {
      dispatch({ type: "SET_PROCESSING", value: true });
      dispatch({ type: "SET_HISTORY_BUSY" });
      // Both answer 200 with an EMPTY body; re-read the snapshot to rebuild.
      await (move === "undo" ? this.transport.undo(conversationId) : this.transport.redo(conversationId));
      const snapshot = await this.transport.read(conversationId);
      // A New Conversation meanwhile must not get the old history.
      if (!this.isCurrent(gen)) return;
      this.applyHistorySnapshot(
        snapshot,
        move === "undo" ? { undoAvailable: false, redoAvailable: true } : { undoAvailable: true, redoAvailable: false },
      );
    } catch (err) {
      if (!this.isCurrent(gen)) return;
      // 409 is expected while the conversation is paused or a turn is running —
      // availability is deliberately NOT pause-aware server-side.
      const notNow = err instanceof ApiError && err.status === 409;
      const key =
        move === "undo"
          ? notNow
            ? "conversation.undo.notNow"
            : "conversation.undo.failed"
          : notNow
            ? "conversation.redo.notNow"
            : "conversation.redo.failed";
      dispatch({ type: "ADD_MESSAGE", message: makeNotice(this.t(key)) });
    } finally {
      if (this.isCurrent(gen)) dispatch({ type: "SET_PROCESSING", value: false });
    }
  }

  undo(): Promise<void> {
    return this.moveStep("undo");
  }

  redo(): Promise<void> {
    return this.moveStep("redo");
  }

  /** Rerun the last step after an ERROR or EXECUTION_INTERRUPTED. */
  async retry(): Promise<void> {
    const conversationId = this.state.conversationId;
    if (!conversationId) return;
    const gen = this.currentGeneration;
    const dispatch = this.dispatch;
    dispatch({ type: "SET_PROCESSING", value: true });
    dispatch({ type: "SET_HISTORY_BUSY" });
    // A rerun replaces the last step's reply, so a rating given to the old one
    // no longer applies (the server keeps it; the client forgets it).
    const rerunStep = lastStepIndex(this.state.messages);
    try {
      await this.transport.rerun(conversationId);
      if (this.isCurrent(gen) && rerunStep !== null) this.options.onStepRerun?.(rerunStep);
      const snapshot = await this.transport.read(conversationId, true);
      if (!this.isCurrent(gen)) return;
      if (snapshot.conversationState) {
        dispatch({ type: "SET_CONVERSATION_STATE", state: snapshot.conversationState, endReason: snapshot.endReason ?? null });
      }
      // Retry re-reads the same step that failed; its output is already shown.
      this.applySnapshot(snapshot, { dedupe: true });
    } catch (err) {
      if (!this.isCurrent(gen)) return;
      dispatch({
        type: "ADD_MESSAGE",
        message: makeNotice(
          err instanceof ApiError && err.status === 409
            ? this.t("conversation.retry.nothing")
            : this.t("conversation.retry.failed"),
        ),
      });
    } finally {
      if (this.isCurrent(gen)) dispatch({ type: "SET_PROCESSING", value: false });
    }
  }

  /* ─── Pause (HITL), cancel, stop ────────────── */

  setApprovalStatus(status: ApprovalStatus | null): void {
    this.dispatch({ type: "SET_APPROVAL_STATUS", status });
  }

  /**
   * The pause ended — by a reviewer, or by timeout policy. Nothing was pushed,
   * so re-read to pick up the resumed turn. Resolves false to keep a watch
   * alive: a dropped refresh must not strand the client in a pause it can never
   * leave.
   */
  async refreshAfterPause(isStale: () => boolean): Promise<boolean> {
    const conversationId = this.state.conversationId;
    if (!conversationId) return false;
    try {
      const snapshot = await this.transport.read(conversationId, true);
      // Torn down meanwhile (a restart): applying it would graft the old
      // conversation onto the new one.
      if (isStale()) return true;
      // Re-reading the step that was already rendered when the turn paused.
      this.applySnapshot(snapshot, { dedupe: true });
      return true;
    } catch (err) {
      console.error("Failed to refresh after approval:", err);
      return false;
    }
  }

  /** Cancel a paused turn (the paused card's button). */
  async cancel(): Promise<void> {
    const conversationId = this.state.conversationId;
    if (!conversationId) return;
    const gen = this.currentGeneration;
    const dispatch = this.dispatch;
    try {
      dispatch({ type: "SET_PROCESSING", value: true });
      dispatch({ type: "SET_HISTORY_BUSY" });
      await this.transport.cancel(conversationId);
      // These describe the conversation that was cancelled, not one started since.
      if (!this.isCurrent(gen)) return;
      dispatch({ type: "SET_APPROVAL_STATUS", status: null });
      dispatch({ type: "SET_CONVERSATION_STATE", state: "EXECUTION_INTERRUPTED" });
      dispatch({ type: "ADD_MESSAGE", message: makeNotice(this.t("conversation.send.cancelled")) });
    } catch (err) {
      if (!this.isCurrent(gen)) return;
      if (err instanceof ApiError && err.status === 409) {
        // Nothing to cancel — it resolved between render and click.
        dispatch({ type: "SET_APPROVAL_STATUS", status: null });
      } else {
        console.error("Cancel failed:", err);
      }
    } finally {
      if (this.isCurrent(gen)) dispatch({ type: "SET_PROCESSING", value: false });
    }
  }

  /** Stop generating: abort the read at once, then ask the server to stop. */
  async stop(): Promise<void> {
    const dispatch = this.dispatch;
    this.abort?.abort();
    this.abort = null;
    // Stopping before the first token left an empty bubble reading "No response".
    dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
    dispatch({ type: "FINISH_STREAMING" });
    dispatch({ type: "SET_PROCESSING", value: false });
    dispatch({ type: "SET_THINKING", value: false });

    const conversationId = this.state.conversationId;
    if (!conversationId || this.transport.mode === "local") return;
    const gen = this.currentGeneration;
    try {
      await this.transport.cancel(conversationId);
      // New Conversation while the cancel was in flight: the fresh one is not interrupted.
      if (!this.isCurrent(gen)) return;
      dispatch({ type: "SET_CONVERSATION_STATE", state: "EXECUTION_INTERRUPTED" });
    } catch (err) {
      // 409 = nothing to cancel; the turn finished as we clicked.
      if (!(err instanceof ApiError && err.status === 409)) console.error("Stop failed:", err);
    }
  }

  /* ─── Restart ───────────────────────────────── */

  /** Abandon whatever is in flight and clear: the next open starts from nothing. */
  reset(): void {
    // Without this the old stream kept writing tokens, state and undo/redo
    // flags into the NEW conversation. With a grace period the stream is left
    // to drain (the generation check keeps it from writing) and aborted later.
    if (this.options.detachedGraceMs === undefined) this.abort?.abort();
    this.abort = null;
    this.generation += 1;
    this.pendingTurns.clear();
    this.dispatch({ type: "CLEAR_MESSAGES" });
  }

  /** New conversation (or Start over): abandon this one and open a fresh one. */
  async restart(): Promise<void> {
    this.reset();
    await this.open(true);
  }

  /** The conversation could not be opened: try the same one again. */
  async retryStart(): Promise<void> {
    this.reset();
    await this.open(false);
  }
}
