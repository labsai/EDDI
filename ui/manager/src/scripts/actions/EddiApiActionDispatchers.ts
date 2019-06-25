import { bindActionCreators, ActionCreatorsMapObject } from 'redux';
import { store } from '../store/store';
import {
  fetchBotAction,
  fetchBotsAction,
  fetchBotDataAction,
  fetchPackagesAction,
  fetchPackageAction,
  fetchPluginAction,
  fetchDefaultPluginTypesAction,
  fetchPackageDataAction,
  updateBotAction,
  updateBotPackagesAction,
  updatePackageAction,
  addAvailableUpdateForPackageAction,
  updateDescriptorAction,
  IFetchBotAction,
  IFetchBotsAction,
  IFetchBotDataAction,
  IFetchPackagesAction,
  IFetchPackageAction,
  IFetchPluginAction,
  IFetchDefaultPluginTypesAction,
  IFetchPackageDataAction,
  IUpdateBotAction,
  IUpdateBotPackagesAction,
  IUpdatePackageAction,
  IAddAvailableUpdateForPackageAction,
  IUpdateDescriptorAction,
  IFetchCurrentPackageAction,
  fetchCurrentPackageAction,
  fetchPluginsAction,
  IFetchPluginsAction,
  IFetchBotsUsingPackageAction,
  fetchBotsUsingPackageAction,
  IFetchPackagesUsingPluginAction,
  fetchPackagesUsingPluginAction,
  updateJsonDataAction,
  IUpdateJsonDataAction,
  ICreateNewConfigAction,
  createNewConfigAction,
  IUpdatePackagesAction,
  IUpdatePackagesSuccessAction,
  IUpdatePackagesFailedAction,
  updatePackagesAction,
  updatePackagesSuccessAction,
  updatePackagesFailedAction,
  IUpdateBotsAction,
  IUpdateBotsSuccessAction,
  IUpdateBotsFailedAction,
  updateBotsSuccessAction,
  updateBotsAction,
  updateBotsFailedAction,
  IDeployBotAction,
  IDeployBotSuccessAction,
  IDeployBotFailedAction,
  deployBotAction,
  deployBotSuccessAction,
  deployBotFailedAction,
  undeployBotFailedAction,
  undeployBotSuccessAction,
  undeployBotAction,
  IFetchBotDeploymentStatusAction,
  IFetchBotDeploymentStatusSuccessAction,
  IFetchBotDeploymentStatusFailedAction,
  fetchBotDeploymentStatusAction,
  fetchBotDeploymentStatusFailedAction,
  fetchBotDeploymentStatusSuccessAction,
  ICreateNewPluginSuccessAction,
  createNewPluginSuccessAction,
  IFetchCurrentBotAction,
  fetchCurrentBotAction,
  ICreateNewBotAction,
  createNewBotAction,
  createNewBotSuccessAction,
  createNewBotFailedAction,
  createNewPackageAction,
  createNewPackageSuccessAction,
  createNewPackageFailedAction,
  ICreateNewPackageAction,
  IAddNewPackageToBotsAction,
  addNewPackageToBotsAction,
  IAddNewPackageToBotsSuccessAction,
  IAddNewPackageToBotsFailedAction,
  addNewPackageToBotsSuccessAction,
  addNewPackageToBotsFailedAction,
  IFetchJsonSchemaAction,
  IFetchJsonSchemaSuccessAction,
  fetchBotJsonSchemaSuccessAction,
  fetchJsonSchemaAction,
  fetchPackageJsonSchemaSuccessAction,
  fetchPluginJsonSchemaSuccessAction,
  IDuplicateAction,
  duplicateAction,
  IDuplicateSuccessAction,
  IDuplicateFailedAction,
  duplicateSuccessAction,
  duplicateFailedAction,
} from './EddiApiActions';

