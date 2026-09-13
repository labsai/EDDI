import type { TranscriptEntryType } from "@/lib/api/groups";

/**
 * Readers for the four group phases whose transcript entry carries the member's
 * raw JSON reply rather than prose.
 *
 * Most phases store what the model said. Four do not: EDDI hands the member a
 * JSON contract and stores the answer verbatim, so `TranscriptEntry.content` is
 * a JSON document —
 *
 * | Entry      | Produced by                | Contract                                                              |
 * |------------|----------------------------|-----------------------------------------------------------------------|
 * | `VOTE`     | `VoteTallyEngine`          | `{vote \| votes, confidence, statement}`                               |
 * | `BID`      | `TaskBidEngine`            | `{bids: [{subject, confidence, estimatedComplexity, rationale}]}`      |
 * | `BARGAIN`  | `NegotiationEngine`        | `{accept, proposal: {terms}, concessions: [{gaveUp, inReturnFor}]}`    |
 * | `RETRO`    | `RetroEngine`              | `{lessons: [{lesson, context}]}`                                       |
 *
 * Everything else EDDI writes is prose: a `PROPOSAL` turn's whole body is the
 * terms, `CONVERGENCE` stores the judge's reason, `FACILITATION` stores a
 * sentence, and an `ERROR` entry carries its text in `errorReason` with a null
 * body. Those need no reader here.
 *
 * Parsing is deliberately lenient in the same way the backend's own three-tier
 * parse is: the model is asked for "ONLY this JSON" and does not always comply.
 * A body this module cannot read returns `null`, and the caller falls back to
 * the generic renderer in `group-utils` — which no longer prints raw JSON
 * either, so an unreadable turn degrades rather than leaking a blob.
 */

/** Entry types whose stored content is a JSON contract rather than prose. */
export const STRUCTURED_ENTRY_TYPES = ["VOTE", "BID", "BARGAIN", "RETRO"] as const;

export type StructuredEntryType = (typeof STRUCTURED_ENTRY_TYPES)[number];

export function isStructuredEntryType(
  type: TranscriptEntryType | string | null | undefined,
): type is StructuredEntryType {
  return (STRUCTURED_ENTRY_TYPES as readonly string[]).includes(String(type));
}

// ─── Shapes ──────────────────────────────────────────────────────

export interface VotePayload {
  kind: "VOTE";
  /** One option for a single-choice ballot, several for an APPROVAL ballot. */
  options: string[];
  /** Clamped to 0..1 by the backend; absent when the model omitted it. */
  confidence: number | null;
  statement: string | null;
}

export interface BidEntry {
  subject: string;
  confidence: number | null;
  /** The contract offers XS/S/M/L, but the model is not held to it. */
  estimatedComplexity: string | null;
  rationale: string | null;
}

export interface BidPayload {
  kind: "BID";
  bids: BidEntry[];
}

export interface Concession {
  gaveUp: string;
  inReturnFor: string;
}

export interface BargainPayload {
  kind: "BARGAIN";
  /** Id of the proposal this turn signs up to, when it accepts one. */
  accept: string | null;
  /** Terms of a counter-proposal, which supersedes an accept in the same turn. */
  proposalTerms: string | null;
  concessions: Concession[];
}

export interface Lesson {
  lesson: string;
  context: string | null;
}

export interface RetroPayload {
  kind: "RETRO";
  lessons: Lesson[];
}

export type StructuredPayload = VotePayload | BidPayload | BargainPayload | RetroPayload;

// ─── JSON access helpers ─────────────────────────────────────────

/**
 * The first balanced JSON object in `content`, parsed.
 *
 * Mirrors the backend's tier 1 + tier 2: try the whole body, then the first
 * `{…}` embedded in prose or a markdown fence. Tier 3 (give up) is `null`.
 * Brace counting is string-aware, so a `}` inside a quoted value does not close
 * the object early — the naive `lastIndexOf("}")` truncates
 * `{"statement": "we agreed }"}` into something unparseable.
 */
function readJson(content: string | null | undefined): Record<string, unknown> | null {
  if (!content) return null;
  const body = content.trim();
  if (!body) return null;

  const direct = tryParseObject(body);
  if (direct) return direct;

  const start = body.indexOf("{");
  if (start < 0) return null;
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < body.length; i++) {
    const ch = body[i]!;
    if (escaped) {
      escaped = false;
      continue;
    }
    if (ch === "\\") {
      if (inString) escaped = true;
      continue;
    }
    if (ch === '"') {
      inString = !inString;
      continue;
    }
    if (inString) continue;
    if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (depth === 0) return tryParseObject(body.slice(start, i + 1));
    }
  }
  return null;
}

