import type { TaskDefinition, TranscriptEntryType } from "@/lib/api/groups";
import {
  isAgentFailurePlaceholder,
  parseEmojiVerification,
  parseStructuredItems,
  parseTranscriptContent,
  stripLoneCodeFence,
  type StructuredItem,
} from "@/components/groups/group-utils";
import {
  detectStructuredPayload,
  parseStructuredPayload,
  type StructuredPayload,
} from "@/lib/group-payloads";

/**
 * What one group-discussion message is once read — the single answer to "how
 * should this body be shown" that every surface renders from.
 *
 * The Manager's `AgentResponseCard`, the Workforce board's `AdvisorResponseCard`,
 * the history viewer's `AgentEntryCard`, the markdown export, the "Ask more"
 * hand-off and the 1:1 chat each used to run their own subset of these readers,
 * and they drifted: a task plan the Manager showed as a numbered list reached
 * the board as a ```json block, and a bargaining move's reasoning was dropped
 * everywhere. Reading happens here once; surfaces only decide how a kind looks.
 */
export type EntryBody =
  /** A ballot, bid sheet, bargaining move or retro harvest. */
  | { kind: "payload"; payload: StructuredPayload }
  /** A task plan — or a pass/fail sheet when `verification`. */
  | { kind: "items"; items: StructuredItem[]; verification: boolean }
  /** EDDI's placeholder for a member whose own conversation errored. */
  | { kind: "failed" }
  /** Prose, already unwrapped from any response envelope or verdict JSON. */
  | { kind: "markdown"; text: string }
  /** A member's "PASS" that the backend stored as the literal word — see `isPassToken`. */
  | { kind: "abstained" }
  | { kind: "empty" };

export interface ReadEntryOptions {
  /** A pre-configured plan's tasks, which its one-line PLAN summary stands for. */
  preConfiguredTasks?: TaskDefinition[];
  /** agentId → display name, so a planned task names its assignee, not their id. */
  memberNames?: Record<string, string>;
}

/**
 * EDDI's abstention token, read exactly as `AbstentionDetector.isAbstention`
 * reads it: trimmed, case-insensitive, at most one trailing "." or "!".
 *
 * The backend converts an agent's PASS into an ABSTAINED entry, but a HUMAN
 * member's turn never goes through that detection, so a director who passes is
 * stored as an OPINION whose whole body is "PASS" — which rendered as though
 * that were their position.
 */
function isPassToken(content: string | null | undefined): boolean {
  if (!content) return false;
  let token = content.trim();
  if (token.length > 1 && (token.endsWith(".") || token.endsWith("!"))) {
    token = token.slice(0, -1).trimEnd();
  }
  return token.toUpperCase() === "PASS";
}

/**
 * Entry types where "PASS" is an answer, not an abstention: detection is off for
 * task phases (it is a natural verification verdict), and the contract and
 * synthesis entries are never member turns that could abstain.
 */
const PASS_IS_CONTENT = new Set(["PLAN", "TASK_RESULT", "VERIFICATION", "VOTE", "BID", "BARGAIN", "RETRO", "SYNTHESIS"]);

function withMemberNames(items: StructuredItem[], names: Record<string, string> | undefined): StructuredItem[] {
  if (!names) return items;
  return items.map((item) =>
    item.assignedTo && names[item.assignedTo] ? { ...item, assignedTo: names[item.assignedTo] } : item,
  );
}

/** Whether a body renders through the shared structured view rather than as prose. */
export function isStructuredBody(
  body: EntryBody,
): body is Extract<EntryBody, { kind: "payload" | "items" | "failed" | "abstained" }> {
  return body.kind === "payload" || body.kind === "items" || body.kind === "failed" || body.kind === "abstained";
}

function itemsBody(items: StructuredItem[], verification: boolean): EntryBody {
  return { kind: "items", items, verification: verification || items.some((i) => i.passed !== undefined) };
}

