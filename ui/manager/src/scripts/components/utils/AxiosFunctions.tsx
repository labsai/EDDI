import axios from 'axios';
import * as React from 'react';
import { parsePluginExtensions } from './helpers/PluginParser';
import { DEFAULT_LIMIT, getAPIUrl } from './ApiFunctions';
import Parser from './Parser';
import * as _ from 'lodash';
import {
  mapDataToDetailedDescriptors,
  postJsonHelper,
  putHelper,
} from './helpers/JsonHelpers';
import {
  BEHAVIOR,
  BEHAVIOR_PATH,
  BOT,
  BOT_PATH,
  OUTPUT,
  OUTPUT_PATH,
  PACKAGE,
  PACKAGE_PATH,
  REGULAR_DICTIONARY,
  REGULAR_DICTIONARY_PATH,
  HTTPCALLS,
  HTTPCALLS_PATH,
} from './EddiTypes';

export interface IDescriptor {
  createdBy?: string;
  createdOn: number;
  description: string;
  lastModifiedBy?: string;
  lastModifiedOn: number;
  name: string;
  resource: string;
}

export interface IDetailedDescriptor extends IDescriptor {
  id: string;
  version: number;
  currentVersion?: number;
}

export async function getDescriptor(
  id: string,
  version: number,
): Promise<IDescriptor> {
  try {
    const descriptor = await axios.get(
      `${await getAPIUrl()}/descriptorstore/descriptors/${id}?version=${version}`,
    );
    return {
      createdBy: descriptor.data.createdBy,
      createdOn: descriptor.data.createdOn,
      description: descriptor.data.description,
      lastModifiedBy: descriptor.data.lastModifiedBy,
      lastModifiedOn: descriptor.data.lastModifiedOn,
      name: descriptor.data.name,
      resource: descriptor.data.resource,
    };
  } catch (e) {
    console.error(`failed to get descriptor. Error: ${e.message}`);
    throw e;
  }
}

export interface IBot extends IDetailedDescriptor {
  packages?: string[];
  channels?: string[];
  hasAvailableUpdates?: boolean;
  deploymentStatus?: string;
}

export interface IDescriptorResponse {
  data: IDescriptor[];
}

export const ID_LENGTH: number = 24;

export async function getBot(botResourceOrId: string): Promise<IBot> {
  if (_.size(botResourceOrId) > ID_LENGTH) {
    return await getSpecificBot(botResourceOrId);
  } else {
    return await getCurrentBot(botResourceOrId);
  }
}
export async function getSpecificBot(botResource: string): Promise<IBot> {
  try {
    const id = Parser.getId(botResource);
    const version = Parser.getVersion(botResource);
    const botDescriptor = await getDescriptor(id, version);
    const data: IBotData = await getBotData(botResource);
    const currentVersion = await getCurrentVersion(botResource);
    const deploymentStatus = await getDeploymentStatus(botResource);
    return {
      id,
      version,
      currentVersion,
      createdOn: botDescriptor.createdOn,
      description: botDescriptor.description,
      lastModifiedOn: botDescriptor.lastModifiedOn,
      name: botDescriptor.name,
      resource: botDescriptor.resource,
      packages: data.packages,
      channels: data.channels,
      deploymentStatus,
    };
  } catch (err) {
    console.error(`Failed to get bot. Error: ${err.message}`);
    throw err;
  }
}

export async function getBotData(botResource: string): Promise<IBotData> {
  try {
    const botData: IBotDataResponse = await axios.get(
      `${await getAPIUrl()}${Parser.getApiPathWithIdAndVersion(botResource)}`,
    );
    return botData.data;
  } catch (err) {
    console.error(`Failed to get bot data. Error: ${err.message}`);
    throw err;
  }
}

export async function getAllBots(): Promise<IBot[]> {
  try {
    const botDescriptors = await axios.get(
      `${await getAPIUrl()}/botstore/bots/descriptors?limit=${DEFAULT_LIMIT}`,
    );
    const bots: IBot[] = botDescriptors.data.map(bot => {
      const version = Parser.getVersion(bot.resource);
      return {
        id: Parser.getId(bot.resource),
        version,
        currentVersion: version,
        createdOn: bot.createdOn,
        description: bot.description,
        lastModifiedOn: bot.lastModifiedOn,
        name: bot.name,
        resource: bot.resource,
      };
    });
    const temporaryBotList = [];
    for (let i = 0; i < _.size(bots); i++) {
      const bot = await getSpecificBot(bots[i].resource);
      temporaryBotList.push(bot);
    }
    // todo: Refactor this function
    return temporaryBotList;
  } catch (err) {
    console.error(`Failed to get all bots. Error: ${err.message}`);
    throw err;
  }
}

