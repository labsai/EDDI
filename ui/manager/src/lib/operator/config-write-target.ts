import { RESOURCE_TYPES, type ResourceTypeConfig } from "@/lib/api/resources";
import type { ResolvedRequestPreview } from "@/lib/api/hitl";

/** The stored document a gated whole-document write would replace. */
export interface ConfigWriteTarget {
  resourceType: ResourceTypeConfig;
  id: string;
  /** The version the write is based on — i.e. the one to diff against. */
  version: number;
}

/**
 * Identifies the stored document a gated `PUT` is about to replace, so the
 * approver can be shown what actually CHANGES rather than the whole document.
 *
 * This is the review problem the operator's write scope created. Every
 * workflow-extension write is a whole-document `PUT` — EDDI has no partial
 * update for these — so approving a one-line edit to a 400-line ruleset means
 * finding that line by eye. The honest behaviour under that load is to skim and
 * approve, which is precisely what the gate exists to prevent.
 *
 * Returns `null` for anything it cannot identify with certainty. Guessing here
 * would be worse than not offering a diff: a diff against the wrong document
 * shows invented changes, and an approver who trusts it approves on a false
 * picture.
 *
 * Note EDDI writes version+1 rather than mutating in place, so the version in
 * the request URI is the BASE version — the one currently stored, and the
 * correct left-hand side of the comparison.
 */
export function resolveConfigWriteTarget(preview: ResolvedRequestPreview): ConfigWriteTarget | null {
  if (!preview?.uri || preview.method?.toUpperCase() !== "PUT") return null;

  let path: string;
  let searchParams: URLSearchParams;
  try {
    // The resolved URI is absolute, but tolerate a relative one rather than
    // throwing — `URL` needs a base for those.
    const url = new URL(preview.uri, "http://placeholder.invalid");
    path = url.pathname;
    searchParams = url.searchParams;
  } catch {
    return null;
  }

  // /{store}/{plural}/{id} — exactly three segments. A longer path is a
  // sub-resource verb (e.g. .../updateResourceUri), which is NOT a
  // whole-document replacement and must not be diffed as one.
  const segments = path.split("/").filter(Boolean);
  if (segments.length !== 3) return null;
  const [store, plural, id] = segments;
  if (!id) return null;

  const resourceType = RESOURCE_TYPES.find((rt) => rt.store === store && rt.plural === plural);
  if (!resourceType) return null;

  // `queryParams` is the authoritative parsed form; the URI's own query string
  // is the fallback for a preview that carried it inline.
  const rawVersion = firstValue(preview.queryParams?.version) ?? searchParams.get("version");
  if (rawVersion == null) return null;
  const version = Number(rawVersion);
  if (!Number.isInteger(version) || version < 1) return null;

  return { resourceType, id, version };
}

/** `queryParams` values may be a bare string or a repeated-parameter list. */
function firstValue(value: unknown): string | null {
  if (typeof value === "string") return value;
  if (Array.isArray(value) && typeof value[0] === "string") return value[0];
  return null;
}

/**
 * Whether the proposed body carries redaction markers.
 *
 * The preview body is redacted; the stored document fetched to compare against
 * is not. So any credential-bearing field diffs as a change when nothing about
 * it changed — and "the operator is rewriting our API key" is exactly the wrong
 * conclusion for an approver to reach from a review aid. The diff still renders
 * (the other lines are the point), with this driving a caveat beside it.
 */
export function bodyHasRedactions(body: string | null | undefined): boolean {
  return typeof body === "string" && body.includes("<REDACTED>");
}
