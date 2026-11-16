/* ──────────────────────────────────────────────
   GroupDiscussion — the team kind of conversation (frontend unification
   plan §3.2): one group's discussion streamed over SSE, as a framework-free
   store. The state a live run builds (phases, member turns, the human member's
   turn, the approval pause, the decision, costs, stances, tasks), how each SSE
   event changes it, and how a run is started, resumed, continued, stopped and
   followed. The host passes a transport bound to its own client and, if it
   wants, a store of its own. Moved here from @eddi/app-kit's
   `hooks/use-group-discussion-stream.ts` (phase 6), which now binds it.
   ────────────────────────────────────────────── */

import type {
  TranscriptEntry,
  TranscriptEntryType,
  GroupConversationState,
  GroupSSEEvent,
  GroupStartPayload,
  RoundStartPayload,
  GroupConversation,
  PhaseStartPayload,
  SpeakerStartPayload,
  SpeakerCompletePayload,
  SpeakerCompleteOutcome,
  PhaseCompletePayload,
  GroupCompletePayload,
  TaskPlanCreatedPayload,
  TaskVerifiedPayload,
  ConvergenceCheckedPayload,
  ConvergenceReachedPayload,
  DecisionReachedPayload,
  DecisionRecord,
  GroupAttachmentRef,
  HumanInputRequestedPayload,
  RetroRecordedPayload,
  ArtifactUpdatedPayload,
  CostUpdatedPayload,
  MemberToolCallPayload,
  StanceUpdatedPayload,
} from "@eddi/core/groups";


// ─── Streaming State ────────────────────────────────────────────

/**
 * What the convergence events (I2) told us about one phase. `convergence_checked`
 * fires on every check whether or not it converged, so a phase approaching
 * agreement is visible before it stops rather than only afterwards;
 * `convergence_reached` then adds how many repeats were skipped.
 */
export interface ConvergenceProgress {
  phaseIndex: number;
  phaseName: string;
  /** 0-based index of the most recently checked repeat. */
  repeat: number;
  /** The judge's 0..1 agreement score, or `null` when no judge ran (-1 on the wire). */
  agreementScore: number | null;
  converged: boolean;
  /** Repeats the phase was configured for but will not run. Only known once converged. */
  repeatsSkipped: number | null;
  reason: string;
}

export interface GroupStreamState {
  /** Whether the SSE stream is actively connected */
  isStreaming: boolean;
  /** The conversation ID assigned by the backend */
  conversationId: string | null;
  /** Overall conversation state */
  state: GroupConversationState;
  /** Progressive transcript built from SSE events */
  transcript: TranscriptEntry[];
  /** Currently active phase */
  currentPhase: { index: number; name: string; type: string } | null;
  /** Agent IDs that are currently "speaking" (between speaker_start and speaker_complete) */
  activeSpeakers: Set<string>;
  /** Final synthesized answer (set on group_complete) */
  synthesizedAnswer: string | null;
  /**
   * Structured conclusion from `decision_reached` (EDDI F3) — a debate verdict
   * today. Null until a decision-producing phase runs; a discussion that ends in
   * prose alone never sets it.
   */
  decision: DecisionRecord | null;
  /**
   * The most recent convergence check per phase index (I2). Keyed by phase so a
   * later phase's check does not overwrite the record of an earlier one, and so
   * the display can show "this phase stopped after 2 of 4 repeats" next to the
   * phase it belongs to.
   */
  convergence: Map<number, ConvergenceProgress>;
  /** Error message if the discussion failed */
  error: string | null;
  /** Classifies a failure so the UI can offer recovery guidance.
   *  "config_drift" = the group's phases changed while paused, so the resume was
   *  aborted and the discussion is still awaiting approval (recoverable). */
  errorKind: "config_drift" | "generic" | null;
  /** Timestamp when the stream was started (stable, not recalculated per render) */
  startedAt: string | null;
  /** Task plan received from task_plan_created SSE event */
  taskPlan: { id: string; subject: string; assignedTo: string; priority: number }[] | null;
  /** Task verification results from task_verified SSE events */
  taskVerifications: Map<string, { passed: boolean; feedback: string }>;
  /** Set of task IDs currently being executed (inferred from speaker events during EXECUTE phase) */
  tasksInProgress: Set<string>;
  /** Set of task IDs completed (inferred from speaker events during EXECUTE phase) */
  tasksCompleted: Set<string>;
  /** HITL pause info when the group is awaiting approval */
  hitlPause: {
    phaseIndex: number;
    phaseName: string;
    reason: string;
    granularity: string;
  } | null;
  /** HITL resume info after approval decision */
  hitlResume: {
    verdict: string;
    note?: string;
    decidedBy?: string;
  } | null;
  /** Cancellation info when the group discussion is cancelled */
  cancelInfo: {
    reason?: string;
    cancelledBy?: string;
  } | null;
  /**
   * A HUMAN group member's turn is up (I6, `human_input_requested`). Terminal —
   * closes the stream, same as `hitlPause`. Only carries identifiers; the
   * rendered prompt lives on the persisted conversation's `pendingHumanInput`,
   * which the settle effect in `group-detail.tsx` switches to (same pattern
   * `hitlPause` already uses).
   */
  humanInputRequest: {
    memberId: string;
    displayName: string;
    phaseIndex: number;
    phaseName: string;
  } | null;
  /**
   * Every `retro_recorded` event this stream has seen (I8) — NOT terminal, a
   * discussion can run more than one RETRO phase across rounds, so this
   * accumulates rather than holding only the latest.
   */
  retroRecorded: RetroRecordedPayload[];
  /**
   * Every `artifact_updated` event this stream has seen (I17) — metadata only,
   * never content (a full read requires re-fetching the conversation). NOT
   * terminal; a discussion can write several artifacts across its run.
   */
  artifactUpdates: ArtifactUpdatedPayload[];
  /**
   * Ledger key → that key's CUMULATIVE cost in USD, from `cost_updated`.
   *
   * Keyed rather than summed-on-arrival on purpose: the backend records by
   * replacement, and a PARALLEL phase's turns interleave, so adding deltas (or
   * trusting a frame's own `totalCost` ordering) double-counts on a replay and
   * races on a fan-out. Summing this map is order-independent — see
   * `streamTotalCost`.
   *
   * Keys are NOT all agent ids: system spend uses `system:…` and a nested
   * GROUP member uses `agentId:childConversationId`.
   */
  memberCosts: Map<string, number>;
  /**
   * agentId → the member's current one-line stance, from `stance_updated`.
   * Live-only; the persisted equivalent is `conversation.memberStances`.
   */
  stances: Map<string, StanceUpdatedPayload>;
  /**
   * agentId → the tool that member's loop most recently started, from
   * `member_tool_call` ("using X…" indicator). Cleared when the member's turn
   * ends; name only — the backend never sends arguments.
   */
  memberToolCalls: Map<string, string>;
  /**
   * The connection ended without any terminal event — a proxy timeout, a pod
   * restart, a network drop. The discussion may well still be running on the
   * server, so this is deliberately NOT a failure: consumers stop treating the
   * live transcript as authoritative and follow the persisted conversation
   * (which polls while it is running) instead of freezing on the last frame.
   */
  interrupted: boolean;
  /**
   * The server has answered this request with at least one frame. False from
   * the moment a start/continue/approve is issued until then, so a failure with
   * `connected` still false means the request was refused before anything ran —
   * the composer keeps (or restores) what the user typed — and a "streaming
   * live" confirmation is only honest once this is true.
   */
  connected: boolean;
  /**
   * Stop was pressed before the backend had named the conversation, so there
   * was nothing to cancel yet. The cancel is sent as soon as `group_start`
   * supplies the id; until then the UI shows the stop as in progress.
   */
  cancelRequested: boolean;
  /**
   * A deferred Stop (see `cancelRequested`) was sent once `group_start` named
   * the conversation, and the server refused it. The discussion is still
   * running and the stream still follows it, so this is deliberately NOT
   * `error`: the board renders `error` as a discussion failure, which a failed
   * Stop is not. The board reports it the way it reports an immediate Stop
   * failure (a toast) and then clears it with `clearCancelError`.
   */
  cancelError: string | null;
  /**
   * Index in `transcript` where the CURRENT round's entries begin — the live
   * counterpart of `GroupConversation.roundStartTranscriptIndex`.
   *
   * A continuation deliberately KEEPS the previous rounds (`continueStream`
   * preserves `s.transcript`, and the `group_start` handler appends the new
   * question), so anything bucketing by `phaseIndex` — which the backend
   * restarts at 0 each round — needs this to avoid merging rounds.
   */
  roundStartIndex: number;
}


