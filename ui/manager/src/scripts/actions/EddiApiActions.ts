import { Action } from 'redux';
import {
  FETCH_BOT,
  FETCH_BOT_FAILED,
  FETCH_BOT_SUCCESS,
  FETCH_BOTS,
  FETCH_BOTS_FAILED,
  FETCH_BOTS_SUCCESS,
  FETCH_PACKAGEDATA,
  FETCH_PACKAGEDATA_FAILED,
  FETCH_PACKAGEDATA_SUCCESS,
  FETCH_BOTDATA,
  FETCH_BOTDATA_FAILED,
  FETCH_BOTDATA_SUCCESS,
  FETCH_PACKAGE,
  FETCH_PACKAGE_FAILED,
  FETCH_PACKAGE_SUCCESS,
  FETCH_PLUGIN,
  FETCH_PLUGIN_FAILED,
  FETCH_PLUGIN_SUCCESS,
  FETCH_DEFAULT_PLUGIN_TYPES,
  FETCH_DEFAULT_PLUGIN_TYPES_FAILED,
  FETCH_DEFAULT_PLUGIN_TYPES_SUCCESS,
  UPDATE_BOT,
  UPDATE_BOT_FAILED,
  UPDATE_BOT_SUCCESS,
  UPDATE_BOT_PACKAGES,
  UPDATE_BOT_PACKAGES_SUCCESS,
  UPDATE_BOT_PACKAGES_FAILED,
  ADD_AVAILABLE_UPDATE_FOR_PACKAGE,
  UPDATE_PACKAGE,
  UPDATE_PACKAGE_SUCCESS,
  UPDATE_PACKAGE_FAILED,
  UPDATE_DESCRIPTOR,
  UPDATE_DESCRIPTOR_FAILED,
  UPDATE_DESCRIPTOR_SUCCESS,
  FETCH_CURRENT_PACKAGE,
  FETCH_PACKAGES,
  FETCH_PACKAGES_SUCCESS,
  FETCH_PACKAGES_FAILED,
  FETCH_PLUGINS,
  FETCH_PLUGINS_SUCCESS,
  FETCH_PLUGINS_FAILED,
  FETCH_BOTS_USING_PACKAGE,
  FETCH_BOTS_USING_PACKAGE_SUCCESS,
  FETCH_BOTS_USING_PACKAGE_FAILED,
  FETCH_PACKAGES_USING_PLUGIN,
  FETCH_PACKAGES_USING_PLUGIN_SUCCESS,
  FETCH_PACKAGES_USING_PLUGIN_FAILED,
  UPDATE_JSON_DATA,
  UPDATE_JSON_DATA_FAILED,
  UPDATE_PLUGIN_SUCCESS,
  CREATE_NEW_CONFIG,
  CREATE_NEW_CONFIG_FAILED,
  CREATE_NEW_BOT_SUCCESS,
  CREATE_NEW_PACKAGE_SUCCESS,
  CREATE_NEW_PLUGIN_SUCCESS,
  UPDATE_PACKAGES,
  UPDATE_PACKAGES_FAILED,
  UPDATE_PACKAGES_SUCCESS,
  UPDATE_BOTS,
  UPDATE_BOTS_SUCCESS,
  UPDATE_BOTS_FAILED,
  DEPLOY_BOT,
  DEPLOY_BOT_SUCCESS,
  DEPLOY_BOT_FAILED,
  UNDEPLOY_BOT,
  UNDEPLOY_BOT_SUCCESS,
  UNDEPLOY_BOT_FAILED,
  FETCH_BOT_DEPLOYMENT_STATUS,
  FETCH_BOT_DEPLOYMENT_STATUS_SUCCESS,
  FETCH_BOT_DEPLOYMENT_STATUS_FAILED,
  FETCH_CURRENT_BOT,
  CREATE_NEW_BOT,
  CREATE_NEW_BOT_FAILED,
  CREATE_NEW_PACKAGE,
  CREATE_NEW_PACKAGE_FAILED,
  ADD_NEW_PACKAGE_TO_BOTS,
  ADD_NEW_PACKAGE_TO_BOTS_SUCCESS,
  ADD_NEW_PACKAGE_TO_BOTS_FAILED,
  FETCH_JSON_SCHEMA,
  FETCH_JSON_SCHEMA_FAILED,
  FETCH_BOT_JSON_SCHEMA_SUCCESS,
  FETCH_PACKAGE_JSON_SCHEMA_SUCCESS,
  FETCH_PLUGIN_JSON_SCHEMA_SUCCESS,
  DUPLICATE,
  DUPLICATE_SUCCESS,
  DUPLICATE_FAILED,
  FETCH_CONVERSATIONS,
  FETCH_CONVERSATIONS_SUCCESS,
  FETCH_CONVERSATIONS_FAILED,
} from './EddiApiActionTypes';
import {
  IBot,
  IBotData,
  IConversation,
  IDefaultPluginTypes,
  IEddiSchema,
  IPackage,
  IPlugin,
  IPluginsResponse,
} from '../components/utils/AxiosFunctions';

