import { useMemo } from "react";
import type { ConvergenceProgress, GroupStreamState } from "./use-group-discussion-stream";
import { readEntryBody } from "@/lib/group-entry-body";
import type {
  DecisionRecord,
  DiscussionPhase,
  DiscussionStyle,
  GroupConversation,
  GroupConversationState,
  PhaseType,
  TranscriptEntry,
  TranscriptEntryType,
} from "@/lib/api/groups";

/**
 * The single normalised model the overview dashboard renders.
 *
 * Every group discussion, whatever its style, reduces to the same shape:
 * ordered **phases** × **members** × the **entries** they produced. ROUND_TABLE,
 * DEBATE, TASK_FORCE and NEGOTIATION differ in which phases exist and which
 * extra records they leave behind — not in that structure. So the dashboard
 * needs one renderer with per-style emphasis, not a renderer per style.
 *
 * ## Why this adapter exists at all
 *
 * This codebase has **three independent transcript renderers** (the Manager's
 * `DiscussionTranscript`, the Workforce board, the Workforce history viewer)
 * fed by **two different data shapes**: a live `GroupStreamState` assembled
 * from SSE frames, and a persisted `GroupConversation` fetched over REST. The
 * last time a group feature was wired into some of them and not others, a
 * DISSENT rendered as an ordinary opinion on two of the three — which is why
 * `DiscussionInsights` is deliberately one shared component.
 *
 * Normalising here rather than in each surface means the dashboard cannot
 * acquire that class of drift: there is one place that knows how a live
 * discussion differs from a reloaded one, and it is this file.
 *
 * ## Live is thinner than persisted, by construction
 *
 * Some state has no incremental SSE event and is populated **only** by the
 * single-conversation GET: `artifacts`, `negotiation`, `taskList`. Fields that
 * can be `null` on a live stream say so, and the bands that render them hide
 * themselves rather than showing an empty frame.
 */

/** How far a phase has got. */
export type PhaseStatus = "done" | "active" | "pending";

/**
 * What one member did in one phase — the matrix cell.
 *
 * Three different kinds of "nothing here", which must not collapse into one:
 *
 * - `absent` — the phase's selector never included this member (a
 *   MODERATOR-only synthesis, a `ROLE:PRO` rebuttal). Nothing was expected.
 * - `silent` — the phase DID include them (selector `ALL`), it finished, and
 *   they produced nothing: a turn lost to `maxTurns`, to a cost ceiling, or to
 *   a `SYNTHESIZE_NOW` jump. Something was expected and did not arrive.
 * - `pending` — still to come.
 *
 * Rendering all three alike would tell a reader that a debate's PRO side had
 * gone quiet during a CON-only phase, and would hide a member the run dropped.
 */
export type CellKind =
  | "spoke"
  | "dissent"
  | "failed"
  | "abstained"
  | "pending"
  | "silent"
  | "absent";

export interface DigestPhase {
  index: number;
  name: string;
  type: PhaseType | null;
  status: PhaseStatus;
  /** Members that produced at least one entry in this phase, in speaking order. */
  spokenBy: string[];
  entryCount: number;
  /** The newest convergence check for this phase (I2), when one ran. */
  convergence: ConvergenceProgress | null;
  /** From the group config, when available — a phase can run more than once. */
  repeats: number | null;
  requiresApproval: boolean;
}

/** A member's live disposition, for the roster band's status chip. */
export type MemberStatus = "speaking" | "dissented" | "failed" | "idle" | "awaiting";

export interface DigestMember {
  agentId: string;
  displayName: string;
  /** One line on where they stand, or `null` when they have not spoken yet. */
  stance: string | null;
  /**
   * `true` when `stance` is the member's own lead sentence, `false` when an LLM
   * paraphrased it. The roster marks the difference: showing a paraphrase as a
   * quote would misattribute it.
   */
  stanceIsQuote: boolean;
  turnCount: number;
  /**
   * USD attributed to this member, or `null` when unknown — which is the normal
   * outcome for an agent whose LLM config carries no prices, not an error.
   */
  cost: number | null;
  status: MemberStatus;
  /** Whether the member recorded a DISSENT at any point. */
  hasDissented: boolean;
  /** Whether any of their turns errored or was skipped. */
  hasFailed: boolean;
}