/** Shared empty state handed to consumers that have no stream yet. Never mutated. */
const initialState: GroupStreamState = {
  isStreaming: false,
  conversationId: null,
  state: "CREATED",
  transcript: [],
  currentPhase: null,
  activeSpeakers: new Set(),
  synthesizedAnswer: null,
  decision: null,
  convergence: new Map(),
  error: null,
  errorKind: null,
  startedAt: null,
  taskPlan: null,
  taskVerifications: new Map(),
  tasksInProgress: new Set(),
  tasksCompleted: new Set(),
  hitlPause: null,
  hitlResume: null,
  cancelInfo: null,
  humanInputRequest: null,
  retroRecorded: [],
  artifactUpdates: [],
  memberCosts: new Map(),
  stances: new Map(),
  memberToolCalls: new Map(),
  interrupted: false,
  connected: false,
  cancelRequested: false,
  cancelError: null,
  roundStartIndex: 0,
};

/** A clean state with its own collection instances (the shared `initialState`
 *  ones must never be written to). */
function freshState(): GroupStreamState {
  return {
    ...initialState,
    activeSpeakers: new Set(),
    tasksInProgress: new Set(),
    tasksCompleted: new Set(),
    taskVerifications: new Map(),
    convergence: new Map(),
    retroRecorded: [],
    artifactUpdates: [],
    memberCosts: new Map(),
    stances: new Map(),
    memberToolCalls: new Map(),
    roundStartIndex: 0,
  };
}

/** The shared empty state, for a consumer with no stream yet. Never mutate it. */
export const initialGroupStreamState: GroupStreamState = initialState;

/** A clean state with collections of its own. */
export const freshGroupStreamState = freshState;

/**
 * What a Stop achieved.
 *
 * - `cancelled` — the server cancelled the discussion.
 * - `alreadyEnded` — the server answered 409: the discussion reached a terminal
 *   state on its own before the cancel landed. Nothing is running any more, so
 *   this is not an error; the persisted document says how it ended.
 * - `pending` — the backend has not named the conversation yet; the cancel is
 *   sent as soon as `group_start` arrives.
 * - `nothingToCancel` — no stream for this group.
 */
export type CancelOutcome = "cancelled" | "alreadyEnded" | "pending" | "nothingToCancel";

/**
 * Where a resumed or continued stream picks up from.
 *
 * Neither the approve nor the continue endpoint replays what came before it, so
 * the store holds only what THIS page session saw stream: nothing after a
 * reload, and another discussion's rows, costs and stances if the user watched
 * one stream and then picked this one. The first dropped every earlier phase
 * and round from the overview the moment a resumed turn arrived (the digest
 * prefers a non-empty live transcript); the second showed a different
 * discussion's turns and spend under this one.
 *
 * So a stream that was on another conversation starts from a clean state, and
 * the persisted document (the complete record up to the pause or the previous
 * round) supplies the transcript whenever it holds at least as much as the
 * store does. The store only wins when it is AHEAD, which happens when the
 * caller's copy was fetched before the last streamed rows.
 */
function resumeBase(s: GroupStreamState, gcId: string, seed?: GroupConversation | null): GroupStreamState {
  const own = s.conversationId === gcId;
  const base = own ? s : freshState();
  const persisted = seed?.id === gcId ? (seed.transcript ?? []) : null;
  if (persisted && persisted.length >= base.transcript.length) {
    return { ...base, transcript: persisted, roundStartIndex: seed?.roundStartTranscriptIndex ?? 0 };
  }
  return base;
}

/** Mark every still-open speaker placeholder as a turn that produced nothing. */
function closePlaceholders(
  transcript: TranscriptEntry[],
  matches: (entry: TranscriptEntry) => boolean = () => true,
): TranscriptEntry[] {
  let changed = false;
  const next = transcript.map((entry) => {
    if (!isOpenPlaceholder(entry) || !matches(entry)) return entry;
    changed = true;
    return { ...entry, type: "SKIPPED" as TranscriptEntryType };
  });
  return changed ? next : transcript;
}

/**
 * A `speaker_start` placeholder that no `speaker_complete` has filled yet.
 *
 * Every renderer draws a null-content member entry as "still typing", so one
 * left open after its turn ended types forever. SKIPPED/ERROR rows carry null
 * content legitimately (`member_pause_skipped`, a no-content outcome) and are
 * closed already.
 */