export interface IFetchBotsAction extends Action {
  limit: number;
  index: number;
}

export function fetchBotsAction(
  limit: number,
  index: number,
): IFetchBotsAction {
  return {
    limit,
    index,
    type: FETCH_BOTS,
  };
}

export interface IFetchBotsSuccessAction extends Action {
  bots: IBot[];
  limit: number;
  index: number;
}

export function fetchBotsSuccessAction(
  bots: IBot[],
  limit: number,
  index: number,
): IFetchBotsSuccessAction {
  return {
    limit,
    bots,
    index,
    type: FETCH_BOTS_SUCCESS,
  };
}

export interface IFetchBotsFailedAction extends Action {
  error: Error;
}

export function fetchBotsFailedAction(error: Error): IFetchBotsFailedAction {
  return {
    error,
    type: FETCH_BOTS_FAILED,
  };
}

export interface IFetchBotAction extends Action {
  botId: string;
}

export function fetchBotAction(botId: string): IFetchBotAction {
  return {
    botId,
    type: FETCH_BOT,
  };
}

export interface IFetchCurrentBotAction extends Action {
  botId: string;
}

export function fetchCurrentBotAction(botId: string): IFetchCurrentBotAction {
  return {
    botId,
    type: FETCH_CURRENT_BOT,
  };
}

export interface IFetchBotSuccessAction extends Action {
  bot: IBot;
}

export function fetchBotSuccessAction(bot: IBot): IFetchBotSuccessAction {
  return {
    bot,
    type: FETCH_BOT_SUCCESS,
  };
}

export interface IFetchBotFailedAction extends Action {
  error: Error;
}

export function fetchBotFailedAction(error: Error): IFetchBotFailedAction {
  return {
    error,
    type: FETCH_BOT_FAILED,
  };
}

export interface IFetchBotDataAction extends Action {
  botResource: string;
}

export function fetchBotDataAction(botResource: string): IFetchBotDataAction {
  return {
    botResource,
    type: FETCH_BOTDATA,
  };
}

export interface IFetchBotDataSuccessAction extends Action {
  botData: IBotData;
  botResource: string;
}

export function fetchBotDataSuccessAction(
  botData: IBotData,
  botResource: string,
): IFetchBotDataSuccessAction {
  return {
    botResource,
    botData,
    type: FETCH_BOTDATA_SUCCESS,
  };
}

export interface IFetchBotDataFailedAction extends Action {
  error: Error;
}

export function fetchBotDataFailedAction(
  error: Error,
): IFetchBotDataFailedAction {
  return {
    error,
    type: FETCH_BOTDATA_FAILED,
  };
}

export interface IFetchPackagesAction extends Action {
  limit: number;
  index: number;
}

export function fetchPackagesAction(
  limit: number,
  index: number,
): IFetchPackagesAction {
  return {
    limit,
    index,
    type: FETCH_PACKAGES,
  };
}

export interface IFetchPackagesSuccessAction extends Action {
  packages: IPackage[];
  limit: number;
  index: number;
}