function tryParseObject(text: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(text);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

function str(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

/** A confidence, clamped to 0..1 the way the backend clamps it. */
function confidence(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  return Math.max(0, Math.min(1, value));
}

function objects(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) return [];
  return value.filter(
    (item): item is Record<string, unknown> =>
      !!item && typeof item === "object" && !Array.isArray(item),
  );
}

// ─── Readers ─────────────────────────────────────────────────────

export function parseVotePayload(content: string | null | undefined): VotePayload | null {
  const node = readJson(content);
  if (!node) return null;

  // A set, not a list: an APPROVAL ballot that repeats an option
  // (`{"votes": ["A", "A"]}`) is one selection said twice, and rendering it
  // twice also gives two badges the same React key. Deduplicating only the
  // `vote`/`votes` overlap missed the repeat inside `votes` itself.
  const seen = new Set<string>();
  if (Array.isArray(node.votes)) {
    for (const vote of node.votes) {
      const option = str(vote);
      if (option) seen.add(option);
    }
  }
  const single = str(node.vote);
  if (single) seen.add(single);
  const options = [...seen];

  const statement = str(node.statement);
  // A ballot with no option AND no statement is not a ballot — most likely an
  // object that merely happens to sit on a VOTE entry (an abstention written in
  // prose, say). Handing it back would render an empty card.
  if (options.length === 0 && !statement) return null;

  return { kind: "VOTE", options, confidence: confidence(node.confidence), statement };
}

export function parseBidPayload(content: string | null | undefined): BidPayload | null {
  const node = readJson(content);
  if (!node) return null;

  const bids: BidEntry[] = [];
  for (const bid of objects(node.bids)) {
    // The backend drops a bid with no subject (it cannot be matched to a task),
    // so showing one would describe work nobody is being offered.
    const subject = str(bid.subject);
    if (!subject) continue;
    bids.push({
      subject,
      confidence: confidence(bid.confidence),
      estimatedComplexity: str(bid.estimatedComplexity),
      rationale: str(bid.rationale),
    });
  }
  // An empty `bids` array is a real answer — "I am bidding on nothing" — and the
  // card says so, which is why this returns a payload rather than null.
  return Array.isArray(node.bids) ? { kind: "BID", bids } : null;
}

export function parseBargainPayload(content: string | null | undefined): BargainPayload | null {
  const node = readJson(content);
  if (!node) return null;

  const proposal = node.proposal;
  const proposalTerms =
    proposal && typeof proposal === "object" && !Array.isArray(proposal)
      ? str((proposal as Record<string, unknown>).terms)
      : null;

  const concessions: Concession[] = [];
  for (const entry of objects(node.concessions)) {
    const gaveUp = str(entry.gaveUp);
    const inReturnFor = str(entry.inReturnFor);
    // The backend's rule is that a concession naming no return is not recorded,
    // so a half-filled one is not shown either.
    if (gaveUp && inReturnFor) concessions.push({ gaveUp, inReturnFor });
  }

  const accept = str(node.accept);
  if (!accept && !proposalTerms && concessions.length === 0) return null;
  return { kind: "BARGAIN", accept, proposalTerms, concessions };
}

export function parseRetroPayload(content: string | null | undefined): RetroPayload | null {
  const node = readJson(content);
  if (!node || !Array.isArray(node.lessons)) return null;

  const lessons: Lesson[] = [];
  for (const entry of objects(node.lessons)) {
    const lesson = str(entry.lesson);
    if (!lesson) continue;
    lessons.push({ lesson, context: str(entry.context) });
  }
  return lessons.length > 0 ? { kind: "RETRO", lessons } : null;
}

/**
 * The typed reading of one transcript entry, or `null` when this entry type has
 * no contract or its body does not honour the one it has.
 */
export function parseStructuredPayload(
  type: TranscriptEntryType | string | null | undefined,
  content: string | null | undefined,
): StructuredPayload | null {
  switch (type) {
    case "VOTE":
      return parseVotePayload(content);
    case "BID":
      return parseBidPayload(content);
    case "BARGAIN":
      return parseBargainPayload(content);
    case "RETRO":
      return parseRetroPayload(content);
    default:
      return null;
  }
}