export interface DigestCell {
  kind: CellKind;
  entryCount: number;
}

/**
 * One member addressing another — the structure of the directional styles.
 *
 * PEER_REVIEW and DEVIL_ADVOCATE are *about* who critiqued or challenged whom
 * (their phases set `targetEachPeer`), and `TranscriptEntry.targetAgentId`
 * carries it on every turn. Without this the matrix could say "Security spoke
 * in Critique" but never "Security critiqued the Architect", which is the
 * content of those styles rather than a detail of them.
 */
export interface DigestInteraction {
  fromAgentId: string;
  toAgentId: string;
  /** How many turns went this way. */
  count: number;
}

/** One member's bid for one task (I18's contract-net-lite). */
export interface DigestBid {
  agentId: string;
  subject: string;
  confidence: number | null;
  estimatedComplexity: string | null;
  rationale: string | null;
}

export interface DiscussionDigest {
  /** `true` while an SSE stream is feeding this digest. */
  isLive: boolean;
  state: GroupConversationState;
  question: string | null;
  style: DiscussionStyle | null;
  round: number;
  phases: DigestPhase[];
  members: DigestMember[];
  /** `matrix[agentId][phaseIndex]`. Always dense over `members` × `phases`. */
  matrix: Record<string, DigestCell[]>;
  /** Directed member-to-member turns, strongest first. Empty for broadcast-only styles. */
  interactions: DigestInteraction[];
  /** Bids cast this round, for TASK_FORCE's contract-net phase. */
  bids: DigestBid[];
  /** How many rounds this discussion has had. `1` for most. */
  roundCount: number;
  /** Which round the bands describe — 1-based, defaults to the newest. */
  selectedRound: number;
  /** Total spend in USD, or `null` when nothing has been attributed. */
  totalCost: number | null;
  totalEntries: number;
  /** Wall-clock start, for the elapsed readout. */
  startedAt: string | null;
  /**
   * When the discussion last advanced — `lastModified` on the persisted
   * document. `null` while live, where "now" is the end.
   *
   * Needed because a finished discussion's duration is start→end, not
   * start→now: without it a completed discussion reopened a week later reports
   * a week of elapsed time.
   */
  endedAt: string | null;
  decision: DecisionRecord | null;
  synthesizedAnswer: string | null;
  /** `true` when there is genuinely nothing to draw yet. */
  isEmpty: boolean;
}

/**
 * States in which the discussion will run no further, so no phase is "now" and no
 * member is still "pending".
 *
 * `REJECTED` is here because the backend treats it as terminal ("treated as
 * FAILED everywhere that asks 'may this still run?'"). It arrived after this list
 * was first written, and the omission was silent: a rejected discussion kept the
 * phase it was rejected in pulsing as "Now" indefinitely, with its members
 * "pending". A string-union state gains members without any type error, so this
 * list is the one place to update.
 */
const TERMINAL_STATES: ReadonlySet<GroupConversationState> = new Set<GroupConversationState>([
  "COMPLETED",
  "FAILED",
  "REJECTED",
  "CANCELLED",
]);

/** Entry types that mean "this member's turn failed", not "this is their position". */
const FAILURE_TYPES: ReadonlySet<TranscriptEntryType> = new Set<TranscriptEntryType>(["ERROR", "SKIPPED"]);

/**
 * Entries that are bookkeeping rather than a member speaking. Excluded from
 * turn counts and from the matrix so a phase's grid shows contributions, not
 * machinery. QUESTION is the user's, not a member's.
 */
const NON_MEMBER_TYPES: ReadonlySet<TranscriptEntryType> = new Set<TranscriptEntryType>([
  "QUESTION",
  "CONVERGENCE",
  "FACILITATION",
]);

