import { api } from "../api-client";

// Types matching EDDI backend
export interface BotDescriptor {
  resource: string;
  name: string;
  description: string;
  createdOn: number;
  lastModifiedOn: number;
  createdBy?: string;
  lastModifiedBy?: string;
}

export interface Bot {
  packages?: string[];
  channels?: string[];
}

export interface DeploymentStatus {
  status: "NOT_FOUND" | "IN_PROGRESS" | "READY" | "ERROR";
}

/** Parse resource URI to extract id and version */
export function parseResourceUri(resource: string): {
  id: string;
  version: number;
} {
  // Format: eddi://ai.labs.bot/botstore/bots/ID?version=VERSION
  const url = new URL(resource.replace("eddi://", "http://"));
  const parts = url.pathname.split("/");
  const id = parts[parts.length - 1]!;
  const version = parseInt(url.searchParams.get("version") || "1", 10);
  return { id, version };
}

// API functions
export function getBotDescriptors(
  limit = 20,
  index = 0,
  filter = ""
): Promise<BotDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  return api.get<BotDescriptor[]>(
    `/botstore/bots/descriptors?${params.toString()}`
  );
}

export function getBot(id: string, version?: number): Promise<Bot> {
  const versionSuffix = version ? `?version=${version}` : "";
  return api.get<Bot>(`/botstore/bots/${id}${versionSuffix}`);
}

export function createBot(bot: Bot): Promise<{ location: string }> {
  return api.post<{ location: string }>("/botstore/bots", bot);
}

export function updateBot(
  id: string,
  version: number,
  bot: Bot
): Promise<void> {
  return api.put(`/botstore/bots/${id}?version=${version}`, bot);
}

export function deleteBot(id: string, version: number): Promise<void> {
  return api.delete(`/botstore/bots/${id}?version=${version}`);
}

export function duplicateBot(
  id: string,
  version: number,
  deepCopy = false
): Promise<{ location: string }> {
  return api.post<{ location: string }>(
    `/botstore/bots/${id}?version=${version}&deepCopy=${deepCopy}`
  );
}

export function deployBot(
  environment: string,
  botId: string,
  version?: number
): Promise<void> {
  const versionSuffix = version ? `?version=${version}` : "";
  return api.post(
    `/administration/${environment}/deploy/${botId}${versionSuffix}`
  );
}

export function undeployBot(
  environment: string,
  botId: string
): Promise<void> {
  return api.post(`/administration/${environment}/undeploy/${botId}`);
}

export function getDeploymentStatus(
  environment: string,
  botId: string
): Promise<DeploymentStatus> {
  return api.get<DeploymentStatus>(
    `/administration/${environment}/deploymentstatus/${botId}`
  );
}