export async function getBotPackages(resource: string): Promise<string[]> {
  try {
    const botData = await axios.get(
      `${await getAPIUrl()}${Parser.getApiPathWithIdAndVersion(resource)}`,
    );
    return botData.data.packages;
  } catch (err) {
    console.error(`Failed to get packages in bot. Error: ${err.message}`);
    throw err;
  }
}

export async function getBotDescriptors(
  limit: number,
  index: number,
): Promise<IBot[]> {
  try {
    const res: IDescriptorResponse = await axios.get(
      `${await getAPIUrl()}/botstore/bots/descriptors?index=${index}&limit=${limit}`,
    );
    return res.data.map(bot => {
      const createdOn = bot.createdOn;
      const description = bot.description;
      const id = Parser.getId(bot.resource);
      const lastModifiedOn = bot.lastModifiedOn;
      const name = bot.name;
      const resource = bot.resource;
      const version = Parser.getVersion(bot.resource);
      return {
        createdOn,
        description,
        id,
        lastModifiedOn,
        name,
        resource,
        version,
        currentVersion: version,
      };
    });
  } catch (err) {
    console.error(err);
    throw err;
  }
}

export async function getCurrentBot(id: string): Promise<IBot> {
  try {
    const version = (await axios.get(
      `${await getAPIUrl()}/botstore/bots/${id}/currentversion`,
    )).data;
    const descriptor = await getDescriptor(id, version);
    const data: IBotData = await getBotData(descriptor.resource);
    const deploymentStatus = await getDeploymentStatus(descriptor.resource);
    return {
      id,
      lastModifiedOn: descriptor.lastModifiedOn,
      name: descriptor.name,
      version: version,
      currentVersion: version,
      description: descriptor.description,
      resource: descriptor.resource,
      createdOn: descriptor.createdOn,
      packages: data.packages,
      channels: data.channels,
      deploymentStatus,
    };
  } catch (err) {
    console.error(`Failed to get current bot. Error: ${err.message}`);
    throw err;
  }
}

export interface IPlugin extends IDetailedDescriptor {
  pluginData?: string;
  usedByPackages?: string[];
}

export interface IPluginResponse {
  data: IPlugin;
}

export async function getPluginData(pluginResource: string) {
  try {
    const res = await axios.get(
      `${`${await getAPIUrl()}${Parser.getApiPathWithIdAndVersion(
        pluginResource,
      )}`}`,
    );
    return res.data;
  } catch (err) {
    console.error(`Failed to get PluginData. Error: ${err.message}`);
    throw err;
  }
}

export async function getPackageData(
  packageResource: string,
): Promise<IPlugins> {
  try {
    const res: IPluginsResponse = await axios.get(
      `${await getAPIUrl()}${Parser.getApiPathWithIdAndVersion(
        packageResource,
      )}`,
    );
    return res.data;
  } catch (err) {
    console.error(`Failed to get PackageData. Error: ${err.message}`);
    throw err;
  }
}

export async function getPlugin(pluginResource: string): Promise<IPlugin> {
  try {
    const res: IPluginResponse = await axios.get(
      `${await getAPIUrl()}/descriptorstore/descriptors/${Parser.getIdAndVersion(
        pluginResource,
      )}`,
    );
    const pluginData = await getPluginData(pluginResource);
    const currentVersion = await getCurrentVersion(pluginResource);
    return {
      id: Parser.getId(res.data.resource),
      version: Parser.getVersion(res.data.resource),
      currentVersion,
      createdOn: res.data.createdOn,
      description: res.data.description,
      lastModifiedOn: res.data.lastModifiedOn,
      name: res.data.name,
      resource: res.data.resource,
      pluginData: pluginData,
    };
  } catch (err) {
    console.error(`Failed to get plugin. Error: ${err.message}`);
    throw err;
  }
}

export interface IPluginExtensions {
  type: string;
  config: { uri: string };
  extensions: {
    [property: string]: { type: string; config: { uri: string } }[];
  };
}

export interface IPlugins {
  packageExtensions: IPluginExtensions[];
}

export interface IPluginsResponse {
  data: IPlugins;
}

export interface IChannel {}