/**
 * Entry types a stance may be EXTRACTED from — the backend's extraction set,
 * i.e. `StanceSummaryEngine.STANCE_BEARING` minus its `JSON_CONTRACT` types.
 *
 * ABSTAINED is excluded for the same reason it is there: its content is a
 * refusal to add anything, so extracting from it would replace a member's real
 * last position with "I have nothing to add".
 *
 * Deliberately absent too: `VOTE`, `BID`, `RETRO`, `PLAN`, `TASK_RESULT` and
 * `VERIFICATION` carry a JSON contract, not prose. The lead "sentence" of a
 * ballot is `{"choice":"pgvector","confidence":0.8,` — shown to the reader as
 * that member's own words, and, being the newest entry, replacing their real
 * position after every VOTE or RETRO phase. The backend's extractor skips the
 * same set; its LLM summariser still reads them.
 */
const STANCE_BEARING: ReadonlySet<TranscriptEntryType> = new Set<TranscriptEntryType>([
  "OPINION",
  "CRITIQUE",
  "REVISION",
  "CHALLENGE",
  "DEFENSE",
  "ARGUMENT",
  "REBUTTAL",
  "DISSENT",
  "PROPOSAL",
  "BARGAIN",
  "HUMAN_INPUT",
  "FOLLOW_UP",
]);

/** Hard cap on a locally extracted stance; the backend's default for its own. */
const STANCE_MAX_CHARS = 160;

/**
 * First sentence of `content`, capped, or `null` when there is nothing usable.
 *
 * Mirrors the backend extractor closely enough to look the same in the roster,
 * including skipping a full stop that belongs to a decimal or an abbreviation
 * rather than ending a sentence.
 */
function leadSentence(content: string | null | undefined): string | null {
  if (!content) return null;
  const text = content.replace(/\s+/g, " ").trim();
  if (!text) return null;

  let sentence = text;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c !== "." && c !== "!" && c !== "?") continue;
    // "$1.50" — a decimal point, not a terminator.
    if (c === "." && /\d/.test(text[i + 1] ?? "") && /\d/.test(text[i - 1] ?? "")) continue;
    // "..." — an ellipsis.
    if (text[i + 1] === ".") continue;
    // An abbreviation ("e.g.", "Dr.") — a lone letter before the dot AND a
    // lower-case continuation after it. Without the second half, "Weigh option
    // B. Option A is worse." cut nothing, because the standalone B read as an
    // abbreviation.
    if (
      c === "." &&
      /[A-Za-z]/.test(text[i - 1] ?? "") &&
      !/[A-Za-z0-9]/.test(text[i - 2] ?? "") &&
      /[a-z]/.test(text.slice(i + 1).trimStart()[0] ?? "")
    ) {
      continue;
    }
    // A candidate with no letter in it is a list marker, not a sentence:
    // "1. We should adopt pgvector." rendered as a stance reading just "1.",
    // attributed as the member's own words. LLM replies open this way often.
    if (!/[A-Za-z]/.test(text.slice(0, i))) continue;
    const next = text.slice(i + 1).trimStart()[0];
    if (next === undefined || next !== next.toLowerCase() || !/[a-z]/i.test(next)) {
      sentence = text.slice(0, i + 1);
      break;
    }
  }
  if (sentence.length <= STANCE_MAX_CHARS) return sentence;
  const cut = sentence.slice(0, STANCE_MAX_CHARS - 1);
  const lastSpace = cut.lastIndexOf(" ");
  return `${(lastSpace > STANCE_MAX_CHARS / 2 ? cut.slice(0, lastSpace) : cut).trim()}…`;
}

/** A system row, which names no member (the cost-ceiling notice, a facilitator note). */
function isSystemEntry(entry: TranscriptEntry): boolean {
  return !entry.speakerAgentId;
}

/**
 * Whether a phase's participant selector includes a given member.
 *
 * Only `ALL` can be decided from the selector string alone — `MODERATOR` and
 * `ROLE:…` need the group's roster, which this adapter deliberately does not
 * take (the surfaces do not all have it). So anything else returns `null`,
 * meaning "unknown", and the matrix falls back to `pending` rather than
 * claiming a member is excluded. Guessing wrong in the other direction would
 * silently hide a member who really was expected to speak.
 */
function selectorIncludesEveryone(participants: string | undefined): boolean | null {
  if (!participants) return null;
  return participants.trim().toUpperCase() === "ALL" ? true : null;
}