export function fetchPackagesSuccessAction(
  packages: IPackage[],
  limit: number,
  index: number,
): IFetchPackagesSuccessAction {
  return {
    packages,
    limit,
    index,
    type: FETCH_PACKAGES_SUCCESS,
  };
}

export interface IFetchPackagesFailedAction extends Action {
  error: Error;
}

export function fetchPackagesFailedAction(
  error: Error,
): IFetchPackagesFailedAction {
  return {
    error,
    type: FETCH_PACKAGES_FAILED,
  };
}

export interface IFetchPackageAction extends Action {
  packageResource: string;
}

export function fetchPackageAction(
  packageResource: string,
): IFetchPackageAction {
  return {
    packageResource,
    type: FETCH_PACKAGE,
  };
}

export interface IFetchPackageSuccessAction extends Action {
  package: IPackage;
}

export function fetchPackageSuccessAction(
  pkg: IPackage,
): IFetchPackageSuccessAction {
  return {
    package: pkg,
    type: FETCH_PACKAGE_SUCCESS,
  };
}

export interface IFetchPackageFailedAction extends Action {
  error: Error;
}

export function fetchPackageFailedAction(
  error: Error,
): IFetchPackageFailedAction {
  return {
    error,
    type: FETCH_PACKAGE_FAILED,
  };
}

export interface IFetchCurrentPackageAction extends Action {
  packageId: string;
}

export function fetchCurrentPackageAction(
  packageId: string,
): IFetchCurrentPackageAction {
  return {
    type: FETCH_CURRENT_PACKAGE,
    packageId,
  };
}

export interface IFetchDefaultPluginTypesAction extends Action {}

export function fetchDefaultPluginTypesAction(): IFetchDefaultPluginTypesAction {
  return {
    type: FETCH_DEFAULT_PLUGIN_TYPES,
  };
}

export interface IFetchDefaultPluginTypesSuccessAction extends Action {
  defaultPluginTypes: IDefaultPluginTypes[];
}

export function fetchDefaultPluginTypesSuccessAction(
  defaultPluginTypes: IDefaultPluginTypes[],
): IFetchDefaultPluginTypesSuccessAction {
  return {
    defaultPluginTypes,
    type: FETCH_DEFAULT_PLUGIN_TYPES_SUCCESS,
  };
}

export interface IFetchDefaultPluginTypesFailedAction extends Action {
  error: Error;
}

export function fetchDefaultPluginTypesFailedAction(
  error: Error,
): IFetchDefaultPluginTypesFailedAction {
  return {
    error,
    type: FETCH_DEFAULT_PLUGIN_TYPES_FAILED,
  };
}

export interface IFetchPluginsAction extends Action {
  pluginType: string;
  limit: number;
  index: number;
}

export function fetchPluginsAction(
  pluginType: string,
  limit: number,
  index: number,
): IFetchPluginsAction {
  return {
    pluginType,
    limit,
    index,
    type: FETCH_PLUGINS,
  };
}

export interface IFetchPluginsSuccessAction extends Action {
  plugins: IPlugin[];
  pluginType: string;
  limit: number;
  index: number;
}

export function fetchPluginsSuccessAction(
  plugins: IPlugin[],
  pluginType: string,
  limit: number,
  index: number,
): IFetchPluginsSuccessAction {
  return {
    plugins,
    pluginType,
    limit,
    index,
    type: FETCH_PLUGINS_SUCCESS,
  };
}

export interface IFetchPluginsFailedAction extends Action {
  error: Error;
}

export function fetchPluginsFailedAction(
  error: Error,
): IFetchPluginsFailedAction {
  return {
    error,
    type: FETCH_PLUGINS_FAILED,
  };
}

export interface IFetchPluginAction extends Action {
  pluginResource: string;
}

export function fetchPluginAction(pluginResource: string): IFetchPluginAction {
  return {
    pluginResource,
    type: FETCH_PLUGIN,
  };
}

export interface IFetchPluginSuccessAction extends Action {
  plugin: IPlugin;
}