export interface IBotData {
  packages: string[];
  channels: string[];
}

export interface IBotDataResponse {
  data: IBotData;
}

export interface IPackage extends IDetailedDescriptor {
  updatablePlugins?: string[];
  packageData?: IPlugins;
  pluginTypes?: IPluginTypes[];
  usedByBots?: string[];
}

export async function getPackageDescriptors(
  limit = DEFAULT_LIMIT,
  index = 0,
): Promise<IPackage[]> {
  try {
    const res: IDescriptorResponse = await axios.get(
      `${await getAPIUrl()}/packagestore/packages/descriptors?limit=${limit}&index=${index}`,
    );
    return Parser.getDetailedDescriptors(res, true);
  } catch (err) {
    console.error(`Failed to get package descriptors. Error: ${err.message}`);
    throw err;
  }
}

export async function getPackage(resource: string): Promise<IPackage> {
  try {
    const id = Parser.getId(resource);
    const version = Parser.getVersion(resource);
    const descriptor = await getDescriptor(id, version);
    const packageData: IPlugins = await getPackageData(resource);
    const currentVersion = await getCurrentVersion(resource);
    const pluginTypes = await getPluginTypes(resource);
    return {
      id,
      version,
      currentVersion,
      lastModifiedOn: descriptor.lastModifiedOn,
      name: descriptor.name,
      description: descriptor.description,
      resource: descriptor.resource,
      createdOn: descriptor.createdOn,
      packageData,
      pluginTypes,
    };
  } catch (err) {
    console.error(`Failed to get package. Error: ${err.message}`);
    throw err;
  }
}

export async function getCurrentPackage(id: string): Promise<IPackage> {
  try {
    const version = (await axios.get(
      `${await getAPIUrl()}/packagestore/packages/${id}/currentversion`,
    )).data;
    const descriptor = await getDescriptor(id, version);
    const pluginTypes: IPluginTypes[] = await getPluginTypes(
      descriptor.resource,
    );
    return {
      id,
      version,
      lastModifiedOn: descriptor.lastModifiedOn,
      currentVersion: version,
      name: descriptor.name,
      description: descriptor.description,
      resource: descriptor.resource,
      createdOn: descriptor.createdOn,
      pluginTypes,
    };
  } catch (err) {
    console.error(`Failed to get current package. Error: ${err.message}`);
    throw err;
  }
}

export async function getCurrentPlugin(resource: string): Promise<IPlugin> {
  try {
    let requestUri = `${await getAPIUrl()}${Parser.getApiPathWithIdAndVersion(
      resource,
    )}`;
    requestUri = `${requestUri.substring(
      0,
      requestUri.indexOf('?version'),
    )}/currentversion`;
    const version = await (await axios.get(requestUri)).data;
    const id = Parser.getId(resource);
    const descriptor = await getDescriptor(id, version);
    return {
      id,
      version,
      lastModifiedOn: descriptor.lastModifiedOn,
      currentVersion: version,
      name: descriptor.name,
      description: descriptor.description,
      resource: descriptor.resource,
      createdOn: descriptor.createdOn,
    };
  } catch (err) {
    console.error(`Failed to get current plugin. Error: ${err.message}`);
    throw err;
  }
}

export async function getCurrentVersion(resource: string): Promise<number> {
  try {
    const uri = `${await getAPIUrl()}${Parser.getApiPathWithIdAndVersion(
      resource,
    )}`;
    const requestUri =
      uri.substring(0, uri.indexOf('?version=')) + '/currentversion';
    const currentVersion = await axios.get(requestUri);
    return parseInt(currentVersion.data, 10);
  } catch (err) {
    console.error(`Failed to get current version. Error: ${err.message}`);
    throw err;
  }
}

export async function updateBot(
  currentBot: IBot,
  updatablePackageResource: string,
): Promise<IBot> {
  const currentBotUri = `${await getAPIUrl()}/botstore/bots/${
    currentBot.id
  }?version=${currentBot.version}`;
  try {
    const newPackage: IPackage = await getCurrentPackage(
      Parser.getId(updatablePackageResource),
    );
    const packages = currentBot.packages.map(pkg => {
      if (Parser.getId(pkg) === newPackage.id) {
        return newPackage.resource;
      }
      return pkg;
    });
    await axios.put(currentBotUri, {
      packages,
      channels: currentBot.channels,
    });
    const newBot: IBot = await getCurrentBot(currentBot.id);
    return newBot;
  } catch (err) {
    console.error(`Failed to update bot. Error: ${err.message}`);
    throw err;
  }
}

