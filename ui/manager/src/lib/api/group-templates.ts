import { api } from "../api-client";

// ─────────────────────────────────────────────────────────────────
// I10 — packaged group templates (research-pod, editorial-team,
// ops-task-force, decision-board, negotiation-table).
//
// NOTE on naming: `lib/group-templates.ts` already exports a client-side-only
// `GroupTemplate` type for the wizard's convenience presets (a handful of
// hardcoded style/role suggestions, no backend involved). This is a DIFFERENT,
// backend-persisted concept — a manifest + a full packaged AgentGroupConfiguration
// that gets instantiated through the normal group store. To avoid colliding with
// that existing type, everything here is prefixed `GroupTemplate*` →
// `TemplateManifest`/`TemplateRoleSlot`/`GroupTemplateDetail`.
// ─────────────────────────────────────────────────────────────────

/**
 * One role slot a template declares (e.g. `"researcher1"`). Every declared role
 * is implicitly required — there is no optional-role concept and no capability
 * list, just a key and a human description.
 */
export interface TemplateRoleSlot {
  role: string;
  description: string;
}

/** A packaged template's metadata, as returned by the list/read endpoints. Carries no `style` — that lives only inside the packaged config. */
export interface TemplateManifest {
  templateId: string;
  title: string;
  description: string;
  requiredRoles: TemplateRoleSlot[];
}

/**
 * Full detail for one template (`GET /groupstore/templates/{templateId}`).
 * `config` is the packaged `AgentGroupConfiguration` as raw, unparsed JSON —
 * byte-for-byte what the template resource file declares, INCLUDING unresolved
 * `"$role"` placeholder strings in `members[].agentId`/`moderatorAgentId`. It is
 * shown for preview only; do not treat it as a ready-to-save config.
 */
export interface GroupTemplateDetail {
  manifest: TemplateManifest;
  config: Record<string, unknown>;
}

/**
 * Body of the instantiate call. `roleAssignments` maps every role the
 * manifest declares to an agent id — or, for a role the template hard-codes as
 * `memberType: "HUMAN"` (only `decision-board`'s `humanDirector` today), the
 * human's principal id instead. `name` falls back to the template's own title
 * when omitted/blank.
 */
export interface InstantiateTemplateRequest {
  name?: string | null;
  roleAssignments: Record<string, string>;
}

/**
 * List every packaged template's manifest, in a fixed, curated order (the
 * backend's `index.txt`) — NOT alphabetical.
 * GET /groupstore/templates
 */
export function listGroupTemplates(): Promise<TemplateManifest[]> {
  return api.get<TemplateManifest[]>("/groupstore/templates");
}

/**
 * Read one template's manifest + packaged (still-placeholder) config, for a
 * preview before instantiation.
 * GET /groupstore/templates/{templateId}
 * 404 (via the thrown ApiError) when `templateId` doesn't match any packaged template.
 */
export function readGroupTemplate(templateId: string): Promise<GroupTemplateDetail> {
  return api.get<GroupTemplateDetail>(
    `/groupstore/templates/${encodeURIComponent(templateId)}`,
  );
}

/**
 * Instantiate a template into a brand-new, saved group — resolves every
 * `$role` placeholder against `request.roleAssignments`, then saves through the
 * exact same store path (and every save-time validator) a hand-written config
 * would.
 * POST /groupstore/templates/{templateId}/instantiate
 * Success: 201, empty body + Location header — same shape as `createGroup`.
 * Failure (400, nothing persisted): unknown templateId, missing/unknown role
 * keys, or any save-time validator rejection (vote phases, human members,
 * facilitator, artifact specs, HITL config) — the thrown ApiError's `message`
 * carries the backend's exact, actionable text (e.g. "Missing role
 * assignment(s): researcher2").
 */
export function instantiateGroupTemplate(
  templateId: string,
  request: InstantiateTemplateRequest,
): Promise<{ location: string }> {
  return api.post<{ location: string }>(
    `/groupstore/templates/${encodeURIComponent(templateId)}/instantiate`,
    request,
  );
}