export function fetchPluginSuccessAction(
  plugin: IPlugin,
): IFetchPluginSuccessAction {
  return {
    plugin,
    type: FETCH_PLUGIN_SUCCESS,
  };
}

export interface IFetchPluginFailedAction extends Action {
  error: Error;
}

export function fetchPluginFailedAction(
  error: Error,
): IFetchPluginFailedAction {
  return {
    error,
    type: FETCH_PLUGIN_FAILED,
  };
}

export interface IUpdateDescriptorAction extends Action {
  resource: string;
  name: string;
  description: string;
}

export function updateDescriptorAction(
  resource: string,
  name: string,
  description: string,
): IUpdateDescriptorAction {
  return {
    resource,
    name,
    description,
    type: UPDATE_DESCRIPTOR,
  };
}

export interface IUpdateDescriptorSuccessAction extends Action {
  resource: string;
  name: string;
  description: string;
}
export function updateDescriptorSuccessAction(
  resource: string,
  name: string,
  description: string,
): IUpdateDescriptorSuccessAction {
  return {
    resource,
    name,
    description,
    type: UPDATE_DESCRIPTOR_SUCCESS,
  };
}

export interface IUpdateDescriptorFailedAction extends Action {
  error: Error;
}
export function updateDescriptorFailedAction(
  error: Error,
): IUpdateDescriptorFailedAction {
  return {
    error,
    type: UPDATE_DESCRIPTOR_FAILED,
  };
}

export interface IUpdateBotAction extends Action {
  bot: IBot;
  package: IPackage;
}

export function updateBotAction(bot: IBot, pkg: IPackage): IUpdateBotAction {
  return {
    bot,
    package: pkg,
    type: UPDATE_BOT,
  };
}
export interface IUpdateBotSuccessAction extends Action {
  bot: IBot;
}
export function updateBotSuccessAction(bot: IBot): IUpdateBotSuccessAction {
  return {
    bot,
    type: UPDATE_BOT_SUCCESS,
  };
}
export interface IUpdateBotFailedAction extends Action {
  error: Error;
}
export function updateBotFailedAction(error: Error): IUpdateBotFailedAction {
  return {
    error,
    type: UPDATE_BOT_FAILED,
  };
}

export interface IUpdateBotPackagesAction extends Action {
  bot: IBot;
  packages: string[];
}

export function updateBotPackagesAction(
  bot: IBot,
  packages: string[],
): IUpdateBotPackagesAction {
  return {
    bot,
    packages,
    type: UPDATE_BOT_PACKAGES,
  };
}

export interface IUpdateBotPackagesSuccessAction extends Action {
  bot: IBot;
}
export function updateBotPackagesSuccessAction(
  bot: IBot,
): IUpdateBotPackagesSuccessAction {
  return {
    bot,
    type: UPDATE_BOT_PACKAGES_SUCCESS,
  };
}

export interface IAddAvailableUpdateForPackageAction extends Action {
  packageResource: string;
  pluginResource: string;
}

export interface IUpdateBotPackagesFailedAction extends Action {
  error: Error;
}
export function updateBotPackagesFailedAction(
  error: Error,
): IUpdateBotPackagesFailedAction {
  return {
    error,
    type: UPDATE_BOT_PACKAGES_FAILED,
  };
}

export interface IUpdatePackageAction extends Action {
  package: IPackage;
  plugin: IPlugin;
}

export function updatePackageAction(
  pkg: IPackage,
  plugin: IPlugin,
): IUpdatePackageAction {
  return {
    package: pkg,
    plugin,
    type: UPDATE_PACKAGE,
  };
}
export interface IUpdatePackageSuccessAction extends Action {
  package: IPackage;
}
export function updatePackageSuccessAction(
  pkg: IPackage,
): IUpdatePackageSuccessAction {
  return {
    package: pkg,
    type: UPDATE_PACKAGE_SUCCESS,
  };
}
export interface IUpdatePackageFailedAction extends Action {
  error: Error;
}
export function updatePackageFailedAction(
  error: Error,
): IUpdatePackageFailedAction {
  return {
    error,
    type: UPDATE_PACKAGE_FAILED,
  };
}
export function addAvailableUpdateForPackageAction(
  packageResource: string,
  pluginResource: string,
): IAddAvailableUpdateForPackageAction {
  return {
    packageResource,
    pluginResource,
    type: ADD_AVAILABLE_UPDATE_FOR_PACKAGE,
  };
}

