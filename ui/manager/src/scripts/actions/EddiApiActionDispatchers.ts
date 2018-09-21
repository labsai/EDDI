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
  IUpdatePluginTypeAction,
  IFetchPluginTypesAction,
  fetchPluginTypesAction,
  updatePluginTypeAction,
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
} from './EddiApiActions';

export interface IEddiApiActionDispatchers extends ActionCreatorsMapObject {
  fetchBotAction: (botId) => IFetchBotAction;
  fetchBotsAction: () => IFetchBotsAction;
  fetchBotDataAction: (botResource) => IFetchBotDataAction;
  fetchPackagesAction: () => IFetchPackagesAction;
  fetchPackageAction: (packageResource) => IFetchPackageAction;
  fetchCurrentPackageAction: (packageId) => IFetchCurrentPackageAction;
  fetchPackageDataAction: (packageResource) => IFetchPackageDataAction;
  fetchDefaultPluginTypesAction: () => IFetchDefaultPluginTypesAction;
  fetchPluginsAction: (pluginType) => IFetchPluginsAction;
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
  fetchPluginTypesAction: (packageResource) => IFetchPluginTypesAction;
  updatePluginTypeAction: (
    packageResource,
    pluginTypes,
  ) => IUpdatePluginTypeAction;
  fetchBotsUsingPackageAction: (
    packageResource,
  ) => IFetchBotsUsingPackageAction;
  fetchPackagesUsingPluginAction: (
    pluginResource,
  ) => IFetchPackagesUsingPluginAction;
  updateJsonDataAction: (resource, data) => IUpdateJsonDataAction;
  createNewConfigAction: (
    eddiType,
    name,
    description,
    data,
  ) => ICreateNewConfigAction;
  updatePackagesAction: (pluginResource, packages) => IUpdatePackagesAction;
  updatePackagesSuccessAction: (packages) => IUpdatePackagesSuccessAction;
  updatePackagesFailedAction: (error) => IUpdatePackagesFailedAction;
  updateBotsAction: (bots) => IUpdateBotsAction;
  updateBotsSuccessAction: (bots) => IUpdateBotsSuccessAction;
  updateBotsFailedAction: (error) => IUpdateBotsFailedAction;
}

const actions: IEddiApiActionDispatchers = {
  fetchBotAction,
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
  fetchPluginTypesAction,
  updatePluginTypeAction,
  fetchBotsUsingPackageAction,
  fetchPackagesUsingPluginAction,
  updateJsonDataAction,
  createNewConfigAction,
  updatePackagesAction,
  updatePackagesSuccessAction,
  updatePackagesFailedAction,
  updateBotsAction,
  updateBotsFailedAction,
  updateBotsSuccessAction,
};

const eddiApiActionDispatchers: IEddiApiActionDispatchers = bindActionCreators<
  IEddiApiActionDispatchers
>(actions, store.dispatch);

export default eddiApiActionDispatchers;
