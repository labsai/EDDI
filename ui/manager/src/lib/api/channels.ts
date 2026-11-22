import { api } from "@eddi/app-kit/lib/api-client";
import type { AgentDescriptor } from "@eddi/app-kit/lib/api/agents";
import { seg } from "@eddi/app-kit/lib/api/path-segment";

// ─── Types ──────────────────────────────────────────────────────

export const CHANNEL_TYPES = ["slack", "webhook", "email"] as const;

/** Signature schemes of a `webhook` channel integration (backend default: eddi-v1). */
export const WEBHOOK_SIGNATURE_SCHEMES = ["eddi-v1", "github", "generic-hmac"] as const;

/** The endpoint a `webhook` channel integration receives events on. */
export function webhookEndpointUrl(origin: string, integrationId: string): string {
  return `${origin}/integrations/webhook/${integrationId}`;
}

export type TargetType = "AGENT" | "GROUP";

/**
 * Observe mode configuration — controls when a passive observer target
 * should respond. Schema-ready (backend deferred).
 */
export interface ObserveConfig {
  triggerKeywords: string[];
  triggerMimeTypes: string[];
  cooldownSeconds: number;
  maxDailyResponses: number;
  maxCostPerDay: number;
}

export const DEFAULT_OBSERVE_CONFIG: ObserveConfig = {
  triggerKeywords: [],
  triggerMimeTypes: [],
  cooldownSeconds: 60,
  maxDailyResponses: 50,
  maxCostPerDay: 5.0,
};

/**
 * The email approval gate of an email target, in strictness order: `none` (the
 * agent's own toolApprovals only), `actions` (every tool call not in
 * `readOnlyTools` waits for approval), `firstContact` (as actions, and a
 * sender's first mail waits before the agent runs), `everyTurn` (every mail
 * waits before the agent runs). Email AGENT targets only.
 */
export const APPROVAL_POLICIES = ["none", "actions", "firstContact", "everyTurn"] as const;
export type ApprovalPolicy = (typeof APPROVAL_POLICIES)[number];

/** What the Manager preselects for a new email target. */
export const DEFAULT_EMAIL_APPROVAL_POLICY: ApprovalPolicy = "actions";

export interface ChannelTarget {
  name: string;
  triggers: string[];
  type: TargetType;
  targetId: string;
  observeMode: boolean;
  observeConfig: ObserveConfig | null;
  /** Email AGENT targets only; absent means `none`. */
  approvalPolicy?: ApprovalPolicy;
  /** Email only: tool-approval patterns a mail turn may call without the email gate. */
  readOnlyTools?: string[];
  /** Email only: what a sender is told when an approver rejects a held mail. */
  rejectedReply?: string;
}

/**
 * The target without the email approval fields — what a GROUP target, or a
 * target of another channel type, must be saved as (the backend refuses them).
 */
export function withoutEmailApproval(target: ChannelTarget): ChannelTarget {
  const copy: ChannelTarget = { ...target };
  delete copy.approvalPolicy;
  delete copy.readOnlyTools;
  delete copy.rejectedReply;
  return copy;
}

/** `drop` (the default) or `review`: what happens to a mail refused for a reviewable reason. */
export type EmailRefusalAction = "drop" | "review";

/** `emailControl` — the typed email settings next to `platformConfig` (email channel v3). */
export interface EmailControl {
  smime?: { sign?: boolean | "required"; connection?: string };
  onRefused?: { notAllowlisted?: EmailRefusalAction; unauthenticated?: EmailRefusalAction; notAddressed?: EmailRefusalAction };
  review?: { expiry?: string; maxHeldPerDay?: number; maxHeldPerSenderDomainPerDay?: number; reviewers?: string[] };
}

export interface ChannelIntegrationConfiguration {
  name: string;
  channelType: string;
  platformConfig: Record<string, string>;
  /** Email only; send it back unchanged unless you mean to change it. */
  emailControl?: EmailControl | null;
  targets: ChannelTarget[];
  defaultTargetName: string;
}

export type ChannelDescriptor = AgentDescriptor;

// ─── CRUD ───────────────────────────────────────────────────────

export function getChannelDescriptors(
  limit = 20,
  index = 0,
  filter = "",
): Promise<ChannelDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  return api.get<ChannelDescriptor[]>(
    `/channelstore/channels/descriptors?${params.toString()}`,
  );
}

export function getChannel(
  id: string,
  version?: number,
): Promise<ChannelIntegrationConfiguration> {
  const suffix = version !== undefined ? `?version=${version}` : "";
  return api.get<ChannelIntegrationConfiguration>(
    `/channelstore/channels/${seg(id)}${suffix}`,
  );
}

