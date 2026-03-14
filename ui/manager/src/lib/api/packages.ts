import { api } from "../api-client";
import type { BotDescriptor } from "./bots";

export { type BotDescriptor as PackageDescriptor };

export interface PackageExtension {
  type: string;
  extensions: Record<string, unknown>;
  config: Record<string, unknown>;
}

export interface PackageConfiguration {
  packageExtensions: PackageExtension[];
}

// API functions
export function getPackageDescriptors(
  limit = 100,
  index = 0,
  filter = ""
): Promise<BotDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  return api.get<BotDescriptor[]>(
    `/packagestore/packages/descriptors?${params.toString()}`
  );
}

export function getPackage(
  id: string,
  version: number
): Promise<PackageConfiguration> {
  return api.get<PackageConfiguration>(
    `/packagestore/packages/${id}?version=${version}`
  );
}

export function createPackage(
  config: PackageConfiguration
): Promise<{ location: string }> {
  return api.post<{ location: string }>("/packagestore/packages", config);
}

export function updatePackage(
  id: string,
  version: number,
  config: PackageConfiguration
): Promise<{ location: string }> {
  return api.put(
    `/packagestore/packages/${id}?version=${version}`,
    config
  );
}

export function deletePackage(
  id: string,
  version: number,
  options?: { cascade?: boolean; permanent?: boolean }
): Promise<void> {
  const params = new URLSearchParams({ version: String(version) });
  if (options?.cascade) params.set("cascade", "true");
  if (options?.permanent) params.set("permanent", "true");
  return api.delete(`/packagestore/packages/${id}?${params}`);
}

/** Get all versions of a specific package (for version picker) */
export function getPackageVersions(
  id: string
): Promise<BotDescriptor[]> {
  return api.get<BotDescriptor[]>(
    `/packagestore/packages/descriptors?filter=${id}&includePreviousVersions=true`
  );
}
