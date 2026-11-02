import { api } from "../api-client";
import type { AgentDescriptor } from "./agents";
import { seg } from "./path-segment";

// ─── Types ──────────────────────────────────────────────────────

export const CHANNEL_TYPES = ["slack", "webhook", "email"] as const;

/** Signature schemes of a `webhook` channel integration (backend default: eddi-v1). */
export const WEBHOOK_SIGNATURE_SCHEMES = ["eddi-v1", "github", "generic-hmac"] as const;

/** The endpoint a `webhook` channel integration receives events on. */
export function webhookEndpointUrl(origin: string, integrationId: string): string {
  return `${origin}/integrations/webhook/${integrationId}`;
}
export type ChannelType = (typeof CHANNEL_TYPES)[number];

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

export interface ChannelTarget {
  name: string;
  triggers: string[];
  type: TargetType;
  targetId: string;
  observeMode: boolean;
  observeConfig: ObserveConfig | null;
}

export interface ChannelIntegrationConfiguration {
  name: string;
  channelType: string;
  platformConfig: Record<string, string>;
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

export async function updateChannel(
  id: string,
  version: number,
  config: ChannelIntegrationConfiguration,
): Promise<ChannelSaveResult> {
  return toSaveResult(
    await api.putWithResponse<{ location?: string } | undefined>(
      `/channelstore/channels/${seg(id)}?version=${version}`,
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

/** Create an empty default target */
export function createDefaultTarget(agentId = ""): ChannelTarget {
  return {
    name: "default",
    type: "AGENT",
    targetId: agentId,
    triggers: [],
    observeMode: false,
    observeConfig: null,
  };
}