export interface IFetchPackageDataAction extends Action {
  packageResource: string;
}

export function fetchPackageDataAction(
  packageResource: string,
): IFetchPackageDataAction {
  return {
    packageResource: packageResource,
    type: FETCH_PACKAGEDATA,
  };
}

export interface IFetchPackageDataSuccessAction extends Action {
  packageData: IPluginsResponse[];
  packageResource: string;
}

export function fetchPackageDataSuccessAction(
  packageData: IPluginsResponse[],
  packageResource: string,
): IFetchPackageDataSuccessAction {
  return {
    packageData,
    packageResource,
    type: FETCH_PACKAGEDATA_SUCCESS,
  };
}

export interface IFetchPackageDataFailedAction extends Action {
  error: Error;
}

export function fetchPackageDataFailedAction(
  error: Error,
): IFetchPackageDataFailedAction {
  return {
    error,
    type: FETCH_PACKAGEDATA_FAILED,
  };
}

export interface IFetchBotsUsingPackageAction extends Action {
  packageResource: string;
  anyVersion: boolean;
}

export function fetchBotsUsingPackageAction(
  packageResource: string,
  anyVersion: boolean,
): IFetchBotsUsingPackageAction {
  return {
    packageResource,
    anyVersion,
    type: FETCH_BOTS_USING_PACKAGE,
  };
}

export interface IFetchBotsUsingPackageSuccessAction extends Action {
  packageResource: string;
  anyVersion: boolean;
  bots: IBot[];
}

export function fetchBotsUsingPackageSuccessAction(
  packageResource: string,
  anyVersion: boolean,
  bots: IBot[],
): IFetchBotsUsingPackageSuccessAction {
  return {
    packageResource,
    anyVersion,
    bots,
    type: FETCH_BOTS_USING_PACKAGE_SUCCESS,
  };
}

export interface IFetchBotsUsingPackageFailedAction extends Action {
  error: Error;
}

export function fetchBotsUsingPackageFailedAction(
  error: Error,
): IFetchBotsUsingPackageFailedAction {
  return {
    error,
    type: FETCH_BOTS_USING_PACKAGE_FAILED,
  };
}

export interface IFetchPackagesUsingPluginAction extends Action {
  pluginResource: string;
  anyVersion: boolean;
}

export function fetchPackagesUsingPluginAction(
  pluginResource: string,
  anyVersion: boolean,
): IFetchPackagesUsingPluginAction {
  return {
    pluginResource,
    anyVersion,
    type: FETCH_PACKAGES_USING_PLUGIN,
  };
}

export interface IFetchPackagesUsingPluginSuccessAction extends Action {
  pluginResource: string;
  anyVersion: boolean;
  packages: IPackage[];
}

export function fetchPackagesUsingPluginSuccessAction(
  pluginResource: string,
  anyVersion: boolean,
  packages: IPackage[],
): IFetchPackagesUsingPluginSuccessAction {
  return {
    pluginResource,
    anyVersion,
    packages,
    type: FETCH_PACKAGES_USING_PLUGIN_SUCCESS,
  };
}

export interface IFetchPackagesUsingPluginFailedAction extends Action {
  error: Error;
}

export function fetchPackagesUsingPluginFailedAction(
  error: Error,
): IFetchPackagesUsingPluginFailedAction {
  return {
    error,
    type: FETCH_PACKAGES_USING_PLUGIN_FAILED,
  };
}

export interface IUpdateJsonDataAction extends Action {
  resource: string;
  data: string;
}

export function updateJsonDataAction(
  resource: string,
  data: string,
): IUpdateJsonDataAction {
  return {
    resource,
    data,
    type: UPDATE_JSON_DATA,
  };
}

export interface IUpdateJsonDataFailedAction extends Action {
  error: Error;
}