export function isOpenPlaceholder(entry: TranscriptEntry): boolean {
  return (
    entry.content === null &&
    entry.type !== "SKIPPED" &&
    entry.type !== "ERROR" &&
    entry.type !== "QUESTION" &&
    !!entry.speakerAgentId
  );
}

/**
 * How many transcript rows the stream has actually delivered — everything but
 * the still-open placeholders, which are not rows the stored document will
 * hold. Compared against the persisted transcript to tell whether a refetched
 * document has caught up with what the live view showed.
 */
export function deliveredRowCount(transcript: TranscriptEntry[]): number {
  let count = 0;
  for (const entry of transcript) if (!isOpenPlaceholder(entry)) count++;
  return count;
}

/**
 * Whether the stored copy of the stream's discussion holds at least what the
 * stream delivered — the point at which a stopped stream can give way to it
 * without rows vanishing on screen until the next poll.
 */
export function persistedHasCaughtUp(
  conversation: { id: string; transcript?: TranscriptEntry[] | null } | null | undefined,
  stream: Pick<GroupStreamState, "conversationId" | "transcript">,
): boolean {
  return (
    !!conversation &&
    conversation.id === stream.conversationId &&
    (conversation.transcript?.length ?? 0) >= deliveredRowCount(stream.transcript)
  );
}

/**
 * Terminal settle shared by every event that ends the run: no one is speaking
 * any more, so any placeholder still open never gets its content.
 */
function settleTerminal(
  s: GroupStreamState,
): Pick<GroupStreamState, "activeSpeakers" | "transcript" | "memberToolCalls"> {
  return {
    activeSpeakers: new Set(),
    transcript: closePlaceholders(s.transcript),
    memberToolCalls: new Map(),
  };
}

// ─── Controller ─────────────────────────────────────────────────

/**
 * How a {@link GroupDiscussion} reaches the backend: the three streaming calls
 * (start, continue, approve-and-resume) and the cancel. The host binds them to
 * its own authenticated client — the signed-in apps to `@eddi/app-kit`'s
 * `lib/api/groups` — so the controller never knows about tokens or base URLs.
 *
 * `A` is the approval request the host's approve call takes (the apps'
 * `GroupApprovalRequest`); the controller only passes it through.
 */
export interface GroupTransport<A = unknown> {
  /** Start a new discussion and stream it. */
  discuss(
    groupId: string,
    question: string,
    signal: AbortSignal,
    attachments?: GroupAttachmentRef[],
  ): AsyncIterable<GroupSSEEvent>;
  /** Continue a finished discussion with a new round and stream it. */
  continueRound(groupId: string, gcId: string, question: string, signal: AbortSignal): AsyncIterable<GroupSSEEvent>;
  /** Decide a paused discussion and stream what the decision resumes. */
  approve(groupId: string, gcId: string, request: A, signal: AbortSignal): AsyncIterable<GroupSSEEvent>;
  /** Cancel a discussion on the server. */
  cancel(groupId: string, gcId: string): Promise<unknown>;
  /** Whether a failed cancel means the discussion had already ended on its own (HTTP 409). */
  isAlreadyEnded(error: unknown): boolean;
}

/**
 * Where a {@link GroupDiscussion} keeps its state, when the host wants it in its
 * own store (the apps keep every group's stream in one Zustand store, so a
 * discussion outlives the screen that started it). `get` returning `undefined`
 * means "no stream for this group" — {@link GroupDiscussion.reset} removes the
 * entry rather than storing a blank one, so finished transcripts do not pile up.
 */
export interface GroupDiscussionStore {
  get(): GroupStreamState | undefined;
  set(next: GroupStreamState): void;
  remove(): void;
  subscribe(listener: () => void): () => void;
}

export interface GroupDiscussionOptions<A = unknown> {
  groupId: string;
  transport: GroupTransport<A>;
  /** The host's store; without one the controller keeps its own. */
  store?: GroupDiscussionStore;
}

