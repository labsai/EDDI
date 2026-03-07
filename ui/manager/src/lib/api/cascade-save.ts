import { parseResourceUri } from "./bots";
import {
  updateResource,
  type ResourceTypeConfig,
} from "./resources";
import {
  getPackage,
  updatePackage,
  type PackageConfiguration,
} from "./packages";
import { getBot, updateBot, type Bot } from "./bots";

export interface CascadeContext {
  packageId: string;
  packageVersion: number;
  botId: string;
  botVersion: number;
}

export interface CascadeResult {
  newResourceVersion: number;
  newPackageVersion?: number;
  newBotVersion?: number;
}

/**
 * Save a resource config, then cascade version updates up through
 * the package → bot chain.
 *
 * The EDDI backend increments version on every PUT, returning the
 * new URI in the Location header. We parse that to update parent references.
 */
export async function cascadeSaveResource(
  rt: ResourceTypeConfig,
  resourceId: string,
  resourceVersion: number,
  body: unknown,
  context?: CascadeContext
): Promise<CascadeResult> {
  // 1. Save the resource config
  const saveResult = await updateResource(rt, resourceId, resourceVersion, body);
  const newResourceVersion = parseVersionFromLocation(saveResult.location);

  if (!context) {
    return { newResourceVersion };
  }

  // 2. Update the parent package
  const oldResourceUri = buildResourceUri(rt, resourceId, resourceVersion);
  const newResourceUri = buildResourceUri(rt, resourceId, newResourceVersion);

  const pkg = await getPackage(context.packageId, context.packageVersion);
  const updatedPkg = replaceExtensionUri(pkg, oldResourceUri, newResourceUri);
  const pkgResult = await updatePackage(
    context.packageId,
    context.packageVersion,
    updatedPkg
  );
  const newPackageVersion = parseVersionFromLocation(pkgResult.location);

  // 3. Update the parent bot
  const oldPkgUri = `eddi://ai.labs.package/packagestore/packages/${context.packageId}?version=${context.packageVersion}`;
  const newPkgUri = `eddi://ai.labs.package/packagestore/packages/${context.packageId}?version=${newPackageVersion}`;

  const bot = await getBot(context.botId, context.botVersion);
  const updatedBot: Bot = {
    ...bot,
    packages: (bot.packages ?? []).map((uri) =>
      uri === oldPkgUri ? newPkgUri : uri
    ),
  };
  const botResult = await updateBot(
    context.botId,
    context.botVersion,
    updatedBot
  );
  const newBotVersion = parseVersionFromLocation(botResult.location);

  return { newResourceVersion, newPackageVersion, newBotVersion };
}

/** Parse version number from a Location URI like `eddi://…?version=2` */
function parseVersionFromLocation(location: string): number {
  const { version } = parseResourceUri(location);
  return version;
}

/** Build an EDDI resource URI from config type, id, and version */
function buildResourceUri(
  rt: ResourceTypeConfig,
  id: string,
  version: number
): string {
  const baseType = `eddi://ai.labs.${rt.slug}`;
  return `${baseType}/${rt.store}/${rt.plural}/${id}?version=${version}`;
}

/** Replace old extension URI with new one inside a package config */
function replaceExtensionUri(
  pkg: PackageConfiguration,
  oldUri: string,
  newUri: string
): PackageConfiguration {
  return {
    ...pkg,
    packageExtensions: pkg.packageExtensions.map((ext) => {
      const uri = ext.config?.uri;
      if (typeof uri === "string" && uri === oldUri) {
        return { ...ext, config: { ...ext.config, uri: newUri } };
      }
      return ext;
    }),
  };
}