export interface IEddiApiActionDispatchers extends ActionCreatorsMapObject {
  fetchBotAction: (botId) => IFetchBotAction;
  fetchCurrentBotAction: (botId) => IFetchCurrentBotAction;
  fetchBotsAction: (limit, index) => IFetchBotsAction;
  fetchBotDataAction: (botResource) => IFetchBotDataAction;
  fetchPackagesAction: (limit, index) => IFetchPackagesAction;
  fetchPackageAction: (packageResource) => IFetchPackageAction;
  fetchCurrentPackageAction: (packageId) => IFetchCurrentPackageAction;
  fetchPackageDataAction: (packageResource) => IFetchPackageDataAction;
  fetchDefaultPluginTypesAction: () => IFetchDefaultPluginTypesAction;
  fetchPluginsAction: (pluginType, limit, index) => IFetchPluginsAction;
  fetchPluginAction: (pluginResource) => IFetchPluginAction;
  updateBotAction: (bot, packageIndex) => IUpdateBotAction;
  updateBotPackagesAction: (bot, packages) => IUpdateBotPackagesAction;
  updateDescriptorAction: (
    resource,
    name,
    description,
  ) => IUpdateDescriptorAction;
  updatePackageAction: (pack, plugin) => IUpdatePackageAction;
  addAvailableUpdateForPackageAction: (
    packageResource,
    pluginResource,
  ) => IAddAvailableUpdateForPackageAction;
  fetchBotsUsingPackageAction: (
    packageResource,
    anyVersion,
  ) => IFetchBotsUsingPackageAction;
  fetchPackagesUsingPluginAction: (
    pluginResource,
    anyVersion,
  ) => IFetchPackagesUsingPluginAction;
  updateJsonDataAction: (resource, data) => IUpdateJsonDataAction;
  createNewConfigAction: (
    eddiType,
    name,
    description,
    data,
  ) => ICreateNewConfigAction;
  createNewPluginSuccessAction: (plugin) => ICreateNewPluginSuccessAction;
  createNewBotAction: (botId) => ICreateNewBotAction;
  createNewPackageAction: (pkg) => ICreateNewPackageAction;
  updatePackagesAction: (pluginResource, packages) => IUpdatePackagesAction;
  updatePackagesSuccessAction: (packages) => IUpdatePackagesSuccessAction;
  updatePackagesFailedAction: (error) => IUpdatePackagesFailedAction;
  updateBotsAction: (bots) => IUpdateBotsAction;
  updateBotsSuccessAction: (bots) => IUpdateBotsSuccessAction;
  updateBotsFailedAction: (error) => IUpdateBotsFailedAction;
  deployBotAction: (botResource) => IDeployBotAction;
  deployBotSuccessAction: (
    botResource,
    conversationUrl,
  ) => IDeployBotSuccessAction;
  deployBotFailedAction: (error) => IDeployBotFailedAction;
  fetchBotDeploymentStatusAction: (
    botResource,
  ) => IFetchBotDeploymentStatusAction;
  fetchBotDeploymentStatusSuccessAction: (
    botResource,
    status,
  ) => IFetchBotDeploymentStatusSuccessAction;
  fetchBotDeploymentStatusFailedAction: (
    error,
  ) => IFetchBotDeploymentStatusFailedAction;
  addNewPackageToBotsAction: (
    packageResource,
    bots,
  ) => IAddNewPackageToBotsAction;
  addNewPackageToBotsSuccessAction: (
    packageResource,
    bots,
  ) => IAddNewPackageToBotsSuccessAction;
  addNewPackageToBotsFailedAction: (error) => IAddNewPackageToBotsFailedAction;
  fetchJsonSchemaAction: (eddiType) => IFetchJsonSchemaAction;
  fetchBotJsonSchemaSuccessAction: (
    eddiType,
    schema,
  ) => IFetchJsonSchemaSuccessAction;
  fetchPackageJsonSchemaSuccessAction: (
    eddiType,
    schema,
  ) => IFetchJsonSchemaSuccessAction;
  fetchPluginJsonSchemaSuccessAction: (
    eddiType,
    schema,
  ) => IFetchJsonSchemaSuccessAction;
  duplicateAction: (resource, deepCopy) => IDuplicateAction;
  duplicateSuccessAction: (bot, packages, plugins) => IDuplicateSuccessAction;
  duplicateFailedAction: (error) => IDuplicateFailedAction;
}

const actions: IEddiApiActionDispatchers = {
  fetchBotAction,
  fetchCurrentBotAction,
  fetchBotsAction,
  fetchBotDataAction,
  fetchPackagesAction,
  fetchPackageAction,
  fetchCurrentPackageAction,
  fetchPackageDataAction,
  fetchDefaultPluginTypesAction,
  fetchPluginsAction,
  fetchPluginAction,
  updateBotAction,
  updateBotPackagesAction,
  updatePackageAction,
  addAvailableUpdateForPackageAction,
  updateDescriptorAction,
  fetchBotsUsingPackageAction,
  fetchPackagesUsingPluginAction,
  updateJsonDataAction,
  createNewConfigAction,
  createNewPluginSuccessAction,
  createNewPackageAction,
  createNewPackageFailedAction,
  createNewPackageSuccessAction,
  createNewBotAction,
  createNewBotFailedAction,
  createNewBotSuccessAction,
  updatePackagesAction,
  updatePackagesSuccessAction,
  updatePackagesFailedAction,
  updateBotsAction,
  updateBotsFailedAction,
  updateBotsSuccessAction,
  deployBotAction,
  deployBotSuccessAction,
  deployBotFailedAction,
  undeployBotAction,
  undeployBotSuccessAction,
  undeployBotFailedAction,
  fetchBotDeploymentStatusAction,
  fetchBotDeploymentStatusSuccessAction,
  fetchBotDeploymentStatusFailedAction,
  addNewPackageToBotsAction,
  addNewPackageToBotsSuccessAction,
  addNewPackageToBotsFailedAction,
  fetchJsonSchemaAction,
  fetchBotJsonSchemaSuccessAction,
  fetchPackageJsonSchemaSuccessAction,
  fetchPluginJsonSchemaSuccessAction,
  duplicateAction,
  duplicateSuccessAction,
  duplicateFailedAction,
};

const eddiApiActionDispatchers: IEddiApiActionDispatchers = bindActionCreators<
  IEddiApiActionDispatchers
>(actions, store.dispatch);

export default eddiApiActionDispatchers;