export function updateJsonDataFailedAction(
  error: Error,
): IUpdateJsonDataFailedAction {
  return {
    error,
    type: UPDATE_JSON_DATA_FAILED,
  };
}

export interface IUpdateJsonDataSuccessAction extends Action {
  resource: string;
  data: string;
}

export interface IUpdatePluginSuccessAction extends Action {
  plugin: IPlugin;
}

export function updatePluginSuccessAction(
  plugin: IPlugin,
): IUpdatePluginSuccessAction {
  return {
    plugin,
    type: UPDATE_PLUGIN_SUCCESS,
  };
}

export interface ICreateNewConfigAction extends Action {
  eddiType: string;
  name: string;
  description: string;
  data: string;
}

export function createNewConfigAction(
  eddiType: string,
  name: string,
  description: string,
  data: string,
): ICreateNewConfigAction {
  return {
    eddiType,
    name,
    description,
    data,
    type: CREATE_NEW_CONFIG,
  };
}

export interface ICreateNewConfigFailedAction extends Action {
  error: Error;
}

export function createNewConfigFailedAction(
  error: Error,
): ICreateNewConfigFailedAction {
  return {
    error,
    type: CREATE_NEW_CONFIG_FAILED,
  };
}

export interface ICreateNewBotAction extends Action {
  // todo : Remake this action so no api calls have to be called before this is action runs.
  botId: string;
}

export function createNewBotAction(botId: string): ICreateNewBotAction {
  return {
    botId,
    type: CREATE_NEW_BOT,
  };
}

export interface ICreateNewBotSuccessAction extends Action {
  bot: IBot;
}

export function createNewBotSuccessAction(
  bot: IBot,
): ICreateNewBotSuccessAction {
  return {
    bot,
    type: CREATE_NEW_BOT_SUCCESS,
  };
}

export interface ICreateNewBotFailedAction extends Action {
  error: Error;
}

export function createNewBotFailedAction(
  error: Error,
): ICreateNewBotFailedAction {
  return {
    error,
    type: CREATE_NEW_BOT_FAILED,
  };
}

export interface ICreateNewPackageAction extends Action {
  // todo : Remake this action so no api calls have to be called before this is action runs.
  packageId: string;
}

export function createNewPackageAction(
  packageId: string,
): ICreateNewPackageAction {
  return {
    packageId,
    type: CREATE_NEW_PACKAGE,
  };
}

export interface ICreateNewPackageSuccessAction extends Action {
  pkg: IPackage;
}

export function createNewPackageSuccessAction(
  pkg: IPackage,
): ICreateNewPackageSuccessAction {
  return {
    pkg,
    type: CREATE_NEW_PACKAGE_SUCCESS,
  };
}

export interface ICreateNewPackageFailedAction extends Action {
  error: Error;
}

export function createNewPackageFailedAction(
  error: Error,
): ICreateNewPackageFailedAction {
  return {
    error,
    type: CREATE_NEW_PACKAGE_FAILED,
  };
}

export interface ICreateNewPluginSuccessAction extends Action {
  plugin: IPlugin;
}

export function createNewPluginSuccessAction(
  plugin: IPlugin,
): ICreateNewPluginSuccessAction {
  return {
    plugin,
    type: CREATE_NEW_PLUGIN_SUCCESS,
  };
}

export interface IUpdatePackagesAction extends Action {
  pluginResource: string;
  packages: string[];
}

export function updatePackagesAction(
  pluginResource: string,
  packages: string[],
): IUpdatePackagesAction {
  return {
    pluginResource,
    packages,
    type: UPDATE_PACKAGES,
  };
}

export interface IUpdatePackagesSuccessAction extends Action {
  packages: IPackage[];
}

export function updatePackagesSuccessAction(
  packages: IPackage[],
): IUpdatePackagesSuccessAction {
  return {
    packages,
    type: UPDATE_PACKAGES_SUCCESS,
  };
}

export interface IUpdatePackagesFailedAction extends Action {
  error: Error;
}