/**
 * The response header a channel-integration save carries advice in. The save
 * succeeded (201 / 200); the value is a comma-separated list of stable codes,
 * absent when there are none — e.g. `email.open-trigger`,
 * `email.tools-without-approvals`.
 */
export const CHANNEL_WARNINGS_HEADER = "X-Eddi-Warnings";

/** The codes in an `X-Eddi-Warnings` value: trimmed, blanks and repeats dropped. */
export function parseChannelWarnings(value: string | null | undefined): string[] {
  if (!value) return [];
  const codes: string[] = [];
  for (const raw of value.split(",")) {
    const code = raw.trim();
    if (code && !codes.includes(code)) codes.push(code);
  }
  return codes;
}

/** What a channel save answers: where the new version lives, and any save-time warnings. */
export interface ChannelSaveResult {
  location: string;
  warnings: string[];
}

function toSaveResult(response: {
  data: { location?: string } | undefined;
  headers: Headers;
}): ChannelSaveResult {
  return {
    location: response.data?.location ?? "",
    warnings: parseChannelWarnings(response.headers.get(CHANNEL_WARNINGS_HEADER)),
  };
}

export async function createChannel(
  config: ChannelIntegrationConfiguration,
): Promise<ChannelSaveResult> {
  return toSaveResult(
    await api.postWithResponse<{ location?: string } | undefined>("/channelstore/channels", config),
  );
}

/**
 * Saves a whole integration. `confirmOpen` confirms that the save empties a
 * non-empty email sender allowlist, which opens the integration to every sender:
 * the backend refuses such a save without it.
 */
export async function updateChannel(
  id: string,
  version: number,
  config: ChannelIntegrationConfiguration,
  confirmOpen = false,
): Promise<ChannelSaveResult> {
  const params = new URLSearchParams({ version: String(version) });
  if (confirmOpen) params.set("confirmOpen", "true");
  return toSaveResult(
    await api.putWithResponse<{ location?: string } | undefined>(
      `/channelstore/channels/${seg(id)}?${params}`,
      config,
    ),
  );
}

export function deleteChannel(
  id: string,
  version: number,
  permanent = true,
): Promise<void> {
  const params = new URLSearchParams({
    version: String(version),
    permanent: String(permanent),
  });
  return api.delete(`/channelstore/channels/${seg(id)}?${params}`);
}

export function duplicateChannel(
  id: string,
  version: number,
): Promise<{ location: string }> {
  return api.post<{ location: string }>(
    `/channelstore/channels/${seg(id)}?version=${version}`,
  );
}

// ─── Email sender lists ─────────────────────────────────────────

/** An email integration's sender lists, as of one version. */
export interface EmailSenderLists {
  version: number;
  allow: string[];
  block: string[];
}

/** Entries for one direction of a sender-list change. */
export interface EmailSenderEntries {
  allow?: string[];
  block?: string[];
}

/** The body of `POST /channelstore/channels/{id}/email-senders`. */
export interface EmailSenderChange {
  /** The version the lists were read from; another current version is a 409. */
  expectedVersion: number;
  add?: EmailSenderEntries;
  remove?: EmailSenderEntries;
  /** Why — recorded in the audit ledger. */
  reason?: string;
  /** Required to remove the last allowlist entry, which allows every sender. */
  confirmOpen?: boolean;
}

/** What a sender-list change answers. Entries are `allow:<entry>` / `block:<entry>`. */
export interface EmailSenderChangeResult {
  previousVersion: number;
  version: number;
  changed: boolean;
  allow: string[];
  block: string[];
  added: string[];
  removed: string[];
  unchanged: string[];
  warnings: string[];
}

/** The current sender lists of an email integration. */
export function getEmailSenders(id: string): Promise<EmailSenderLists> {
  return api.get<EmailSenderLists>(`/channelstore/channels/${seg(id)}/email-senders`);
}

/**
 * Adds and removes sender-list entries without rewriting the integration: the
 * backend stores a new version, exactly as a save would. A stale
 * `expectedVersion` is refused with 409 and the current lists in the error body.
 */
export function updateEmailSenders(id: string, change: EmailSenderChange): Promise<EmailSenderChangeResult> {
  return api.post<EmailSenderChangeResult>(`/channelstore/channels/${seg(id)}/email-senders`, change);
}

// ─── Held mail (email channel v3 §7) ────────────────────────────

/** A mail held for review instead of dropped. No content: open a preview for that. */
export interface HeldEmailItem {
  rowId: string;
  integrationId: string;
  sender: string | null;
  authVerdict: string | null;
  /** `not_allowlisted`, `unauthenticated`. */
  heldFor: string[];
  heldAt: string;
  expiresAt: string | null;
  /** Only for an aligned DKIM pass: a forged From may not be allowed. */
  runAndAllowOffered: boolean;
  /** A release runs anonymously, with read-only tools only, and sends no mail. */
  runsAnonymously: boolean;
}