function cellFor(
  entries: TranscriptEntry[],
  phaseStatus: PhaseStatus,
  includedInPhase: boolean | null,
  discussionEnded: boolean,
): DigestCell {
  if (entries.length > 0) {
    const failed = entries.some((e) => FAILURE_TYPES.has(e.type));
    const dissent = entries.some((e) => e.type === "DISSENT");
    const abstained = entries.every((e) => e.type === "ABSTAINED");
    return {
      kind: failed ? "failed" : dissent ? "dissent" : abstained ? "abstained" : "spoke",
      entryCount: entries.length,
    };
  }
  // Nothing from this member, so the cell says whether anything still could be.
  if (includedInPhase === false) return { kind: "absent", entryCount: 0 };
  if (phaseStatus === "done" || discussionEnded) {
    // A phase whose selector we KNOW was `ALL` expected this member and did not
    // get them — a dropped turn, not an exclusion, and "not in this phase"
    // would hide it. Where the selector is unknown, `absent` is the honest
    // answer.
    return { kind: includedInPhase === true ? "silent" : "absent", entryCount: 0 };
  }
  // Includes a phase not yet reached — the case that matters most on the rail's
  // right-hand side, and which an "active phases only" test once rendered as
  // `absent`, i.e. as "excluded" rather than "not yet".
  return { kind: "pending", entryCount: 0 };
}

interface DigestInput {
  /** The persisted document, when one has been fetched. */
  conversation?: GroupConversation | null;
  /** Live SSE state. Takes priority over `conversation` for everything it carries. */
  streamState?: GroupStreamState;
  /** The group's configured phases, so the rail can show phases not yet reached. */
  configPhases?: DiscussionPhase[] | null;
  /** agentId → display name, for surfaces with a roster but no conversation yet. */
  rosterDisplayNames?: Record<string, string>;
  style?: DiscussionStyle | null;
  /** Which round to describe, 1-based. Defaults to the newest. */
  selectedRound?: number;
}

/**
 * Builds a {@link DiscussionDigest} from whichever of the two data shapes the
 * calling surface happens to have. Memoised on its inputs.
 */
export function useDiscussionDigest(input: DigestInput): DiscussionDigest {
  const { conversation, streamState, configPhases, rosterDisplayNames, style, selectedRound } = input;
  return useMemo(
    () => buildDigest(conversation, streamState, configPhases, rosterDisplayNames, style, selectedRound),
    [conversation, streamState, configPhases, rosterDisplayNames, style, selectedRound],
  );
}