/** A transcript entry's body. */
export function readEntryBody(
  entry: { type?: TranscriptEntryType | string | null; content: string | null | undefined },
  { preConfiguredTasks, memberNames }: ReadEntryOptions = {},
): EntryBody {
  const { type, content } = entry;

  // The four JSON contracts first: the array reader below would match a BID's
  // `bids` on `subject` and keep only the subjects.
  const payload = parseStructuredPayload(type, content);
  if (payload) return { kind: "payload", payload };
  if (isAgentFailurePlaceholder(content)) return { kind: "failed" };
  if (!PASS_IS_CONTENT.has(String(type)) && isPassToken(content)) return { kind: "abstained" };

  const text = content ? parseTranscriptContent(content) : "";
  const isVerification = type === "VERIFICATION";

  // No type gate on the array reader: a CUSTOM style can put a planner's task
  // list on any phase, and the Manager has always read it wherever it landed.
  let items = parseStructuredItems(content) ?? parseStructuredItems(text);
  // Current backends pre-format verification as ✅/❌ lines rather than JSON.
  if (!items && isVerification) {
    items = parseEmojiVerification(content ?? "") ?? parseEmojiVerification(text);
  }
  // A pre-configured plan is recorded as a one-line summary; the tasks live in
  // the group's config.
  if (!items && type === "PLAN" && preConfiguredTasks && preConfiguredTasks.length > 0) {
    items = preConfiguredTasks.map((task) => ({
      subject: task.subject,
      description: task.description,
      // "ALL" is the config's "anyone may take it" placeholder — shown as an
      // assignee it reads as a member called ALL. Who actually got the task
      // (by role or by bid) is the task board's to show.
      assignedTo: task.assignToRole && task.assignToRole.toUpperCase() !== "ALL" ? task.assignToRole : undefined,
      priority: task.priority,
    }));
  }
  if (items) return itemsBody(withMemberNames(items, memberNames), isVerification);

  return text.trim() ? { kind: "markdown", text } : { kind: "empty" };
}

/**
 * A 1:1 chat reply's body, or `null` for "render it as markdown".
 *
 * A chat message has no entry type, so only a reply that IS one of the
 * contracts — the whole body, bare or fenced — is read as one. That is the
 * shape a member agent's own conversation holds (a ballot, a bid sheet, a
 * planner's task list); an agent answering a person in prose that happens to
 * quote JSON is still answering in prose.
 */
export function readMessageBody(content: string | null | undefined): EntryBody | null {
  if (!content) return null;
  if (isAgentFailurePlaceholder(content)) return { kind: "failed" };

  const payload = detectStructuredPayload(content);
  if (payload) return { kind: "payload", payload };

  const items = parseStructuredItems(content, { wholeBody: true });
  if (items) return itemsBody(items, false);

  // The two other JSON replies EDDI asks a group's helpers for — a convergence
  // judge's score and a facilitator's move — read as a labelled list. Any other
  // JSON is left exactly as the agent wrote it: an agent built to answer in
  // JSON is answering in JSON, and the chat is where its author checks that.
  const body = stripLoneCodeFence(content).trim();
  if (!body.startsWith("{")) return null;
  let node: unknown;
  try {
    node = JSON.parse(body);
  } catch {
    return null;
  }
  if (!node || typeof node !== "object" || Array.isArray(node)) return null;
  const isHelperReply = "agreementScore" in node || ("move" in node && "reason" in node);
  if (!isHelperReply) return null;
  const text = parseTranscriptContent(body);
  return text.trim() ? { kind: "markdown", text } : { kind: "empty" };
}

/**
 * A translator narrowed to what serialization needs. Matches i18next's
 * `t(key, { defaultValue, ...options })` once wrapped by the caller.
 */
export type BodyT = (key: string, fallback: string, options?: Record<string, unknown>) => string;

