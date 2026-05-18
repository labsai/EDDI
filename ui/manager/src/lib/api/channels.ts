import { api } from "../api-client";
import type { AgentDescriptor } from "./agents";

// ─── Types ──────────────────────────────────────────────────────

export const CHANNEL_TYPES = ["slack"] as const;
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

/**
 * Platform-specific config keys.
 * Slack: channelId, botToken, signingSecret
 * Teams: channelId, appId, appPassword, serviceUrl (future)
 * Discord: guildId, channelId, botToken, publicKey (future)
 */
export const SLACK_PLATFORM_KEYS = [
  "channelId",
  "botToken",
  "signingSecret",
] as const;

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
    `/channelstore/channels/${id}${suffix}`,
  );
}

export function createChannel(
  config: ChannelIntegrationConfiguration,
): Promise<{ location: string }> {
  return api.post<{ location: string }>("/channelstore/channels", config);
}

export function updateChannel(
  id: string,
  version: number,
  config: ChannelIntegrationConfiguration,
): Promise<{ location: string }> {
  return api.put(
    `/channelstore/channels/${id}?version=${version}`,
    config,
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
  return api.delete(`/channelstore/channels/${id}?${params}`);
}

export function duplicateChannel(
  id: string,
  version: number,
): Promise<{ location: string }> {
  return api.post<{ location: string }>(
    `/channelstore/channels/${id}?version=${version}`,
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
  const id = parts[parts.length - 1]!;
  const version = parseInt(url.searchParams.get("version") || "1", 10);
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