export function buildDigest(
  conversation?: GroupConversation | null,
  streamState?: GroupStreamState,
  configPhases?: DiscussionPhase[] | null,
  rosterDisplayNames?: Record<string, string>,
  style?: DiscussionStyle | null,
  selectedRound?: number,
): DiscussionDigest {
  const isLive = !!streamState?.isStreaming;
  // A live stream's transcript is the newer view while it is running; once it
  // stops, the persisted document is authoritative (it carries the rows no SSE
  // frame ever sent). Falling back by length would pick the stale one whenever
  // a reload happened mid-discussion.
  const streamTranscript = streamState?.transcript ?? [];
  const wholeTranscript: TranscriptEntry[] =
    isLive && streamTranscript.length > 0 ? streamTranscript : (conversation?.transcript ?? streamTranscript);

  // A continuation restarts `phaseIndex` at 0 (the backend's phase loop begins
  // at 0 every round), so bucketing the WHOLE transcript by phase index merges
  // rounds: round 1's turns land in round 2's cells, a round-1 ERROR marks a
  // round-2 cell "failed", and phases this round has not reached show members
  // as having already spoken in them.
  const boundaries = roundBoundaries(wholeTranscript, conversation, streamState, isLive);
  const roundCount = boundaries.length;
  // Newest round by default; a caller can ask for an earlier one.
  const selected = Math.min(Math.max(selectedRound ?? roundCount, 1), roundCount);
  // An earlier round has ended even while a later one is running. `state`,
  // `currentPhaseIndex` and the stream's convergence map all describe the
  // NEWEST round, so none of them may be read for this one.
  const historical = selected < roundCount;
  const sliceStart = boundaries[selected - 1] ?? 0;
  const sliceEnd = boundaries[selected] ?? wholeTranscript.length;
  const transcript: TranscriptEntry[] =
    sliceStart > 0 || sliceEnd < wholeTranscript.length
      ? wholeTranscript.slice(sliceStart, sliceEnd)
      : wholeTranscript;

  const state: GroupConversationState =
    (isLive ? streamState?.state : conversation?.state) ?? conversation?.state ?? streamState?.state ?? "CREATED";

  const displayNames: Record<string, string> = {
    ...(rosterDisplayNames ?? {}),
    ...(conversation?.memberDisplayNames ?? {}),
  };

  // ── Phases ──────────────────────────────────────────────────
  const currentPhaseIndex = isLive
    ? (streamState?.currentPhase?.index ?? conversation?.currentPhaseIndex ?? 0)
    : (conversation?.currentPhaseIndex ?? streamState?.currentPhase?.index ?? 0);

  // Names seen in the transcript win over config names: a facilitator can
  // diverge the runtime phase list from the configured one (I12), and the
  // transcript records what actually ran.
  const phaseNames = new Map<number, string>();
  const phaseEntries = new Map<number, TranscriptEntry[]>();
  for (const entry of transcript) {
    // The round's QUESTION belongs to no phase. The backend stores it at
    // phaseIndex 0 with the phaseName "Question", so reading it here named the
    // first phase "Question" on every reloaded discussion, and counted a phase
    // nobody had spoken in yet as started.
    if (entry.type === "QUESTION") continue;
    if (entry.phaseName && !phaseNames.has(entry.phaseIndex)) phaseNames.set(entry.phaseIndex, entry.phaseName);
    const bucket = phaseEntries.get(entry.phaseIndex);
    if (bucket) bucket.push(entry);
    else phaseEntries.set(entry.phaseIndex, [entry]);
  }

  const phaseCount = Math.max(
    configPhases?.length ?? 0,
    phaseNames.size > 0 ? Math.max(...phaseNames.keys()) + 1 : 0,
    isLive && !historical && streamState?.currentPhase ? streamState.currentPhase.index + 1 : 0,
  );

  const terminal = historical || TERMINAL_STATES.has(state);

  const phases: DigestPhase[] = [];
  for (let i = 0; i < phaseCount; i++) {
    const config = configPhases?.[i];
    const entries = phaseEntries.get(i) ?? [];
    const status: PhaseStatus = terminal
      ? entries.length > 0
        ? "done"
        : "pending"
      : i < currentPhaseIndex
        ? "done"
        : i === currentPhaseIndex && (entries.length > 0 || isLive)
          ? "active"
          : "pending";

    const spokenBy: string[] = [];
    for (const entry of entries) {
      if (isSystemEntry(entry) || NON_MEMBER_TYPES.has(entry.type)) continue;
      if (!spokenBy.includes(entry.speakerAgentId)) spokenBy.push(entry.speakerAgentId);
    }

    phases.push({
      index: i,
      name: phaseNames.get(i) ?? config?.name ?? `Phase ${i + 1}`,
      type: config?.type ?? null,
      status,
      spokenBy,
      entryCount: entries.filter((e) => !isSystemEntry(e) && !NON_MEMBER_TYPES.has(e.type)).length,
      // Only the stream knows convergence, and only for the newest round; no
      // record of an earlier round's check survives, so a past round shows none.
      convergence: historical ? null : (streamState?.convergence?.get(i) ?? null),
      repeats: config?.repeats ?? null,
      requiresApproval: config?.requiresApproval === true,
    });
  }

  // ── Members ─────────────────────────────────────────────────
  // Ordered by first contribution so the roster does not reshuffle between
  // renders; members known only from the roster are appended after.
  const memberOrder: string[] = [];
  const turnCounts = new Map<string, number>();
  const dissented = new Set<string>();
  const failed = new Set<string>();

  // Each member's newest stance-bearing contribution, for the extraction
  // fallback below.
  const newestStanceEntry = new Map<string, TranscriptEntry>();

  for (const entry of transcript) {
    if (isSystemEntry(entry) || NON_MEMBER_TYPES.has(entry.type)) continue;
    const id = entry.speakerAgentId;
    if (!memberOrder.includes(id)) memberOrder.push(id);
    if (entry.type === "DISSENT") dissented.add(id);
    if (FAILURE_TYPES.has(entry.type)) {
      failed.add(id);
      continue;
    }
    turnCounts.set(id, (turnCounts.get(id) ?? 0) + 1);
    if (entry.speakerDisplayName && !displayNames[id]) displayNames[id] = entry.speakerDisplayName;
    if (STANCE_BEARING.has(entry.type) && entry.content?.trim()) newestStanceEntry.set(id, entry);
  }
  // Every known member, not just the ones the optional roster prop names:
  // `displayNames` already merges the persisted `memberDisplayNames`, which is
  // the ONLY source the Workforce history viewer has. Reading the prop alone
  // made a member who was silent this round vanish from the roster and the
  // matrix entirely, instead of showing `silent`/`absent` cells.
  for (const id of Object.keys(displayNames)) {
    if (!memberOrder.includes(id)) memberOrder.push(id);
  }

  // Live stances win while streaming; the persisted map is the fallback and the
  // authority once the stream has stopped.
  const liveStances = streamState?.stances;
  const persistedStances = conversation?.memberStances;

  // The cost ledger and the stance maps are whole-discussion records with no
  // per-round breakdown. An earlier round must not borrow them: it showed the
  // discussion's TOTAL spend and each member's CURRENT stance as if they were
  // that round's. A past round's stance is extracted from its own turns
  // instead, and its cost is honestly unknown.
  const costs = historical ? new Map<string, number>() : memberCostsFor(conversation, streamState);

  const members: DigestMember[] = memberOrder.map((agentId) => {
    const live = historical ? undefined : liveStances?.get(agentId);
    const persisted = historical ? undefined : persistedStances?.[agentId];
    const stanceSource = isLive && live ? live : (persisted ?? live);
    // Speaking and awaiting describe NOW, which only the newest round has.
    const speaking = !historical && streamState?.activeSpeakers?.has(agentId) === true;

    // No stance supplied: extract one here from the member's newest
    // contribution. Not a nicety — the backend only writes stances at phase
    // boundaries and only from this release onwards, so WITHOUT this fallback
    // the roster reads "Has not spoken yet" for every member of every
    // discussion that already exists, and for anyone mid-phase on a live one,
    // while the matrix beside it shows their turns. It is the same lead
    // sentence the backend would have taken, so it is equally a quotation.
    const supplied = stanceSource
      ? "text" in stanceSource
        ? stanceSource.text
        : stanceSource.stance
      : null;
    const stance = supplied ?? leadSentence(newestStanceEntry.get(agentId)?.content);

    return {
      agentId,
      displayName: displayNames[agentId] ?? agentId,
      stance,
      stanceIsQuote: stanceSource ? !stanceSource.llmGenerated : true,
      turnCount: turnCounts.get(agentId) ?? 0,
      // Sum rather than look up: a member's spend can be spread over several
      // ledger keys (a nested GROUP member gets one key per child discussion).
      cost: sumCostsFor(costs, agentId),
      status: speaking
        ? "speaking"
        : !historical && state === "AWAITING_HUMAN_INPUT" && conversation?.pendingHumanInput?.memberId === agentId
          ? "awaiting"
          : failed.has(agentId)
            ? "failed"
            : dissented.has(agentId)
              ? "dissented"
              : "idle",
      hasDissented: dissented.has(agentId),
      hasFailed: failed.has(agentId),
    };
  });

  // ── Matrix ──────────────────────────────────────────────────
  const byMemberPhase = new Map<string, TranscriptEntry[]>();
  for (const entry of transcript) {
    if (isSystemEntry(entry) || NON_MEMBER_TYPES.has(entry.type)) continue;
    const key = `${entry.speakerAgentId}\u0000${entry.phaseIndex}`;
    const bucket = byMemberPhase.get(key);
    if (bucket) bucket.push(entry);
    else byMemberPhase.set(key, [entry]);
  }

  const matrix: Record<string, DigestCell[]> = {};
  for (const member of members) {
    matrix[member.agentId] = phases.map((phase) =>
      cellFor(
        byMemberPhase.get(`${member.agentId}\u0000${phase.index}`) ?? [],
        phase.status,
        selectorIncludesEveryone(configPhases?.[phase.index]?.participants),
        terminal,
      ),
    );
  }

  // Directed turns. Counted per ordered pair and sorted heaviest-first so the
  // band leads with the exchange that actually carried the phase.
  const edges = new Map<string, DigestInteraction>();
  for (const entry of transcript) {
    if (isSystemEntry(entry) || NON_MEMBER_TYPES.has(entry.type)) continue;
    if (!entry.targetAgentId || entry.targetAgentId === entry.speakerAgentId) continue;
    const key = `${entry.speakerAgentId}\u0000${entry.targetAgentId}`;
    const found = edges.get(key);
    if (found) found.count += 1;
    else
      edges.set(key, { fromAgentId: entry.speakerAgentId, toAgentId: entry.targetAgentId, count: 1 });
  }
  const interactions = [...edges.values()].sort((a, b) => b.count - a.count);

  const bids = collectBids(transcript);

  const totalCost = costs.size > 0 ? sumAll(costs) : null;
  // The same filter every phase's `entryCount` applies. Counting raw rows put
  // QUESTION, CONVERGENCE, FACILITATION and system SKIPPED entries in the
  // headline, so it disagreed with the rail directly beneath it.
  const totalTurns = transcript.filter((e) => !isSystemEntry(e) && !NON_MEMBER_TYPES.has(e.type)).length;

  return {
    isLive,
    state,
    question: currentQuestion(transcript) ?? conversation?.originalQuestion ?? null,
    style: style ?? null,
    round: conversation?.round ?? 1,
    phases,
    members,
    matrix,
    interactions,
    bids,
    roundCount,
    selectedRound: selected,
    totalCost,
    totalEntries: totalTurns,
    startedAt: conversation?.created ?? streamState?.startedAt ?? null,
    endedAt: !isLive ? (conversation?.lastModified ?? null) : null,
    // Both fields hold the NEWEST round's conclusion (a continuation clears
    // them), so an earlier round reads its own synthesis from its turns and
    // has no structured decision on record.
    decision: historical
      ? null
      : ((isLive ? streamState?.decision : conversation?.decision) ?? conversation?.decision ?? null),
    synthesizedAnswer: historical
      ? roundSynthesis(transcript)
      : ((isLive ? streamState?.synthesizedAnswer : conversation?.synthesizedAnswer) ??
        conversation?.synthesizedAnswer ??
        null),
    isEmpty: phases.length === 0 && members.length === 0,
  };
}