export async function updateBotPackages(
  currentBot: IBot,
  packages: string[],
): Promise<IBot> {
  try {
    const currentBotUri = `${await getAPIUrl()}/botstore/bots/${
      currentBot.id
    }?version=${currentBot.version}`;
    await axios.put(currentBotUri, { packages, channels: currentBot.channels });
    const newBot: IBot = await getCurrentBot(currentBot.id);
    return newBot;
  } catch (err) {
    console.error(`Failed to update bot packages. Error: ${err.message}`);
    throw err;
  }
}

export async function addPackageToBot(
  currentBot: IBot,
  packageResource: string,
): Promise<IBot> {
  try {
    const currentBotUri = `${await getAPIUrl()}/botstore/bots/${
      currentBot.id
    }?version=${currentBot.version}`;
    let botData: IBotData;
    if (_.isUndefined(currentBot.packages)) {
      botData = await getBotData(currentBot.resource);
    } else {
      botData = {
        packages: currentBot.packages,
        channels: currentBot.channels,
      };
    }
    botData.packages.push(packageResource);
    const updatedBot: IBot = await getCurrentBot(currentBot.id);
    return updatedBot;
  } catch (err) {
    console.error(`Failed to add package to bot. Error: ${err.message}`);
    throw err;
  }
}

export async function updateResourcesInBot(
  botResource: string,
  packageResources: string[],
): Promise<IBot> {
  try {
    const currentBotUri = `${await getAPIUrl()}/botstore/bots/${Parser.getIdAndVersion(
      botResource,
    )}`;
    const oldBot: IBot = await getCurrentBot(Parser.getId(botResource));
    const newBotPackageList = oldBot.packages.map(pkg => {
      return (
        packageResources.find(
          resource => Parser.getId(resource) === Parser.getId(pkg),
        ) || pkg
      );
    });
    await axios.put(currentBotUri, {
      packages: newBotPackageList,
      channels: oldBot.channels,
    });
    const newBot: IBot = await getCurrentBot(Parser.getId(botResource));
    return newBot;
  } catch (err) {
    console.error(`Failed to update resources in bot. Error: ${err.message}`);
    throw err;
  }
}

interface IBotToUpdate {
  botResource: string;
  packageResources: string[];
}

export async function updateBots(bots: IBotToUpdate[]) {
  try {
    const newBots: IBot[] = [];
    for (let i = 0; i < _.size(bots); i++) {
      const newBot: IBot = await updateResourcesInBot(
        bots[i].botResource,
        bots[i].packageResources,
      );
      newBots.push(newBot);
    }
    return newBots;
  } catch (err) {
    console.error(`Failed to update bots. Error: ${err.message}`);
    throw err;
  }
}

export function updatePackageExtension(
  externalPackages: IPluginExtensions[],
  newExtensionResource: string,
) {
  const newExtensionId = Parser.getId(newExtensionResource);
  const updatedExternalPackages = externalPackages.map(externalPackage => {
    if (
      externalPackage.config &&
      externalPackage.config.uri &&
      Parser.getId(externalPackage.config.uri) === newExtensionId
    ) {
      externalPackage.config.uri = newExtensionResource;
    }
    if (!_.isEmpty(externalPackage.extensions)) {
      for (let extensions of Object.values(externalPackage.extensions)) {
        for (let extension of extensions) {
          if (
            extension.config &&
            extension.config.uri &&
            Parser.getId(extension.config.uri) === newExtensionId
          ) {
            extension.config.uri = newExtensionResource;
          }
        }
      }
    }
    return externalPackage;
  });
  return updatedExternalPackages;
}

export async function updatePackage(
  currentPackage: IPackage,
  updatablePluginResource: string,
): Promise<IPackage> {
  try {
    const currentPackageUri = `${await getAPIUrl()}/packagestore/packages/${
      currentPackage.id
    }?version=${currentPackage.version}`;
    const packageData: IPlugins = (await axios.get(currentPackageUri)).data;
    const newPlugin = await getCurrentPlugin(updatablePluginResource);
    const newPackageData = await updatePackageExtension(
      packageData.packageExtensions,
      newPlugin.resource,
    );
    await axios.put(currentPackageUri, {
      packageExtensions: newPackageData,
    });
    const newPackage = await getCurrentPackage(currentPackage.id);
    return newPackage;
  } catch (err) {
    console.error(`Failed to update package. Error: ${err.message}`);
    throw err;
  }
}