export function updatePackagesFailedAction(
  error: Error,
): IUpdatePackagesFailedAction {
  return {
    error,
    type: UPDATE_PACKAGES_FAILED,
  };
}

interface IBotToUpdate {
  botResource: string;
  packageResources: string[];
}

export interface IUpdateBotsAction extends Action {
  bots: IBotToUpdate[];
}

export function updateBotsAction(bots: IBotToUpdate[]): IUpdateBotsAction {
  return {
    bots,
    type: UPDATE_BOTS,
  };
}

export interface IUpdateBotsSuccessAction extends Action {
  bots: IBot[];
}

export function updateBotsSuccessAction(
  bots: IBot[],
): IUpdateBotsSuccessAction {
  return {
    bots,
    type: UPDATE_BOTS_SUCCESS,
  };
}

export interface IUpdateBotsFailedAction extends Action {
  error: Error;
}

export function updateBotsFailedAction(error: Error): IUpdateBotsFailedAction {
  return {
    error,
    type: UPDATE_BOTS_FAILED,
  };
}

export interface IDeployBotAction extends Action {
  botResource: string;
}

export function deployBotAction(botResource: string): IDeployBotAction {
  return {
    botResource,
    type: DEPLOY_BOT,
  };
}

export interface IDeployBotSuccessAction extends Action {
  botResource: string;
  conversationUrl: string;
}

export function deployBotSuccessAction(
  botResource: string,
  conversationUrl: string,
): IDeployBotSuccessAction {
  return {
    botResource,
    conversationUrl,
    type: DEPLOY_BOT_SUCCESS,
  };
}

export interface IDeployBotFailedAction extends Action {
  error: Error;
}

export function deployBotFailedAction(error: Error): IDeployBotFailedAction {
  return {
    error,
    type: DEPLOY_BOT_FAILED,
  };
}

export interface IUndeployBotAction extends Action {
  botResource: string;
}

export function undeployBotAction(botResource: string): IUndeployBotAction {
  return {
    botResource,
    type: UNDEPLOY_BOT,
  };
}

export interface IUndeployBotSuccessAction extends Action {
  botResource: string;
}

export function undeployBotSuccessAction(
  botResource: string,
): IUndeployBotSuccessAction {
  return {
    botResource,
    type: UNDEPLOY_BOT_SUCCESS,
  };
}

export interface IUndeployBotFailedAction extends Action {
  error: Error;
  response: string;
}

export function undeployBotFailedAction(
  error: Error,
  response: string,
): IUndeployBotFailedAction {
  return {
    error,
    response,
    type: UNDEPLOY_BOT_FAILED,
  };
}

export interface IFetchBotDeploymentStatusAction extends Action {
  botResource: string;
}

export function fetchBotDeploymentStatusAction(
  botResource: string,
): IFetchBotDeploymentStatusAction {
  return {
    botResource,
    type: FETCH_BOT_DEPLOYMENT_STATUS,
  };
}

export interface IFetchBotDeploymentStatusSuccessAction extends Action {
  botResource: string;
  status: string;
}

export function fetchBotDeploymentStatusSuccessAction(
  botResource: string,
  status: string,
): IFetchBotDeploymentStatusSuccessAction {
  return {
    botResource,
    status,
    type: FETCH_BOT_DEPLOYMENT_STATUS_SUCCESS,
  };
}

export interface IFetchBotDeploymentStatusFailedAction extends Action {
  error: Error;
}

export function fetchBotDeploymentStatusFailedAction(
  error: Error,
): IFetchBotDeploymentStatusFailedAction {
  return {
    error,
    type: FETCH_BOT_DEPLOYMENT_STATUS_FAILED,
  };
}

export interface IAddNewPackageToBotsAction extends Action {
  packageResource: string;
  bots: IBot[];
}

export function addNewPackageToBotsAction(
  packageResource: string,
  bots: IBot[],
): IAddNewPackageToBotsAction {
  return {
    packageResource,
    bots,
    type: ADD_NEW_PACKAGE_TO_BOTS,
  };
}

export interface IAddNewPackageToBotsSuccessAction extends Action {
  packageResource: string;
  bots: IBot[];
}