/**
 * Where each round's entries begin, as indices into the whole transcript.
 *
 * The authority is the boundary the backend stores for the CURRENT round
 * (`roundStartTranscriptIndex`, or the stream's `roundStartIndex`). That is
 * always correct but describes one round, which is all the bands need — the
 * round *switcher* additionally needs the earlier boundaries, and those are not
 * persisted.
 *
 * They are recovered from `QUESTION` entries, which the backend writes at the
 * start of every round and nowhere else (`GroupConversationService` for the
 * first, `GroupLifecycleOps` for each continuation, both immediately after
 * setting the stored index). Rather than trusting that invariant blindly, the
 * recovered list is **validated against the stored boundary**: its last entry
 * must be the round start the backend recorded. If it is not — a QUESTION
 * written somewhere this code does not know about, or entries filtered before
 * they reached us — the recovery is discarded and only the stored boundary is
 * used, which narrows the switcher rather than slicing the view wrongly.
 *
 * Always returns at least `[0]`, so callers can index it unconditionally.
 */
function roundBoundaries(
  transcript: TranscriptEntry[],
  conversation: GroupConversation | null | undefined,
  streamState: GroupStreamState | undefined,
  isLive: boolean,
): number[] {
  const known = isLive ? (streamState?.roundStartIndex ?? 0) : (conversation?.roundStartTranscriptIndex ?? 0);
  const safeKnown = known > 0 && known < transcript.length ? known : 0;

  const questions: number[] = [];
  for (let i = 0; i < transcript.length; i++) {
    if (transcript[i]?.type === "QUESTION") questions.push(i);
  }

  const consistent =
    questions.length > 0 && questions[0] === 0 && questions[questions.length - 1] === safeKnown;
  if (consistent) return questions;

  return safeKnown > 0 ? [0, safeKnown] : [0];
}

