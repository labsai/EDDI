import { parseRedactedJson } from "@/lib/redacted-json";
import type { ResolvedRequestPreview } from "@/lib/api/hitl";

/**
 * Reads a gated `POST /agentstore/agents/{id}/cascade` request into the form an
 * approver can read at a glance.
 *
 * The raw body is `{"resourceMappings":[{"oldUri":"eddi://ai.labs.llm/llmstore/
 * llms/6a1b…?version=3","newUri":"…?version=4"}, …], "redeploy": true, …}`. Two
 * long URIs per mapping that differ in one digit is exactly the shape a skimming
 * approver cannot check, so each mapping is reduced to what changes — the kind of
 * resource, its id and `v3 → v4` — and the consequences that are not in the
 * mappings (a redeploy, and what it does to running conversations) are stated as
 * sentences.
 *
 * This is a **display aid, not a control**: nothing is blocked on the result and
 * the raw body stays available. It therefore returns `null` rather than a
 * best-effort reading for anything it cannot read in full — a truncated body, a
 * body that is not the expected shape — so the approver is never shown a summary
 * that is quietly missing a mapping.
 */

/** One resource whose reference moves, reduced to what an approver compares. */
export interface CascadeMappingView {
  /** `llm`, `rules`, `workflow`, `rag`, … — the part after `eddi://ai.labs.`. */
  kind: string;
  /** The resource id the OLD uri names. */
  id: string;
  /** Set when the new URI names a different resource — a swap, not a version bump. */
  newId: string | null;
  fromVersion: number | null;
  toVersion: number | null;
  /** The verbatim URIs, for the tooltip and for anything this could not parse. */
  oldUri: string;
  newUri: string;
}

/** A cascade request, readable. */
export interface CascadePreview {
  agentId: string;
  /** The agent version the cascade starts from; `null` if the request omits it. */
  agentVersion: number | null;
  mappings: CascadeMappingView[];
  redeploy: boolean;
  /** `production` when the request does not name one — the backend's default. */
  environment: string;
  compatible: boolean;
}

const CASCADE_PATH = /\/agentstore\/agents\/([^/?#]+)\/cascade\/?$/i;
const VERSIONED_URI = /^eddi:\/\/(?:ai\.labs\.)?([^/]+)\/.*\/([^/?#]+)\?version=(\d+)$/;

function splitUri(uri: string): { kind: string; id: string; version: number } | null {
  const match = VERSIONED_URI.exec(uri);
  if (!match) return null;
  return { kind: match[1]!, id: match[2]!, version: Number(match[3]) };
}

function toView(oldUri: string, newUri: string): CascadeMappingView {
  const from = splitUri(oldUri);
  const to = splitUri(newUri);
  return {
    kind: from?.kind ?? to?.kind ?? "resource",
    id: from?.id ?? oldUri,
    newId: from && to && from.id !== to.id ? to.id : null,
    fromVersion: from?.version ?? null,
    toVersion: to?.version ?? null,
    oldUri,
    newUri,
  };
}

/** Whether a resolved request is a cascade — without needing its body. */
export function isCascadeRequest(preview: ResolvedRequestPreview | null | undefined): boolean {
  if (!preview?.uri) return false;
  if ((preview.method ?? "").toUpperCase() !== "POST") return false;
  return CASCADE_PATH.test(pathOf(preview.uri));
}

function pathOf(uri: string): string {
  try {
    return new URL(uri, "http://placeholder.invalid").pathname;
  } catch {
    return uri;
  }
}

export function parseCascadePreview(preview: ResolvedRequestPreview | null | undefined): CascadePreview | null {
  if (!preview || !isCascadeRequest(preview)) return null;
  if (preview.bodyTruncated || !preview.body) return null;

  let decodedPath = pathOf(preview.uri);
  try {
    decodedPath = decodeURIComponent(decodedPath);
  } catch {
    // Keep the raw path; an id with a malformed escape is still shown as written.
  }
  const agentId = CASCADE_PATH.exec(decodedPath)?.[1];
  if (!agentId) return null;

  const parsed = parseRedactedJson(preview.body);
  if (!parsed.ok || typeof parsed.value !== "object" || parsed.value === null || Array.isArray(parsed.value)) {
    return null;
  }
  const body = parsed.value as Record<string, unknown>;
  const rawMappings = body.resourceMappings;
  if (!Array.isArray(rawMappings) || rawMappings.length === 0) return null;

  const mappings: CascadeMappingView[] = [];
  for (const entry of rawMappings) {
    if (typeof entry !== "object" || entry === null) return null;
    const { oldUri, newUri } = entry as Record<string, unknown>;
    // One unreadable entry withdraws the whole summary: a list that silently
    // skips a mapping reads as complete.
    if (typeof oldUri !== "string" || typeof newUri !== "string") return null;
    mappings.push(toView(oldUri, newUri));
  }

  // `queryParams` is the authoritative parsed form; the URI's own query string is
  // the fallback for a preview that carried it inline.
  const rawVersion = preview.queryParams?.version ?? new URL(preview.uri, "http://placeholder.invalid").searchParams.get("version");
  const version = rawVersion == null ? NaN : Number(rawVersion);

  const environment = typeof body.environment === "string" && body.environment.trim() !== "" ? body.environment.trim() : "production";
  return {
    agentId,
    agentVersion: Number.isInteger(version) ? version : null,
    mappings,
    redeploy: body.redeploy === true,
    environment,
    compatible: body.compatible === true,
  };
}