export interface HeldEmailQueue {
  integrationId: string;
  integrationName: string | null;
  items: HeldEmailItem[];
  truncated: boolean;
}

/** A live preview, re-read from the mailbox; never stored. */
export interface HeldEmailPreview {
  item: HeldEmailItem;
  fromName: string | null;
  subject: string | null;
  text: string;
  textTruncated: boolean;
  attachments: string[];
}

export type HeldEmailDecision = "discard" | "runOnce" | "runAndAllow";

export interface HeldEmailDecisionResult {
  rowId: string;
  decision: HeldEmailDecision;
  state: string;
  allowlistVersion: number | null;
  warnings: string[];
}

export function getHeldEmail(id: string, limit = 50): Promise<HeldEmailQueue> {
  return api.get<HeldEmailQueue>(`/channelstore/channels/${seg(id)}/email-held?limit=${limit}`);
}

export function getHeldEmailPreview(id: string, rowId: string): Promise<HeldEmailPreview> {
  return api.get<HeldEmailPreview>(`/channelstore/channels/${seg(id)}/email-held/${seg(rowId)}`);
}

export function decideHeldEmail(
  id: string,
  rowId: string,
  decision: HeldEmailDecision,
  note?: string,
): Promise<HeldEmailDecisionResult> {
  return api.post<HeldEmailDecisionResult>(`/channelstore/channels/${seg(id)}/email-held/${seg(rowId)}/decision`, {
    decision,
    note: note?.trim() || undefined,
  });
}

// ─── Helpers ────────────────────────────────────────────────────

/**
 * Parse both eddi:// resource URIs and plain Location header paths.
 *
 * Accepted formats:
 *   - `eddi://ai.labs.channel/channelstore/channels/ID?version=VERSION`
 *   - `/channelstore/channels/ID?version=VERSION`
 */
export function parseChannelResourceUri(resource: string): {
  id: string;
  version: number;
} {
  const normalised = resource.startsWith("eddi://")
    ? resource.replace("eddi://", "http://")
    : resource;
  const url = new URL(normalised, "http://dummy");
  const parts = url.pathname.split("/").filter(Boolean);
  const id = parts[parts.length - 1] ?? resource;
  const parsedVersion = parseInt(url.searchParams.get("version") || "1", 10);
  const version = isNaN(parsedVersion) ? 1 : parsedVersion;
  return { id, version };
}

/** Enriched descriptor with config-level data */
export type EnrichedChannelDescriptor = ChannelDescriptor & {
  id: string;
  version: number;
  channelType: string;
  targetCount: number;
  channelId?: string;
};

/**
 * Fetch channel descriptors and enrich them with data from full configs.
 * Deduplicates by ID (keeps latest version), then batch-fetches configs.
 */
export async function getEnrichedChannelDescriptors(
  limit = 20,
  index = 0,
  filter = "",
): Promise<EnrichedChannelDescriptor[]> {
  const descriptors = await getChannelDescriptors(limit, index, filter);

  // Deduplicate by ID (keep latest version)
  const grouped = new Map<
    string,
    ChannelDescriptor & { id: string; version: number }
  >();
  for (const d of descriptors) {
    const { id, version } = parseChannelResourceUri(d.resource);
    const existing = grouped.get(id);
    if (!existing || version > existing.version) {
      grouped.set(id, { ...d, id, version });
    }
  }

  // Enrich with config data
  return Promise.all(
    Array.from(grouped.values())
      .sort((a, b) => b.lastModifiedOn - a.lastModifiedOn)
      .map(async (d) => {
        try {
          const config = await getChannel(d.id, d.version);
          return {
            ...d,
            name: config.name || d.name,
            channelType: config.channelType,
            targetCount: config.targets?.length ?? 0,
            channelId: config.platformConfig?.channelId,
          };
        } catch {
          return { ...d, channelType: "unknown", targetCount: 0 };
        }
      }),
  );
}

/**
 * Create an empty default target. An email target starts on the
 * `actions` approval policy: mail is untrusted input, so its tool calls wait
 * for approval unless the admin chooses otherwise.
 */
export function createDefaultTarget(agentId = "", email = false): ChannelTarget {
  return {
    name: "default",
    type: "AGENT",
    targetId: agentId,
    triggers: [],
    observeMode: false,
    observeConfig: null,
    ...(email ? { approvalPolicy: DEFAULT_EMAIL_APPROVAL_POLICY } : {}),
  };
}