/**
 * Bids cast in this round, flattened across members.
 *
 * Reuses `readEntryBody` rather than re-parsing: a BID's content is the same
 * JSON contract the transcript renderers already decode, and a second parser
 * would be a second thing to keep in step with the backend.
 */
function collectBids(transcript: TranscriptEntry[]): DigestBid[] {
  const out: DigestBid[] = [];
  for (const entry of transcript) {
    if (entry.type !== "BID" || !entry.speakerAgentId) continue;
    const body = readEntryBody(entry);
    if (body.kind !== "payload" || body.payload.kind !== "BID") continue;
    for (const bid of body.payload.bids) {
      out.push({
        agentId: entry.speakerAgentId,
        subject: bid.subject,
        confidence: bid.confidence,
        estimatedComplexity: bid.estimatedComplexity,
        rationale: bid.rationale,
      });
    }
  }
  return out;
}

/** The newest SYNTHESIS turn's text in `transcript`, or null. */
function roundSynthesis(transcript: TranscriptEntry[]): string | null {
  for (let i = transcript.length - 1; i >= 0; i--) {
    const entry = transcript[i];
    if (entry?.type === "SYNTHESIS" && entry.content?.trim()) return entry.content;
  }
  return null;
}

/**
 * The question this round is answering.
 *
 * A continuation records its follow-up as a `QUESTION` entry rather than
 * rewriting `originalQuestion` (which stays the conversation's title), so a
 * round-2 overview headed by `originalQuestion` shows the first round's prompt
 * above a summary of answers to a different one. The transcript handed in is
 * already sliced to the current round, so its newest QUESTION is this round's.
 */