export async function patchDescriptor(
  resource: string,
  name: string,
  description: string,
) {
  try {
    const res = await axios.patch(
      `${await getAPIUrl()}/descriptorstore/descriptors/${Parser.getIdAndVersion(
        resource,
      )}`,
      {
        operation: 'SET',
        document: {
          name: name,
          description: description,
        },
      },
    );
    return res.config.url;
  } catch (err) {
    console.error(`Failed to patch bot. Error: ${err.message}`);
    throw err;
  }
}

export interface IResponse {
  data: string;
  status: number;
  statusText: string;
  headers: { location: string };
  config: {};
}

export async function createNewBot(name: string, description: string) {
  try {
    const response: IResponse = await postJsonHelper('/botstore/bots', {});
    const resource = response.headers.location;
    await patchDescriptor(resource, name, description);
    return Parser.getId(response.headers.location);
  } catch (err) {
    console.error(`Failed to create bot. Error: ${err.message}`);
    throw err;
  }
}

export async function createNewPackage(
  name: string,
  description: string,
  extensions: object,
) {
  try {
    const response: IResponse = await postJsonHelper(
      '/packagestore/packages/',
      { packageExtensions: extensions },
    );
    const resource = response.headers.location;
    patchDescriptor(resource, name, description);
    return Parser.getId(resource);
  } catch (err) {
    console.error(`Failed to create package. Error: ${err.message}`);
    throw err;
  }
}

export async function addPluginType(resource: string, extensions: object) {
  try {
    await putHelper(resource, Parser.getApiPath(resource), {
      packageExtensions: extensions,
    });
    const newPackage: IPackage = await getCurrentPackage(
      Parser.getId(resource),
    );
    return newPackage;
  } catch (err) {
    console.error(`Failed to save plugins. Error: ${err.message}`);
    throw err;
  }
}

export interface IDefaultPluginTypes {
  configs: {};
  displayName: string;
  extensions: {};
  type: string;
}

export interface IExtensionsResponse {
  data: IDefaultPluginTypes[];
}

export async function getAllDefaultPluginTypes(): Promise<
  IDefaultPluginTypes[]
> {
  try {
    const res: IExtensionsResponse = await axios.get(
      `${await getAPIUrl()}/extensionstore/extensions`,
    );
    return res.data;
  } catch (err) {
    console.error(`Failed to get extensions. Error: ${err.message}`);
    throw err;
  }
}

export interface IPluginTypes {
  type: string;
  extensions?: IPluginTypes[];
  resource: string;
}

export const getPluginTypes: (
  resource: string,
) => Promise<IPluginTypes[]> = async resource => {
  try {
    const res: IPluginsResponse = await axios.get(
      `${await getAPIUrl()}/packagestore/packages/${Parser.getIdAndVersion(
        resource,
      )}`,
    );
    if (!res.data) {
      return [];
    } else {
      return parsePluginExtensions(res.data.packageExtensions);
    }
  } catch (e) {
    console.error(e);
  }
};

export interface IPluginExtension {
  type: string;
  resource?: string;
  extensions?: IPluginExtension[];
}

export async function getPluginDescriptors(
  pluginType: string,
  limit: number,
  index: number,
): Promise<IPlugin[]> {
  try {
    let res: IDescriptorResponse;
    switch (pluginType) {
      case BEHAVIOR:
        res = await axios.get(
          `${await getAPIUrl()}${BEHAVIOR_PATH}/descriptors?index=${index}&limit=${limit}`,
        );
        break;

      case OUTPUT:
        res = await axios.get(
          `${await getAPIUrl()}${OUTPUT_PATH}/descriptors?index=${index}&limit=${limit}`,
        );
        break;

      case REGULAR_DICTIONARY:
        res = await axios.get(
          `${await getAPIUrl()}${REGULAR_DICTIONARY_PATH}/descriptors?index=${index}&limit=${limit}`,
        );
        break;

      case HTTPCALLS:
        res = await axios.get(
          `${await getAPIUrl()}${HTTPCALLS_PATH}/descriptors?index=${index}&limit=${limit}`,
        );
        break;

      default:
        res = null;
    }
    if (res !== null) {
      return res.data.map(pkg => {
        const version = Parser.getVersion(pkg.resource);
        return {
          createdOn: pkg.createdOn,
          description: pkg.description,
          id: Parser.getId(pkg.resource),
          lastModifiedOn: pkg.lastModifiedOn,
          name: pkg.name,
          resource: pkg.resource,
          version,
          currentVersion: version,
        };
      });
    } else {
      return null;
    }
  } catch (err) {
    console.error(`Failed to get plugin descriptors. Error: ${err.message}`);
    throw err;
  }
}

