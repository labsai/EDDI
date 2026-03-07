import { getPackageDescriptors, getPackage } from "./packages";
import { getBotDescriptors, getBot, parseResourceUri } from "./bots";

export interface ResourceUsage {
  packageId: string;
  packageVersion: number;
  packageName: string;
  botId: string;
  botVersion: number;
  botName: string;
}

/**
 * Find all packages and bots that reference a given resource URI.
 *
 * Scans all packages for extensions whose config.uri contains
 * the resource ID, then scans all bots for references to those packages.
 */
export async function findResourceUsage(
  resourceId: string,
  resourceStore: string,
  resourcePlural: string
): Promise<ResourceUsage[]> {
  const usages: ResourceUsage[] = [];

  // 1. Get all packages
  const pkgDescriptors = await getPackageDescriptors(200, 0, "");

  for (const pkgDesc of pkgDescriptors) {
    const { id: pkgId, version: pkgVersion } = parseResourceUri(pkgDesc.resource);

    try {
      const pkg = await getPackage(pkgId, pkgVersion);
      // Check if any extension references this resource
      const hasReference = pkg.packageExtensions.some((ext) => {
        const uri = ext.config?.uri;
        return (
          typeof uri === "string" &&
          uri.includes(`/${resourceStore}/${resourcePlural}/${resourceId}`)
        );
      });

      if (!hasReference) continue;

      // 2. Find bots that reference this package
      const botDescriptors = await getBotDescriptors(200, 0, "");

      for (const botDesc of botDescriptors) {
        const { id: botId, version: botVersion } = parseResourceUri(botDesc.resource);
        try {
          const bot = await getBot(botId, botVersion);
          const pkgUri = pkgDesc.resource;
          if (bot.packages?.some((uri) => uri === pkgUri)) {
            usages.push({
              packageId: pkgId,
              packageVersion: pkgVersion,
              packageName: pkgDesc.name || pkgId,
              botId,
              botVersion,
              botName: botDesc.name || botId,
            });
          }
        } catch {
          // Skip bots that can't be loaded
        }
      }
    } catch {
      // Skip packages that can't be loaded
    }
  }

  return usages;
}