function currentQuestion(transcript: TranscriptEntry[]): string | null {
  for (let i = transcript.length - 1; i >= 0; i--) {
    const entry = transcript[i];
    if (entry?.type === "QUESTION" && entry.content?.trim()) return entry.content.trim();
  }
  return null;
}

/**
 * The cost ledger to read: the persisted one, with live frames laid over it per
 * key.
 *
 * Overlaid, never swapped. A stream carries only the keys it has announced
 * *this session*, so preferring it wholesale dropped everything it had not
 * re-sent — an earlier round's `system:*` keys, nested `agentId:childId` keys,
 * and every member yet to speak this round. Pressing Continue on a $4.10
 * discussion made the headline read $0.02 until the document was refetched.
 *
 * Per-key overlay is also the correct merge, because each frame carries that
 * key's *cumulative* cost: a live value is simply a newer reading of the same
 * number, not an increment to add.
 */
function memberCostsFor(
  conversation: GroupConversation | null | undefined,
  streamState: GroupStreamState | undefined,
): Map<string, number> {
  const merged = new Map<string, number>(Object.entries(conversation?.memberCosts ?? {}));
  for (const [key, value] of streamState?.memberCosts ?? []) {
    merged.set(key, value);
  }
  return merged;
}

/**
 * Sums every ledger key attributable to one member.
 *
 * Keys are not uniformly agent ids: a nested GROUP member is keyed
 * `agentId:childConversationId`, one key per child discussion. Matching the
 * prefix on a `:` boundary picks those up while excluding a different agent
 * whose id merely starts with the same characters. `system:` keys belong to no
 * member and are counted only in the total.
 */
function sumCostsFor(costs: Map<string, number>, agentId: string): number | null {
  let total = 0;
  let found = false;
  for (const [key, value] of costs) {
    if (key === agentId || key.startsWith(`${agentId}:`)) {
      total += value;
      found = true;
    }
  }
  return found ? total : null;
}

function sumAll(costs: Map<string, number>): number {
  let total = 0;
  for (const value of costs.values()) total += value;
  return total;
}