export async function getBotsUsingPackage(
  packageResource: string,
  usingOldVersions = false,
): Promise<IBot[]> {
  try {
    const config = {
      headers: { Accept: 'application/json', 'Content-Type': 'text/plain' },
    };
    const res: IDescriptorResponse = await axios.post(
      `${await getAPIUrl()}/botstore/bots/descriptors?limit=500&includePreviousVersions=${usingOldVersions}`,
      packageResource,
      config,
    );
    return Parser.getDetailedDescriptors(res, true);
  } catch (err) {
    console.error(
      `Failed to get bots using this package. Error: ${err.message}`,
    );
    throw err;
  }
}

export async function getPackagesUsingPlugin(
  pluginResource: string,
  usingOldVersions = false,
): Promise<IPackage[]> {
  try {
    const config = {
      headers: { Accept: 'application/json', 'Content-Type': 'text/plain' },
    };
    const res = await axios.post(
      `${await getAPIUrl()}/packagestore/packages/descriptors?limit=500&includePreviousVersions=${usingOldVersions}`,
      pluginResource,
      config,
    );
    return Parser.getDetailedDescriptors(res, true);
  } catch (err) {
    console.error(
      `Failed to get packages using this plugin. Error: ${err.message}`,
    );
    throw err;
  }
}

export async function updateJsonData(resource: string, data: string) {
  try {
    await axios.put(
      `${await getAPIUrl()}${Parser.getApiPathWithIdAndVersion(resource)}`,
      data,
    );
  } catch (err) {
    console.error(`Failed to update JSON data. Error: ${err.message}`);
    throw err;
  }
}

export async function postNewConfig(
  type: string,
  name: string,
  description: string,
  data: string,
) {
  let configPath: string;
  switch (type) {
    case REGULAR_DICTIONARY:
      configPath = REGULAR_DICTIONARY_PATH;
      break;
    case BEHAVIOR:
      configPath = BEHAVIOR_PATH;
      break;
    case OUTPUT:
      configPath = OUTPUT_PATH;
      break;
    case BOT:
      configPath = BOT_PATH;
      break;
    case PACKAGE:
      configPath = PACKAGE_PATH;
      break;
    case HTTPCALLS:
      configPath = HTTPCALLS_PATH;
      break;

    default:
      console.error(`Could not create new config of type: ${type}`);
  }
  try {
    const response: IResponse = await postJsonHelper(
      configPath,
      JSON.parse(data),
    );
    const resource = response.headers.location;
    await patchDescriptor(resource, name, description);
    return resource;
  } catch (err) {
    console.error(`Failed to create new config. Error: ${err.message}`);
    throw err;
  }
}

export async function updatePackages(
  pluginResource: string,
  packages: IPackage[],
) {
  try {
    const updatedPackages: IPackage[] = [];
    for (let i = 0; i < _.size(packages); i++) {
      const updatedPackage: IPackage = await updatePackage(
        packages[i],
        pluginResource,
      );
      updatedPackages.push(updatedPackage);
    }
    return updatedPackages;
  } catch (err) {
    console.error(`Failed to update packages. Error: ${err.message}`);
    throw err;
  }
}

export async function getDeploymentStatus(resource: string) {
  try {
    const res = await axios.get(
      `${await getAPIUrl()}/administration/unrestricted/deploymentstatus/${Parser.getIdAndVersion(
        resource,
      )}`,
    );
    return res.data;
  } catch (err) {
    console.error(`Failed to get deployment status. Error: ${err.message}`);
    throw err;
  }
}

export async function deployBot(resource: string) {
  try {
    await axios.post(
      `${await getAPIUrl()}/administration/unrestricted/deploy/${Parser.getIdAndVersion(
        resource,
      )}`,
    );
  } catch (err) {
    console.error(`Failed to deploy bot. Error: ${err.message}`);
    throw err;
  }
}

export async function undeployBot(resource: string) {
  const res = await axios.post(
    `${await getAPIUrl()}/administration/unrestricted/undeploy/${Parser.getIdAndVersion(
      resource,
    )}`,
  );
  return res;
}