/** A store of one value with listeners — the default when the host brings none. */
function ownStore(): GroupDiscussionStore {
  let value: GroupStreamState | undefined;
  const listeners = new Set<() => void>();
  const notify = () => listeners.forEach((listener) => listener());
  return {
    get: () => value,
    set: (next) => {
      value = next;
      notify();
    },
    remove: () => {
      if (value === undefined) return;
      value = undefined;
      notify();
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

/**
 * The team kind of conversation (frontend unification plan §3.2): one group's
 * discussion — its phases, member turns, a human member's turn, the approval
 * pause, the decision — as a plain store (`subscribe`, `getSnapshot`), read in
 * React through `useSyncExternalStore` and testable without it.
 *
 * Every rule about how a run is started, resumed, continued, stopped and
 * followed lives here once; the Manager's group workspace and Workforce's board
 * both drive it (through `@eddi/app-kit`'s `useGroupDiscussionStream`).
 *
 * A discussion keeps streaming while nobody watches it: the connection is never
 * closed by an unmount, only by {@link abort}, {@link cancel}, {@link reset} or
 * a terminal event.
 */
export class GroupDiscussion<A = unknown> {
  readonly groupId: string;
  private readonly transport: GroupTransport<A>;
  private readonly store: GroupDiscussionStore;
  /**
   * The in-flight stream's abort controller — the supersession source of truth:
   * a loop whose controller is no longer this one has been replaced (a new run,
   * Stop, reset) and must not write any more.
   */
  private abortController: AbortController | null = null;

  constructor({ groupId, transport, store }: GroupDiscussionOptions<A>) {
    this.groupId = groupId;
    this.transport = transport;
    this.store = store ?? ownStore();
  }

  /** The current state; the shared empty state while there is no stream. Never mutate it. */
  getSnapshot = (): GroupStreamState => this.store.get() ?? initialState;

  subscribe = (listener: () => void): (() => void) => this.store.subscribe(listener);

  /** Whether a stream (or a state left by one) exists for this group. */
  hasStream(): boolean {
    return this.store.get() !== undefined;
  }

  /** Apply `updater` to the state, creating a fresh one when there is none. */
  update(updater: (s: GroupStreamState) => GroupStreamState): void {
    this.store.set(updater(this.store.get() ?? freshState()));
  }

  private swapController(): AbortController {
    this.abortController?.abort();
    const abort = new AbortController();
    this.abortController = abort;
    return abort;
  }

  /** Start a new discussion of `question`, replacing whatever this group showed. */
  async start(question: string, attachments?: GroupAttachmentRef[]): Promise<void> {
    const abort = this.swapController();
    this.update(() => ({
      ...freshState(),
      isStreaming: true,
      state: "IN_PROGRESS",
      startedAt: new Date().toISOString(),
    }));
    await this.consume(this.transport.discuss(this.groupId, question, abort.signal, attachments), abort);
  }

  /**
   * Submit an approve/reject decision for a paused discussion AND stream the
   * resumed progress over the same connection. Preserves the existing
   * transcript so a live pause→resume appends rather than restarts.
   */
  async approve(gcId: string, request: A, seed?: GroupConversation | null): Promise<void> {
    const abort = this.swapController();
    this.update((s) => ({
      ...resumeBase(s, gcId, seed),
      isStreaming: true,
      state: "IN_PROGRESS",
      conversationId: gcId,
      interrupted: false,
      connected: false,
      cancelRequested: false,
      cancelError: null,
      hitlPause: null,
      hitlResume: null,
      humanInputRequest: null,
      error: null,
      startedAt: (s.conversationId === gcId ? s.startedAt : null) ?? new Date().toISOString(),
      activeSpeakers: new Set(),
    }));
    await this.consume(this.transport.approve(this.groupId, gcId, request, abort.signal), abort);
  }

  /**
   * Continue a COMPLETED discussion as a new round. Preserves the existing
   * transcript so the new round appends rather than replaces — same pattern as
   * {@link approve}.
   */
  async continueRound(gcId: string, question: string, seed?: GroupConversation | null): Promise<void> {
    const abort = this.swapController();
    this.update((s) => ({
      ...resumeBase(s, gcId, seed),
      isStreaming: true,
      state: "IN_PROGRESS",
      conversationId: gcId,
      error: null,
      errorKind: null,
      interrupted: false,
      connected: false,
      cancelRequested: false,
      cancelError: null,
      startedAt: (s.conversationId === gcId ? s.startedAt : null) ?? new Date().toISOString(),
      // Keep transcript (appended by the round_start handler), but reset
      // per-round derived fields so stale data doesn't leak into the UI.
      synthesizedAnswer: null,
      // `continueDiscussion` clears both of these server-side — a round that
      // produces no verdict of its own must not display the previous round's.
      decision: null,
      convergence: new Map(),
      currentPhase: null,
      taskPlan: null,
      taskVerifications: new Map(),
      tasksInProgress: new Set(),
      tasksCompleted: new Set(),
      activeSpeakers: new Set(),
      hitlPause: null,
      hitlResume: null,
      cancelInfo: null,
      humanInputRequest: null,
      retroRecorded: [],
      artifactUpdates: [],
    }));
    await this.consume(this.transport.continueRound(this.groupId, gcId, question, abort.signal), abort);
  }

  /**
   * Detach from the stream WITHOUT touching the discussion — it keeps running on
   * the server. For navigating to another discussion, never for "Stop".
   */
  abort(): void {
    this.abortController?.abort();
    this.abortController = null;
    this.update((s) => ({ ...s, isStreaming: false }));
  }

  /**
   * Stop the discussion: cancel it on the server, then close the stream.
   *
   * "Stop" used to be {@link abort}: it closed this tab's connection and nothing
   * else, so the discussion went on running — and spending — on the server
   * while the board froze on the last frame it had seen. The cancel is sent
   * FIRST and the stream closed only once it succeeded: if the cancel fails,
   * the discussion is still running and the stream is still the best view of
   * it. The caller reports a thrown error.
   *
   * `knownGcId` names the discussion to stop when it is not the one this tab is
   * streaming — one whose connection dropped, one adopted from the stored list
   * after a reload, or one selected from Sessions while ANOTHER discussion
   * streams here. That one is cancelled by id and the live stream is left
   * alone. Omitted, or naming the streamed discussion, it stops the live stream.
   */
  async cancel(knownGcId?: string): Promise<CancelOutcome> {
    const current = this.store.get();
    if (knownGcId && knownGcId !== current?.conversationId) {
      // A different discussion from the one this tab streams (if it streams
      // one at all): Stop is about the discussion on screen, and must not
      // cancel the live stream instead. `cancelAndClose` closes a stream only
      // when it is this discussion's, so the live one keeps running.
      return this.cancelAndClose(knownGcId);
    }
    if (!current?.isStreaming && !current?.cancelRequested) {
      // No live stream. A discussion can still be running on the server — the
      // connection dropped, or the board adopted it after a reload — and it
      // needs a Stop just as much.
      return knownGcId ? this.cancelAndClose(knownGcId) : "nothingToCancel";
    }
    const gcId = current.conversationId;
    if (!gcId) {
      // Nothing to address yet. `consume` sends the cancel as soon as
      // group_start names the conversation.
      this.update((s) => ({ ...s, cancelRequested: true, cancelError: null }));
      return "pending";
    }
    return this.cancelAndClose(gcId);
  }

  /** Acknowledge a reported {@link GroupStreamState.cancelError}. */
  clearCancelError(): void {
    if (!this.store.get()?.cancelError) return;
    this.update((s) => ({ ...s, cancelError: null }));
  }

  /**
   * Abort any in-flight stream AND drop the state. Use this when the user
   * explicitly starts a new discussion (clears the stale transcript, synthesized
   * answer, etc.).
   */
  reset(): void {
    this.abortController?.abort();
    this.abortController = null;
    this.store.remove();
  }

  /**
   * Resolves once the request just issued is known to have been accepted
   * (`true`: the first frame arrived) or refused (`false`: it failed before any
   * frame, or was aborted/reset first). Composers use it to hold on to what the
   * user typed until the server has actually taken it, and to confirm
   * "streaming live" only once something connected.
   */
  whenAccepted(): Promise<boolean> {
    return new Promise((resolve) => {
      const settled = (s: GroupStreamState | undefined): boolean | null => {
        if (!s) return false;
        if (s.connected) return true;
        if (!s.isStreaming) return false;
        return null;
      };
      const initial = settled(this.store.get());
      if (initial !== null) return resolve(initial);
      const unsubscribe = this.store.subscribe(() => {
        const result = settled(this.store.get());
        if (result === null) return;
        unsubscribe();
        resolve(result);
      });
    });
  }

  /**
   * Cancel `gcId` on the server, then close the stream.
   *
   * A 409 is the backend saying the discussion was already terminal — it ended
   * between the click and the request. The stream is closed all the same (there
   * is nothing left to follow), but the state is left to the persisted document
   * rather than claimed as CANCELLED, because that is not how it ended.
   */
  private async cancelAndClose(gcId: string): Promise<CancelOutcome> {
    let outcome: CancelOutcome = "cancelled";
    try {
      await this.transport.cancel(this.groupId, gcId);
    } catch (e) {
      if (!this.transport.isAlreadyEnded(e)) {
        this.update((s) => (s.conversationId === gcId ? { ...s, cancelRequested: false } : s));
        throw e;
      }
      outcome = "alreadyEnded";
    }
    // Only close the stream this cancel was about: a newer one may own the slot.
    // A discussion followed without a stream has no entry, and gets none.
    if (!this.store.get()) return outcome;
    const controller = this.abortController;
    let closed = false;
    this.update((s) => {
      if (s.conversationId !== gcId) return s;
      closed = true;
      return {
        ...s,
        ...settleTerminal(s),
        isStreaming: false,
        cancelRequested: false,
        // Whatever the connection did, the run is over now: "the live connection
        // was lost, keeps updating while it runs" would be false.
        interrupted: false,
        ...(outcome === "cancelled"
          ? { state: "CANCELLED" as GroupConversationState, cancelInfo: { reason: undefined, cancelledBy: undefined } }
          : {}),
      };
    });
    if (closed && controller) {
      controller.abort();
      if (this.abortController === controller) this.abortController = null;
    }
    return outcome;
  }

  /** Drain an SSE event source into the state, then settle `isStreaming`.
   *  Shared by the start / continue / approve-resume flows. */
  private async consume(events: AsyncIterable<GroupSSEEvent>, abort: AbortController): Promise<void> {
    // A superseded loop (new discussion started, or the user hit Stop) may still
    // run for a tick or fail afterwards. Its writes must not land on the stream
    // that replaced it — otherwise a fresh discussion flips back to "not
    // streaming", or inherits the old one's error.
    const setState = (updater: (s: GroupStreamState) => GroupStreamState) => {
      if (this.abortController !== abort) return;
      this.update(updater);
    };

    // Whether the server said how the run ended (or paused). Without one, the
    // connection simply stopped — see `interrupted`.
    let sawTerminal = false;
    // Whether anything arrived at all. A failure BEFORE the first frame is the
    // request being refused (a 400, a 403): the run never started. A failure
    // AFTER it is the connection dropping under a run that may still be going.
    let sawEvent = false;

    try {
      for await (const event of events) {
        if (!sawEvent) setState((s) => (s.connected ? s : { ...s, connected: true }));
        sawEvent = true;
        const isDone = applyGroupStreamEvent(event, setState);
        if (isDone) {
          sawTerminal = true;
          abort.abort();
          break;
        }
        // Stop was pressed before the conversation had an id; now it may have one.
        const current = this.store.get();
        if (current?.cancelRequested && current.conversationId && this.abortController === abort) {
          try {
            await this.cancelAndClose(current.conversationId);
            return;
          } catch (e) {
            // The Stop failed, the discussion did not: keep following it, and
            // report the refusal as a failed Stop rather than as `error`, which
            // the board renders as the discussion itself failing.
            // `cancelAndClose` has already dropped `cancelRequested`.
            setState((s) => ({ ...s, cancelError: e instanceof Error ? e.message : String(e) }));
          }
        }
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") {
        // Expected when we abort after a terminal event, or on Stop/New.
      } else if (!sawEvent) {
        const errorMsg = e instanceof Error ? e.message : String(e);
        setState((s) => ({
          ...s,
          ...settleTerminal(s),
          isStreaming: false,
          cancelRequested: false,
          state: "FAILED",
          error: errorMsg,
          errorKind: "generic",
        }));
        sawTerminal = true;
      }
      // Otherwise the run started and the connection then broke. That says
      // nothing about the run itself, so it is not declared FAILED: it falls
      // through to `interrupted` below.
    }

    // No terminal event and not stopped by us: the connection ended under a run
    // that may still be going. Placeholders stay open — those members may well
    // still be answering — and consumers switch to the persisted conversation.
    if (!sawTerminal && !abort.signal.aborted) {
      setState((s) => (s.isStreaming ? { ...s, isStreaming: false, cancelRequested: false, interrupted: true } : s));
    }
    // Safety-net for every other exit.
    setState((s) => (s.isStreaming ? { ...s, isStreaming: false } : s));

    // Unregister once finished. The controller field is the supersession source
    // of truth, so leaving a dead controller behind would have swapController
    // re-abort an already-aborted one on the next start. Only clear our own —
    // a stream that superseded us owns the slot now.
    if (this.abortController === abort) {
      this.abortController = null;
    }
  }
}

// ─── Event Handler ──────────────────────────────────────────────

/**
 * Records the question that opens a round and where that round begins.
 *
 * `roundStartIndex` is the question's own index for an appended round and 0 for
 * a fresh discussion. Without it the digest buckets every round's turns into the
 * current round's phases, because the backend restarts phaseIndex at 0 each
 * round.
 *
 * A resume that restarts at phase 0 re-announces the round it resumes, and a
 * transcript seeded from the persisted document already ends with that
 * question. Appending it again would duplicate the row AND count a round that
 * never ran, so a question matching the transcript's last row is kept, not
 * repeated.
 */
function openRound(
  s: GroupStreamState,
  conversationId: string | null | undefined,
  question: string,
  append: boolean,
): GroupStreamState {
  const opened = { ...s, conversationId: conversationId ?? s.conversationId, state: "IN_PROGRESS" as const };
  const last = s.transcript[s.transcript.length - 1];
  if (append && last?.type === "QUESTION" && last.content === question) return opened;
  const questionEntry: TranscriptEntry = {
    speakerAgentId: "user",
    speakerDisplayName: "User",
    content: question,
    phaseIndex: -1,
    phaseName: null,
    type: "QUESTION" as TranscriptEntryType,
    timestamp: new Date().toISOString(),
    errorReason: null,
    targetAgentId: null,
  };
  return {
    ...opened,
    transcript: append ? [...s.transcript, questionEntry] : [questionEntry],
    roundStartIndex: append ? s.transcript.length : 0,
  };
}

/**
 * Process a single SSE event from the group discussion stream: the reducer
 * every live run goes through. Returns `true` when the stream is logically
 * complete (a terminal event: completed, failed, paused, cancelled).
 */
export function applyGroupStreamEvent(
  event: GroupSSEEvent,
  setState: (updater: (s: GroupStreamState) => GroupStreamState) => void
): boolean {
  switch (event.type) {
    case "group_start": {
      try {
        const payload: GroupStartPayload = JSON.parse(event.data);
        // A conversation id already set means this stream is appending to a
        // known conversation (a resume); otherwise it is a brand-new discussion
        // and replaces whatever the store held.
        setState((s) =>
          openRound(s, payload.groupConversationId ?? payload.conversationId, payload.question, !!s.conversationId),
        );
      } catch (e) {
        console.warn('[SSE] Failed to parse group_start event:', e);
      }
      return false;
    }

    // Continuation rounds (round 2 onwards) open with this, never with
    // `group_start`. Unhandled, a live continuation recorded no question and
    // never moved `roundStartIndex`, so the overview bucketed every round's
    // turns into one round's phases, the exact merge the round boundary exists
    // to prevent, and the transcript showed no question for the new round.
    case "round_start": {
      try {
        const payload: RoundStartPayload = JSON.parse(event.data);
        setState((s) => openRound(s, payload.groupConversationId, payload.question, true));
      } catch (e) {
        console.warn('[SSE] Failed to parse round_start event:', e);
      }
      return false;
    }

    case "phase_start": {
      try {
        const payload: PhaseStartPayload = JSON.parse(event.data);
        setState((s) => ({
          ...s,
          currentPhase: {
            index: payload.phaseIndex,
            name: payload.phaseName,
            type: payload.phaseType,
          },
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse phase_start event:', e);
      }
      return false;
    }

    case "speaker_start": {
      try {
        const payload: SpeakerStartPayload = JSON.parse(event.data);
        setState((s) => {
          const newSpeakers = new Set(s.activeSpeakers);
          newSpeakers.add(payload.agentId);

          // Track task execution during EXECUTE phase
          let newTasksInProgress = s.tasksInProgress;
          if (s.currentPhase?.type === "EXECUTE" && s.taskPlan) {
            newTasksInProgress = new Set(s.tasksInProgress);
            // Match the next pending task for this speaker by display name
            // (the backend task plan carries assignedTo = display name only).
            const agentTask = s.taskPlan.find(
              (t) =>
                t.assignedTo === payload.displayName &&
                !s.tasksCompleted.has(t.id) &&
                !s.tasksInProgress.has(t.id)
            );
            if (agentTask) {
              newTasksInProgress.add(agentTask.id);
            }
          }

          return {
            ...s,
            activeSpeakers: newSpeakers,
            memberToolCalls: withoutToolCall(s.memberToolCalls, payload.agentId),
            tasksInProgress: newTasksInProgress,
            // Add a placeholder entry for the active speaker (typing indicator)
            transcript: [
              ...s.transcript,
              {
                speakerAgentId: payload.agentId,
                speakerDisplayName: payload.displayName,
                content: null,
                phaseIndex: payload.phaseIndex,
                phaseName: payload.phaseName,
                type: mapPhaseToEntryType(s.currentPhase?.type),
                timestamp: new Date().toISOString(),
                errorReason: null,
                targetAgentId: null,
              },
            ],
          };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse speaker_start event:', e);
      }
      return false;
    }

    case "speaker_complete": {
      try {
        const payload: SpeakerCompletePayload = JSON.parse(event.data);
        setState((s) => {
          const newSpeakers = new Set(s.activeSpeakers);
          newSpeakers.delete(payload.agentId);

          // A turn that produced nothing. Newer backends say why in `outcome`
          // (and send no content, so a failure is never shown as something the
          // member said); older ones just send no content. Either way the
          // placeholder must close — left at `content: null` it rendered as a
          // member typing for ever.
          const outcome = noContentOutcome(payload.outcome);
          const content = outcome ? null : (payload.response ?? payload.content ?? null);
          const closedType: TranscriptEntryType | null =
            outcome === "ERROR" ? "ERROR" : outcome || content === null ? "SKIPPED" : null;

          // Replace the placeholder entry with the real content
          const transcript = [...s.transcript];
          const placeholderIdx = transcript.findIndex(
            (e) =>
              e.speakerAgentId === payload.agentId &&
              isOpenPlaceholder(e) &&
              e.phaseIndex === payload.phaseIndex
          );

          if (placeholderIdx >= 0) {
            const prev = transcript[placeholderIdx]!;
            transcript[placeholderIdx] = {
              speakerAgentId: prev.speakerAgentId,
              speakerDisplayName: prev.speakerDisplayName,
              content,
              phaseIndex: prev.phaseIndex,
              phaseName: prev.phaseName,
              type: closedType ?? prev.type,
              timestamp: new Date().toISOString(),
              errorReason: prev.errorReason,
              targetAgentId: prev.targetAgentId,
            };
          } else {
            // No placeholder found — append directly
            transcript.push({
              speakerAgentId: payload.agentId,
              speakerDisplayName: payload.displayName,
              content,
              phaseIndex: payload.phaseIndex,
              phaseName: payload.phaseName,
              type: closedType ?? mapPhaseToEntryType(s.currentPhase?.type),
              timestamp: new Date().toISOString(),
              errorReason: null,
              targetAgentId: null,
            });
          }

          // Track task completion during EXECUTE phase
          let newTasksInProgress2 = s.tasksInProgress;
          let newTasksCompleted = s.tasksCompleted;
          if (s.currentPhase?.type === "EXECUTE" && s.taskPlan) {
            // Match by display name (see speaker_start above).
            const agentTask = s.taskPlan.find(
              (t) =>
                t.assignedTo === payload.displayName &&
                s.tasksInProgress.has(t.id)
            );
            if (agentTask) {
              newTasksInProgress2 = new Set(s.tasksInProgress);
              newTasksInProgress2.delete(agentTask.id);
              newTasksCompleted = new Set(s.tasksCompleted);
              newTasksCompleted.add(agentTask.id);
            }
          }

          return {
            ...s,
            activeSpeakers: newSpeakers,
            memberToolCalls: withoutToolCall(s.memberToolCalls, payload.agentId),
            transcript,
            tasksInProgress: newTasksInProgress2,
            tasksCompleted: newTasksCompleted,
          };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse speaker_complete event:', e);
      }
      return false;
    }

    case "task_plan_created": {
      try {
        const payload: TaskPlanCreatedPayload = JSON.parse(event.data);
        setState((s) => ({
          ...s,
          taskPlan: payload.tasks,
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse task_plan_created event:', e);
      }
      return false;
    }

    case "task_verified": {
      try {
        const payload: TaskVerifiedPayload = JSON.parse(event.data);
        setState((s) => {
          const newVerifications = new Map(s.taskVerifications);
          newVerifications.set(payload.taskId, {
            passed: payload.passed,
            feedback: payload.feedback,
          });
          return { ...s, taskVerifications: newVerifications };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse task_verified event:', e);
      }
      return false;
    }

    case "convergence_checked": {
      try {
        const payload: ConvergenceCheckedPayload = JSON.parse(event.data);
        setState((s) => {
          const next = new Map(s.convergence);
          const prev = next.get(payload.phaseIndex);
          next.set(payload.phaseIndex, {
            phaseIndex: payload.phaseIndex,
            phaseName: payload.phaseName,
            repeat: payload.repeat,
            // -1 is the backend's "no judge ran" sentinel (everyone abstained, or
            // the judge's answer could not be parsed). Surfacing it as a score
            // would render "-1.00 agreement". A non-number is treated the same
            // way rather than stored, so the field's `number | null` holds.
            agreementScore:
              typeof payload.agreementScore === "number" && payload.agreementScore >= 0
                ? payload.agreementScore
                : null,
            converged: payload.converged,
            // Only convergence_reached knows the skipped count, and it is
            // terminal for the phase — so anything already here belongs to this
            // phase and is worth carrying rather than resetting.
            repeatsSkipped: prev?.repeatsSkipped ?? null,
            reason: payload.reason,
          });
          return { ...s, convergence: next };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse convergence_checked event:', e);
      }
      return false;
    }

    case "convergence_reached": {
      try {
        const payload: ConvergenceReachedPayload = JSON.parse(event.data);
        setState((s) => {
          const next = new Map(s.convergence);
          const prev = next.get(payload.phaseIndex);
          next.set(payload.phaseIndex, {
            phaseIndex: payload.phaseIndex,
            phaseName: payload.phaseName,
            repeat: payload.repeat,
            agreementScore: prev?.agreementScore ?? null,
            converged: true,
            repeatsSkipped: payload.repeatsSkipped,
            reason: payload.reason,
          });
          return { ...s, convergence: next };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse convergence_reached event:', e);
      }
      return false;
    }

    case "decision_reached": {
      try {
        const payload: DecisionReachedPayload = JSON.parse(event.data);
        // Guard the shape: this drives a card that reads `.dissents.length` and
        // `.tally`, and a malformed payload should cost the card, not the stream.
        const decision = payload?.decision;
        if (!decision || typeof decision !== "object") {
          console.warn('[SSE] decision_reached carried no decision');
          return false;
        }
        const normalized: DecisionRecord = {
          ...decision,
          dissents: Array.isArray(decision.dissents) ? decision.dissents : [],
        };
        setState((s) => ({ ...s, decision: normalized }));
      } catch (e) {
        console.warn('[SSE] Failed to parse decision_reached event:', e);
      }
      return false;
    }

    case "phase_complete": {
      try {
        const payload: PhaseCompletePayload = JSON.parse(event.data);
        // A phase that is over has no one left typing in it. A backend that
        // predates `speaker_complete.outcome` sent no completion at all for a
        // PARALLEL member released by the batch deadline, so its placeholder
        // stayed open — a typing indicator for the rest of the discussion.
        setState((s) => ({
          ...s,
          activeSpeakers: new Set(),
          transcript: closePlaceholders(
            s.transcript,
            (e) => typeof payload?.phaseIndex !== "number" || e.phaseIndex === payload.phaseIndex,
          ),
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse phase_complete event:', e);
      }
      return false;
    }

    case "synthesis_start": {
      setState((s) => ({
        ...s,
        state: "SYNTHESIZING",
      }));
      return false;
    }

    case "group_complete": {
      try {
        const payload: GroupCompletePayload = JSON.parse(event.data);
        // Honour the state the backend put on the event rather than assuming
        // COMPLETED. `group_complete` is the terminal notification for every
        // outcome that ends a run, and a HITL rejection ends it as REJECTED —
        // rendering that as "Completed" for the seconds before the persisted
        // conversation loads says the opposite of what happened. COMPLETED
        // remains the fallback for a payload that carries no state.
        setState((s) => ({
          ...s,
          ...settleTerminal(s),
          isStreaming: false,
          cancelRequested: false,
          state: payload.state ?? "COMPLETED",
          synthesizedAnswer: payload.synthesizedAnswer,
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse group_complete event:', e);
        setState((s) => ({
          ...s,
          ...settleTerminal(s),
          isStreaming: false,
          cancelRequested: false,
          state: "COMPLETED",
        }));
      }
      return true;
    }

    // "group_error" is the generic terminal failure event. It also carries the
    // approve/stream endpoint's expected resume rejections (409 concurrent
    // decision, 400 invalid taskApprovals/note); the backend emits those as
    // "group_error", never a bare "error" (EDDI issue #36).
    case "group_error": {
      let errorMsg = "Unknown error";
      try {
        const payload = JSON.parse(event.data);
        errorMsg = payload.error || payload.message || errorMsg;
      } catch (e) {
        console.warn('[SSE] Failed to parse error event:', e);
        errorMsg = event.data || errorMsg;
      }
      // Config-drift aborts leave the discussion AWAITING_APPROVAL on the
      // backend (the pause is restored) — classify it so the UI can guide the
      // user to fix the config and re-approve rather than treat it as terminal.
      const configDrift = /config changed while paused|fix the config and retry/i.test(errorMsg);
      setState((s) => ({
        ...s,
        ...settleTerminal(s),
        isStreaming: false,
        cancelRequested: false,
        state: "FAILED",
        error: errorMsg,
        errorKind: configDrift ? "config_drift" : "generic",
      }));
      return true;
    }

    case "awaiting_approval": {
      try {
        const payload = JSON.parse(event.data) as {
          phaseIndex: number;
          phaseName: string;
          reason: string;
          granularity: string;
        };
        setState((s) => ({
          ...s,
          state: "AWAITING_APPROVAL" as GroupConversationState,
          hitlPause: {
            phaseIndex: payload.phaseIndex,
            phaseName: payload.phaseName,
            reason: payload.reason,
            granularity: payload.granularity,
          },
          ...settleTerminal(s),
          isStreaming: false,
          cancelRequested: false,
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse awaiting_approval event:', e);
      }
      return true;
    }

    case "human_input_requested": {
      try {
        const payload: HumanInputRequestedPayload = JSON.parse(event.data);
        setState((s) => ({
          ...s,
          state: "AWAITING_HUMAN_INPUT" as GroupConversationState,
          humanInputRequest: {
            memberId: payload.memberId,
            displayName: payload.displayName,
            phaseIndex: payload.phaseIndex,
            phaseName: payload.phaseName,
          },
          ...settleTerminal(s),
          isStreaming: false,
          cancelRequested: false,
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse human_input_requested event:', e);
      }
      return true;
    }

    case "hitl_resume": {
      try {
        const payload = JSON.parse(event.data) as {
          verdict: string;
          note?: string;
          decidedBy?: string;
        };
        setState((s) => ({
          ...s,
          state: "IN_PROGRESS" as GroupConversationState,
          hitlResume: {
            verdict: payload.verdict,
            note: payload.note,
            decidedBy: payload.decidedBy,
          },
          hitlPause: null,
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse hitl_resume event:', e);
      }
      return false;
    }

    case "cancelled": {
      try {
        const payload = JSON.parse(event.data) as {
          reason?: string;
          cancelledBy?: string;
        };
        setState((s) => ({
          ...s,
          ...settleTerminal(s),
          state: "CANCELLED" as GroupConversationState,
          cancelInfo: {
            reason: payload.reason,
            cancelledBy: payload.cancelledBy,
          },
          isStreaming: false,
          cancelRequested: false,
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse cancelled event:', e);
      }
      return true;
    }

    case "member_pause_skipped": {
      // A member agent's own turn paused for approval (unsupported in a group);
      // the backend records it SKIPPED. Promote the live placeholder to a
      // SKIPPED-with-reason card so the live view matches the reloaded transcript
      // (otherwise the null-content placeholder renders as a bare "No response").
      try {
        const payload = JSON.parse(event.data) as {
          agentId: string;
          displayName: string;
          phaseIndex: number;
          phaseName: string;
          reason: string;
        };
        setState((s) => {
          const newSpeakers = new Set(s.activeSpeakers);
          newSpeakers.delete(payload.agentId);
          const transcript = [...s.transcript];
          const idx = transcript.findIndex(
            (e) =>
              e.speakerAgentId === payload.agentId &&
              isOpenPlaceholder(e) &&
              e.phaseIndex === payload.phaseIndex,
          );
          if (idx >= 0) {
            const prev = transcript[idx]!;
            transcript[idx] = {
              ...prev,
              type: "SKIPPED" as TranscriptEntryType,
              errorReason: payload.reason,
            };
          }
          return {
            ...s,
            activeSpeakers: newSpeakers,
            memberToolCalls: withoutToolCall(s.memberToolCalls, payload.agentId),
            transcript,
          };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse member_pause_skipped event:', e);
      }
      return false;
    }

    case "retro_recorded": {
      try {
        const payload: RetroRecordedPayload = JSON.parse(event.data);
        setState((s) => ({ ...s, retroRecorded: [...s.retroRecorded, payload] }));
      } catch (e) {
        console.warn('[SSE] Failed to parse retro_recorded event:', e);
      }
      return false;
    }

    case "artifact_updated": {
      try {
        const payload: ArtifactUpdatedPayload = JSON.parse(event.data);
        setState((s) => ({ ...s, artifactUpdates: [...s.artifactUpdates, payload] }));
      } catch (e) {
        console.warn('[SSE] Failed to parse artifact_updated event:', e);
      }
      return false;
    }

    case "cost_updated": {
      try {
        const payload: CostUpdatedPayload = JSON.parse(event.data);
        if (typeof payload.attributionKey !== "string" || !Number.isFinite(payload.attributedCost)) {
          return false;
        }
        setState((s) => {
          const next = new Map(s.memberCosts);
          // set, never add: the value is cumulative for this key, so a frame
          // redelivered after a reconnect must be idempotent.
          next.set(payload.attributionKey, payload.attributedCost);
          return { ...s, memberCosts: next };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse cost_updated event:', e);
      }
      return false;
    }

    case "stance_updated": {
      try {
        const payload: StanceUpdatedPayload = JSON.parse(event.data);
        if (typeof payload.agentId !== "string" || typeof payload.stance !== "string") {
          return false;
        }
        setState((s) => {
          const next = new Map(s.stances);
          next.set(payload.agentId, payload);
          return { ...s, stances: next };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse stance_updated event:', e);
      }
      return false;
    }

    case "member_tool_call": {
      try {
        const payload: MemberToolCallPayload = JSON.parse(event.data);
        if (typeof payload.agentId !== "string" || typeof payload.tool !== "string") {
          return false;
        }
        setState((s) => {
          // Late frame: the member already finished (a tool event can trail the
          // speaker_complete that closed its turn) — showing "using X" on a
          // member nobody is waiting for would never clear.
          if (!s.activeSpeakers.has(payload.agentId)) return s;
          const next = new Map(s.memberToolCalls);
          next.set(payload.agentId, payload.tool);
          return { ...s, memberToolCalls: next };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse member_tool_call event:', e);
      }
      return false;
    }

    default:
      return false;
  }
}

// ─── Helpers ────────────────────────────────────────────────────

/** `calls` without `agentId` — the same instance when nothing changes. */
function withoutToolCall(calls: Map<string, string> | undefined, agentId: string): Map<string, string> {
  if (!calls?.has(agentId)) return calls ?? new Map();
  const next = new Map(calls);
  next.delete(agentId);
  return next;
}

/**
 * `speaker_complete.outcome` as one of the three reasons a turn can produce
 * nothing, or null for an ordinary contribution. Anything else — an absent
 * field from an older backend, or a value a newer one adds — reads as a
 * contribution, which then closes as SKIPPED if it carried no content.
 */
function noContentOutcome(outcome: unknown): SpeakerCompleteOutcome | null {
  return outcome === "TIMEOUT" || outcome === "SKIPPED" || outcome === "ERROR" ? outcome : null;
}

/** Map phase type to the TranscriptEntryType used in entries */
function mapPhaseToEntryType(phaseType?: string): TranscriptEntryType {
  switch (phaseType) {
    case "OPINION":
      return "OPINION";
    case "CRITIQUE":
      return "CRITIQUE";
    case "REVISION":
      return "REVISION";
    case "CHALLENGE":
      return "CHALLENGE";
    case "DEFENSE":
      return "DEFENSE";
    case "ARGUE":
      return "ARGUMENT";
    case "REBUTTAL":
      return "REBUTTAL";
    case "SYNTHESIS":
      return "SYNTHESIS";
    case "PLAN":
      return "PLAN";
    case "EXECUTE":
      return "TASK_RESULT";
    case "VERIFY":
      return "VERIFICATION";
    default:
      return "OPINION";
  }
}