/** `{{name}}` interpolation for a caller that has no i18next instance. */
export function interpolateFallback(fallback: string, options?: Record<string, unknown>): string {
  if (!options) return fallback;
  return fallback.replace(/\{\{\s*(\w+)\s*\}\}/g, (whole, name: string) =>
    name in options ? String(options[name]) : whole,
  );
}

function confidenceText(t: BodyT, value: number | null): string | null {
  return value === null
    ? null
    : t("groups.payload.confidence", "{{percent}}% confident", { percent: Math.round(value * 100) });
}

function payloadToMarkdown(payload: StructuredPayload, t: BodyT): string {
  const blocks: string[] = [];
  switch (payload.kind) {
    case "VOTE": {
      const confidence = confidenceText(t, payload.confidence);
      blocks.push(
        payload.options.length > 0
          ? `**${t("groups.payload.ballot", "Ballot")}:** ${payload.options.join(", ")}${confidence ? ` (${confidence})` : ""}`
          : `_${t("groups.payload.noOption", "No option was named, so this ballot does not count towards the tally.")}_`,
      );
      if (payload.statement) blocks.push(payload.statement);
      break;
    }
    case "BID": {
      if (payload.bids.length === 0) {
        blocks.push(`_${t("groups.payload.noBids", "Did not bid on any task.")}_`);
        break;
      }
      blocks.push(
        payload.bids
          .map((bid) => {
            const detail = [bid.estimatedComplexity, confidenceText(t, bid.confidence)].filter(Boolean).join(", ");
            return `- **${bid.subject}**${detail ? ` (${detail})` : ""}${bid.rationale ? ` — ${bid.rationale}` : ""}`;
          })
          .join("\n"),
      );
      break;
    }
    case "BARGAIN": {
      if (payload.proposalTerms) {
        blocks.push(`**${t("groups.payload.counterProposal", "Counter-proposal")}:** ${payload.proposalTerms}`);
      }
      if (payload.accept) {
        blocks.push(
          payload.proposalTerms
            ? t("groups.payload.acceptSuperseded", "Also accepted {{proposal}}, which the counter-proposal above supersedes.", { proposal: payload.accept })
            : t("groups.payload.accepted", "Accepted proposal {{proposal}}.", { proposal: payload.accept }),
        );
      }
      if (payload.concessions.length > 0) {
        blocks.push(
          payload.concessions
            .map((c) => `- ${c.gaveUp} → ${t("groups.payload.inReturnFor", "in return for {{received}}", { received: c.inReturnFor })}`)
            .join("\n"),
        );
      }
      if (payload.reasoning) blocks.push(payload.reasoning);
      break;
    }
    case "RETRO":
      blocks.push(
        payload.lessons.map((l) => `- ${l.lesson}${l.context ? ` — _${l.context}_` : ""}`).join("\n"),
      );
      break;
  }
  return blocks.join("\n\n");
}

function itemsToMarkdown(items: StructuredItem[]): string {
  return items
    .map((item, index) => {
      if (item.passed !== undefined) {
        return `- ${item.passed ? "✅" : "❌"} **${item.subject}**${item.feedback ? `: ${item.feedback}` : ""}`;
      }
      const assignee = item.assignedTo ? ` (${item.assignedTo})` : "";
      return `${index + 1}. **${item.subject}**${item.description ? ` — ${item.description}` : ""}${assignee}`;
    })
    .join("\n");
}

/** A body as markdown — for the export, the clipboard and the "Ask more" hand-off. */
export function entryBodyToMarkdown(body: EntryBody, t: BodyT): string {
  switch (body.kind) {
    case "markdown":
      return body.text;
    case "empty":
      return "";
    case "failed":
      return `_${t("groups.agentFailedOutput", "This agent failed to produce a response.")}_`;
    case "abstained":
      return `_${t("groups.abstainedBody", "Declined to add anything new this round.")}_`;
    case "items":
      return itemsToMarkdown(body.items);
    case "payload":
      return payloadToMarkdown(body.payload, t);
  }
}
