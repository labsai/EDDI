import { ActionCreatorsMapObject, bindActionCreators } from 'redux';
import { store } from '../store/store';
import {
  addAvailableUpdateForPackageAction,
  addNewPackageToBotsAction,
  addNewPackageToBotsFailedAction,
  addNewPackageToBotsSuccessAction,
  createNewBotAction,
  createNewBotFailedAction,
  createNewBotSuccessAction,
  createNewConfigAction,
  createNewPackageAction,
  createNewPackageFailedAction,
  createNewPackageSuccessAction,
  createNewPluginSuccessAction,
  deployBotAction,
  deployBotFailedAction,
  deployBotSuccessAction,
  deployExampleBotsAction,
  deployExampleBotsFailedAction,
  deployExampleBotsSuccessAction,
  duplicateAction,
  duplicateFailedAction,
  duplicateSuccessAction,
  endConversationAction,
  endConversationFailedAction,
  endConversationSuccessAction,
  fetchBotAction,
  fetchBotDataAction,
  fetchBotDeploymentStatusAction,
  fetchBotDeploymentStatusFailedAction,
  fetchBotDeploymentStatusSuccessAction,
  fetchBotJsonSchemaSuccessAction,
  fetchBotsAction,
  fetchBotsUsingPackageAction,
  fetchConversationAction,
  fetchConversationFailedAction,
  fetchConversationsAction,
  fetchConversationsFailedAction,
  fetchConversationsSuccessAction,
  fetchConversationSuccessAction,
  fetchCurrentBotAction,
  fetchCurrentPackageAction,
  fetchDefaultPluginTypesAction,
  fetchJsonSchemaAction,
  fetchPackageAction,
  fetchPackageDataAction,
  fetchPackageJsonSchemaSuccessAction,
  fetchPackagesAction,
  fetchPackagesUsingPluginAction,
  fetchPluginAction,
  fetchPluginJsonSchemaSuccessAction,
  fetchPluginsAction,
  IAddAvailableUpdateForPackageAction,
  IAddNewPackageToBotsAction,
  IAddNewPackageToBotsFailedAction,
  IAddNewPackageToBotsSuccessAction,
  ICreateNewBotAction,
  ICreateNewConfigAction,
  ICreateNewPackageAction,
  ICreateNewPluginSuccessAction,
  IDeployBotAction,
  IDeployBotFailedAction,
  IDeployBotSuccessAction,
  IDeployExampleBotsAction,
  IDeployExampleBotsFailedAction,
  IDeployExampleBotsSuccessAction,
  IDuplicateAction,
  IDuplicateFailedAction,
  IDuplicateSuccessAction,
  IEndConversationAction,
  IEndConversationFailedAction,
  IEndConversationSuccessAction,
  IFetchBotAction,
  IFetchBotDataAction,
  IFetchBotDeploymentStatusAction,
  IFetchBotDeploymentStatusFailedAction,
  IFetchBotDeploymentStatusSuccessAction,
  IFetchBotsAction,
  IFetchBotsUsingPackageAction,
  IFetchConversationAction,
  IFetchConversationFailedAction,
  IFetchConversationsAction,
  IFetchConversationsFailedAction,
  IFetchConversationsSuccessAction,
  IFetchConversationSuccessAction,
  IFetchCurrentBotAction,
  IFetchCurrentPackageAction,
  IFetchDefaultPluginTypesAction,
  IFetchJsonSchemaAction,
  IFetchJsonSchemaSuccessAction,
  IFetchPackageAction,
  IFetchPackageDataAction,
  IFetchPackagesAction,
  IFetchPackagesUsingPluginAction,
  IFetchPluginAction,
  IFetchPluginsAction,
  IMassUpdateJsonDataAction,
  IUpdateBotAction,
  IUpdateBotPackagesAction,
  IUpdateBotsAction,
  IUpdateBotsFailedAction,
  IUpdateBotsSuccessAction,
  IUpdateDescriptorAction,
  IUpdateJsonDataAction,
  IUpdatePackageAction,
  IUpdatePackagesAction,
  IUpdatePackagesFailedAction,
  IUpdatePackagesSuccessAction,
  massUpdateJsonDataAction,
  undeployBotAction,
  undeployBotFailedAction,
  undeployBotSuccessAction,
  updateBotAction,
  updateBotPackagesAction,
  updateBotsAction,
  updateBotsFailedAction,
  updateBotsSuccessAction,
  updateDescriptorAction,
  updateJsonDataAction,
  updatePackageAction,
  updatePackagesAction,
  updatePackagesFailedAction,
  updatePackagesSuccessAction,
  clearEditedPluginDataAction,
  updateExtensionsOrderAction,
  IUpdateExtensionsOrderAction,
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
  massUpdateJsonDataAction: (
    plugins,
    deploy,
    openedResource?: string,
  ) => IMassUpdateJsonDataAction;
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
  fetchConversationsAction: (
    limit,
    index,
    conversationId,
    botResource,
  ) => IFetchConversationsAction;
  fetchConversationsSuccessAction: (
    limit,
    index,
    conversationId,
    botResource,
    conversations,
  ) => IFetchConversationsSuccessAction;
  fetchConversationsFailedAction: (error) => IFetchConversationsFailedAction;
  fetchConversationAction: (conversationId) => IFetchConversationAction;
  fetchConversationSuccessAction: (
    conversationId,
    conversation,
  ) => IFetchConversationSuccessAction;
  fetchConversationFailedAction: (error) => IFetchConversationFailedAction;
  endConversationAction: (conversationId) => IEndConversationAction;
  endConversationSuccessAction: (
    conversationId,
  ) => IEndConversationSuccessAction;
  endConversationFailedAction: (conversationId) => IEndConversationFailedAction;
  deployExampleBotsAction: () => IDeployExampleBotsAction;
  deployExampleBotsSuccessAction: (bots) => IDeployExampleBotsSuccessAction;
  deployExampleBotsFailedAction: (error) => IDeployExampleBotsFailedAction;
  clearEditedPluginDataAction: () => void;
  updateExtensionsOrderAction: (
    pkg,
    packageExtensions,
  ) => IUpdateExtensionsOrderAction;
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
  fetchConversationsAction,
  fetchConversationsSuccessAction,
  fetchConversationsFailedAction,
  fetchConversationAction,
  fetchConversationSuccessAction,
  fetchConversationFailedAction,
  endConversationAction,
  endConversationSuccessAction,
  endConversationFailedAction,
  deployExampleBotsAction,
  deployExampleBotsFailedAction,
  deployExampleBotsSuccessAction,
  massUpdateJsonDataAction,
  clearEditedPluginDataAction,
  updateExtensionsOrderAction,
};

const eddiApiActionDispatchers: IEddiApiActionDispatchers = bindActionCreators(
  actions,
  store.dispatch,
);

export default eddiApiActionDispatchers;
