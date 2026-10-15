/**
 * Reading an RFC 6902 JSON Patch the operator proposes.
 *
 * Shared by `gate-guard.ts`, which refuses a patch that would touch an approval
 * gate, and the approval card, which lists what a patch does. Both must agree on
 * what a patch IS — an array of `{op, path, value?, from?}` objects — so a body
 * one of them can read and the other cannot is impossible by construction.
 *
 * This is a reader, not an applier: nothing here evaluates a patch against a
 * document. It answers "what does this body say", never "what would it produce".
 */

/** The media type EDDI's PATCH endpoints accept; the operator's tool sends it. */
export const JSON_PATCH_MEDIA_TYPE = "application/json-patch+json";

/** One operation of a patch, as written. */
export interface JsonPatchOp {
  op: string;
  /** A JSON Pointer (RFC 6901): `""` is the whole document. */
  path: string;
  /** Present on `move` and `copy`. */
  from?: string;
  /** Present on `add`, `replace` and `test`; `undefined` when the key is absent. */
  value?: unknown;
}

export type ParsedJsonPatch = { ok: true; ops: JsonPatchOp[] } | { ok: false };

/**
 * Reads an already-parsed JSON value as a patch.
 *
 * Strict on purpose: every element must be an object with a string `op` and a
 * string `path`, and a `from`, when present, must be a string. Anything else is
 * `ok: false`, and the callers that use this as a control treat "cannot read it"
 * as "refuse" — a patch that is not well formed also fails server-side, so
 * refusing it here costs nothing.
 */
export function parseJsonPatch(parsed: unknown): ParsedJsonPatch {
  if (!Array.isArray(parsed)) return { ok: false };
  const ops: JsonPatchOp[] = [];
  for (const entry of parsed) {
    if (typeof entry !== "object" || entry === null || Array.isArray(entry)) return { ok: false };
    const record = entry as Record<string, unknown>;
    if (typeof record.op !== "string" || typeof record.path !== "string") return { ok: false };
    if ("from" in record && typeof record.from !== "string") return { ok: false };
    const op: JsonPatchOp = { op: record.op, path: record.path };
    if (typeof record.from === "string") op.from = record.from;
    if ("value" in record) op.value = record.value;
    ops.push(op);
  }
  return { ok: true, ops };
}

/** Reads a patch from a request body's text. */
export function parseJsonPatchBody(body: string | null | undefined): ParsedJsonPatch {
  if (typeof body !== "string" || body.trim() === "") return { ok: false };
  try {
    return parseJsonPatch(JSON.parse(body));
  } catch {
    return { ok: false };
  }
}
