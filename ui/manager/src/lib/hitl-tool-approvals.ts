import { isValidIsoDuration, requiresApprovalTimeout } from "./hitl-config";
import {
  MAX_PAUSE_REASON_LENGTH,
  TOOL_SOURCES,
  type ToolApprovalsConfig,
} from "./api/hitl";

/**
 * Client-side mirror of the backend tool-approval save-time validation
 * (`ToolApprovalPatterns.validate` + `HitlConfigValidation.validateToolApprovals`).
 * The single source of truth for validating a {@link ToolApprovalsConfig} in the
 * agent and per-task editors, so a bad config is caught inline instead of
 * bouncing off a save-time 400.
 *
 * Keep in sync with:
 *   ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns
 *   ai.labs.eddi.configs.hitl.HitlConfigValidation#validateToolApprovals
 */

/** Accepted source prefixes for a `source:name` pattern. */
export const KNOWN_TOOL_SOURCES: readonly string[] = TOOL_SOURCES;

const PATTERN_MAX_LENGTH = 256;
// Mirrors Java LEGAL_CHARS `[A-Za-z0-9_\-.:*]+` used with a full-string match.
const LEGAL_PATTERN_CHARS = /^[A-Za-z0-9_.:*-]+$/;

const ON_NO_PROGRESS_VALUES = ["WAIT_FOR_HUMAN", "AUTO_REJECT", "ABORT"];