export function addNewPackageToBotsSuccessAction(
  bots: IBot[],
  packageResource: string,
): IAddNewPackageToBotsSuccessAction {
  return {
    packageResource,
    bots,
    type: ADD_NEW_PACKAGE_TO_BOTS_SUCCESS,
  };
}

export interface IAddNewPackageToBotsFailedAction extends Action {
  error: Error;
}

export function addNewPackageToBotsFailedAction(
  error: Error,
): IAddNewPackageToBotsFailedAction {
  return {
    error,
    type: ADD_NEW_PACKAGE_TO_BOTS_FAILED,
  };
}

export interface IFetchJsonSchemaAction extends Action {
  eddiType: string;
}

export function fetchJsonSchemaAction(
  eddiType: string,
): IFetchJsonSchemaAction {
  return {
    eddiType,
    type: FETCH_JSON_SCHEMA,
  };
}

export interface IFetchJsonSchemaFailedAction extends Action {
  error: Error;
}

export function fetchJsonSchemaFailedAction(
  error: Error,
): IFetchJsonSchemaFailedAction {
  return {
    error,
    type: FETCH_JSON_SCHEMA_FAILED,
  };
}

export interface IFetchJsonSchemaSuccessAction extends Action {
  eddiType: string;
  schema: IEddiSchema;
}

export function fetchBotJsonSchemaSuccessAction(
  eddiType: string,
  schema: IEddiSchema,
): IFetchJsonSchemaSuccessAction {
  return {
    eddiType,
    schema,
    type: FETCH_BOT_JSON_SCHEMA_SUCCESS,
  };
}

export function fetchPackageJsonSchemaSuccessAction(
  eddiType: string,
  schema: IEddiSchema,
): IFetchJsonSchemaSuccessAction {
  return {
    eddiType,
    schema,
    type: FETCH_PACKAGE_JSON_SCHEMA_SUCCESS,
  };
}

export function fetchPluginJsonSchemaSuccessAction(
  eddiType: string,
  schema: IEddiSchema,
): IFetchJsonSchemaSuccessAction {
  return {
    eddiType,
    schema,
    type: FETCH_PLUGIN_JSON_SCHEMA_SUCCESS,
  };
}

export interface IDuplicateAction extends Action {
  resource: string;
  deepCopy: boolean;
}

export function duplicateAction(
  resource: string,
  deepCopy: boolean,
): IDuplicateAction {
  return {
    resource,
    deepCopy,
    type: DUPLICATE,
  };
}

export interface IDuplicateSuccessAction extends Action {
  bot: IBot;
  packages: IPackage[];
  plugins: IPlugin[];
}

export function duplicateSuccessAction(
  bot: IBot,
  packages: IPackage[],
  plugins: IPlugin[],
): IDuplicateSuccessAction {
  return {
    bot,
    packages,
    plugins,
    type: DUPLICATE_SUCCESS,
  };
}

export interface IDuplicateFailedAction extends Action {
  error: Error;
}

export function duplicateFailedAction(error: Error): IDuplicateFailedAction {
  return {
    error,
    type: DUPLICATE_FAILED,
  };
}

export interface IFetchConversationsAction extends Action {
  resource: string;
}

export function fetchConversationsAction(
  resource: string,
): IFetchConversationsAction {
  console.log('FETCHING CONVERSATIONS actions');
  return {
    resource,
    type: FETCH_CONVERSATIONS,
  };
}

export interface IFetchConversationsSuccessAction extends Action {
  resource: string;
  conversations: IConversation[];
}

export function fetchConversationsSuccessAction(
  resource: string,
  conversations: IConversation[],
): IFetchConversationsSuccessAction {
  return {
    resource,
    conversations,
    type: FETCH_CONVERSATIONS_SUCCESS,
  };
}

export interface IFetchConversationsFailedAction extends Action {
  error: Error;
}

export function fetchConversationsFailedAction(
  error: Error,
): IFetchConversationsFailedAction {
  return {
    error,
    type: FETCH_CONVERSATIONS_FAILED,
  };
}