/** Iterative two-row Levenshtein distance (mirrors ToolApprovalPatterns). */
export function levenshtein(a: string, b: string): number {
  const n = b.length;
  let prev: number[] = Array.from({ length: n + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const curr: number[] = new Array(n + 1);
    curr[0] = i;
    for (let j = 1; j <= n; j++) {
      const cost = a.charAt(i - 1) === b.charAt(j - 1) ? 0 : 1;
      curr[j] = Math.min((curr[j - 1] ?? 0) + 1, (prev[j] ?? 0) + 1, (prev[j - 1] ?? 0) + cost);
    }
    prev = curr;
  }
  return prev[n] ?? 0;
}

function suggestionFor(prefix: string): string {
  for (const known of KNOWN_TOOL_SOURCES) {
    if (levenshtein(prefix, known) <= 2) {
      return ` — did you mean '${known}:'?`;
    }
  }
  return "";
}

/**
 * Validate a single glob pattern. Returns an actionable error message, or
 * `null` if the pattern is valid. Mirrors `ToolApprovalPatterns.validate`.
 */
export function validateToolPattern(pattern: string): string | null {
  if (!pattern || pattern.trim().length === 0) {
    return "pattern must not be blank";
  }
  if (pattern.length > PATTERN_MAX_LENGTH) {
    return `pattern exceeds ${PATTERN_MAX_LENGTH} characters`;
  }
  if (!LEGAL_PATTERN_CHARS.test(pattern)) {
    return `pattern '${pattern}' contains illegal characters — allowed: A-Za-z0-9_-.:* (tool names never contain spaces)`;
  }
  if (pattern.startsWith(":") || pattern.endsWith(":")) {
    return `pattern '${pattern}' must not start or end with a colon — the colon separates a source prefix (e.g. 'mcp:read_*') from the tool name`;
  }
  const colon = pattern.indexOf(":");
  if (colon > 0) {
    const prefix = pattern.substring(0, colon);
    if (!prefix.includes("*") && !KNOWN_TOOL_SOURCES.includes(prefix)) {
      return `unknown tool source prefix '${prefix}:' in pattern '${pattern}'${suggestionFor(prefix)} — known sources: ${KNOWN_TOOL_SOURCES.join(", ")}`;
    }
  }
  return null;
}

/** Per-field validation errors for a tool-approvals config. Empty = valid. */
export interface ToolApprovalsErrors {
  requireApproval?: string;
  exempt?: string;
  maxPausesPerTurn?: string;
  maxAutoApprovalsPerTurn?: string;
  onNoProgress?: string;
  approvalTimeout?: string;
  pauseReason?: string;
  pendingMessage?: string;
  inGroupTurns?: string;
}

/** Validate a pattern list; returns the first error (prefixed with its index),
 *  or null when every pattern is valid and unique. */
function validatePatternList(
  patterns: string[] | null | undefined,
  field: string,
): string | null {
  if (!patterns) return null;
  const seen = new Set<string>();
  for (let i = 0; i < patterns.length; i++) {
    const pattern = patterns[i];
    if (pattern === undefined) continue;
    const err = validateToolPattern(pattern);
    if (err) return `${field}[${i}]: ${err}`;
    if (seen.has(pattern)) {
      return `duplicate pattern '${pattern}' in ${field}`;
    }
    seen.add(pattern);
  }
  return null;
}

function rangeError(
  value: number | null | undefined,
  min: number,
  max: number,
  field: string,
): string | null {
  if (value == null) return null;
  if (!Number.isInteger(value) || value < min || value > max) {
    return `${field} must be between ${min} and ${max}, got ${value}`;
  }
  return null;
}

function reasonError(value: string | null | undefined, field: string): string | null {
  if (value != null && value.length > MAX_PAUSE_REASON_LENGTH) {
    return `${field} exceeds the maximum length of ${MAX_PAUSE_REASON_LENGTH} characters`;
  }
  return null;
}

/**
 * Validate a whole {@link ToolApprovalsConfig}. Returns per-field error
 * messages; an empty object means the config is valid and would be accepted at
 * save time. Mirrors `HitlConfigValidation.validateToolApprovals`.
 */
export function validateToolApprovals(cfg: ToolApprovalsConfig): ToolApprovalsErrors {
  const errors: ToolApprovalsErrors = {};

  const require = (cfg.requireApproval ?? []).filter((p) => p.length > 0);
  const exempt = (cfg.exempt ?? []).filter((p) => p.length > 0);

  const requireErr = validatePatternList(cfg.requireApproval, "requireApproval");
  if (requireErr) errors.requireApproval = requireErr;

  const exemptErr = validatePatternList(cfg.exempt, "exempt");
  if (exemptErr) errors.exempt = exemptErr;

  // Cross-field: exempt has no effect without requireApproval patterns.
  if (!errors.exempt && exempt.length > 0 && require.length === 0) {
    errors.exempt = "exempt has no effect without requireApproval patterns";
  }
  // Cross-field: a pattern in both lists is contradictory (exempt would win).
  if (!errors.exempt) {
    const overlap = exempt.find((p) => require.includes(p));
    if (overlap) {
      errors.exempt = `pattern '${overlap}' appears in both requireApproval and exempt; exempt would win — remove one`;
    }
  }

  const pausesErr = rangeError(cfg.maxPausesPerTurn, 1, 10, "maxPausesPerTurn");
  if (pausesErr) errors.maxPausesPerTurn = pausesErr;

  const autoErr = rangeError(cfg.maxAutoApprovalsPerTurn, 0, 10, "maxAutoApprovalsPerTurn");
  if (autoErr) errors.maxAutoApprovalsPerTurn = autoErr;

  if (cfg.onNoProgress != null && !ON_NO_PROGRESS_VALUES.includes(cfg.onNoProgress)) {
    errors.onNoProgress = `onNoProgress '${cfg.onNoProgress}' must be one of ${ON_NO_PROGRESS_VALUES.join(", ")}`;
  }

  if (cfg.inGroupTurns != null && cfg.inGroupTurns !== "REJECT") {
    errors.inGroupTurns =
      cfg.inGroupTurns === "INBOX"
        ? "inGroupTurns=INBOX is reserved for a future version; use REJECT"
        : `inGroupTurns '${cfg.inGroupTurns}' must be REJECT (INBOX is reserved)`;
  }

  // Tool-pause timeout override: a finite policy needs a positive ISO-8601 duration.
  const at = cfg.approvalTimeout?.trim();
  if (requiresApprovalTimeout(cfg.timeoutPolicy)) {
    if (!at) {
      errors.approvalTimeout =
        "a finite timeoutPolicy (AUTO_APPROVE/AUTO_REJECT/ABORT) requires an approvalTimeout (e.g. \"PT30M\")";
    } else if (!isValidIsoDuration(at)) {
      errors.approvalTimeout = `'${at}' is not a valid positive ISO-8601 duration (e.g. "PT30S", "PT15M", "PT2H")`;
    }
  } else if (at && !isValidIsoDuration(at)) {
    errors.approvalTimeout = `'${at}' is not a valid positive ISO-8601 duration (e.g. "PT30S", "PT15M", "PT2H")`;
  }

  const reasonErr = reasonError(cfg.pauseReason, "pauseReason");
  if (reasonErr) errors.pauseReason = reasonErr;
  const pendingErr = reasonError(cfg.pendingMessage, "pendingMessage");
  if (pendingErr) errors.pendingMessage = pendingErr;

  return errors;
}

/** True when a tool-approvals validation result contains any error. */
export function hasToolApprovalsErrors(errors: ToolApprovalsErrors): boolean {
  return Object.keys(errors).length > 0;
}

/**
 * True when an agent-level HITL config would trigger the backend's
 * AUTO_APPROVE-demotion WARN: the agent timeoutPolicy is AUTO_APPROVE and a
 * toolApprovals block is present without its own timeoutPolicy — so tool pauses
 * silently WAIT_INDEFINITELY. Surface this as a non-blocking hint in the editor.
 */
export function toolApprovalsInheritsAutoApprove(
  agentTimeoutPolicy: string | null | undefined,
  toolApprovals: ToolApprovalsConfig | null | undefined,
): boolean {
  return (
    agentTimeoutPolicy === "AUTO_APPROVE" &&
    toolApprovals != null &&
    (toolApprovals.timeoutPolicy == null || toolApprovals.timeoutPolicy === undefined)
  );
}
