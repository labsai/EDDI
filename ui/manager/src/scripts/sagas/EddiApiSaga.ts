import * as _ from 'lodash';
import { call, put, takeEvery } from 'redux-saga/effects';
import { openChatAction } from '../actions/ChatActions';
import {
  addNewPackageToBotsFailedAction,
  addNewPackageToBotsSuccessAction,
  clearEditedPluginDataAction,
  createNewBotFailedAction,
  createNewBotSuccessAction,
  createNewConfigFailedAction,
  createNewPackageAction,
  createNewPackageSuccessAction,
  createNewPluginSuccessAction,
  deployBotFailedAction,
  deployBotSuccessAction,
  deployExampleBotsFailedAction,
  deployExampleBotsSuccessAction,
  duplicateFailedAction,
  duplicateSuccessAction,
  endConversationFailedAction,
  endConversationSuccessAction,
  fetchBotDataFailedAction,
  fetchBotDataSuccessAction,
  fetchBotDeploymentStatusFailedAction,
  fetchBotDeploymentStatusSuccessAction,
  fetchBotFailedAction,
  fetchBotJsonSchemaSuccessAction,
  fetchBotsFailedAction,
  fetchBotsSuccessAction,
  fetchBotSuccessAction,
  fetchBotsUsingPackageFailedAction,
  fetchBotsUsingPackageSuccessAction,
  fetchConversationFailedAction,
  fetchConversationsFailedAction,
  fetchConversationsSuccessAction,
  fetchConversationSuccessAction,
  fetchDefaultPluginTypesFailedAction,
  fetchDefaultPluginTypesSuccessAction,
  fetchJsonSchemaFailedAction,
  fetchPackageDataFailedAction,
  fetchPackageDataSuccessAction,
  fetchPackageFailedAction,
  fetchPackageJsonSchemaSuccessAction,
  fetchPackagesFailedAction,
  fetchPackagesSuccessAction,
  fetchPackageSuccessAction,
  fetchPackagesUsingPluginFailedAction,
  fetchPackagesUsingPluginSuccessAction,
  fetchPluginFailedAction,
  fetchPluginJsonSchemaSuccessAction,
  fetchPluginsFailedAction,
  fetchPluginsSuccessAction,
  fetchPluginSuccessAction,
  IAddNewPackageToBotsAction,
  ICreateNewBotAction,
  ICreateNewConfigAction,
  ICreateNewPackageAction,
  IDeployBotAction,
  IDeployExampleBotsAction,
  IDuplicateAction,
  IEndConversationAction,
  IFetchBotAction,
  IFetchBotDataAction,
  IFetchBotDeploymentStatusAction,
  IFetchBotsAction,
  IFetchBotsUsingPackageAction,
  IFetchConversationAction,
  IFetchConversationsAction,
  IFetchCurrentBotAction,
  IFetchCurrentPackageAction,
  IFetchJsonSchemaAction,
  IFetchPackageAction,
  IFetchPackageDataAction,
  IFetchPackagesAction,
  IFetchPackagesUsingPluginAction,
  IFetchPluginAction,
  IFetchPluginsAction,
  IMassUpdateJsonDataAction,
  IUndeployBotAction,
  IUpdateBotAction,
  IUpdateBotPackagesAction,
  IUpdateBotsAction,
  IUpdateDescriptorAction,
  IUpdateJsonDataAction,
  IUpdatePackageAction,
  IUpdatePackagesAction,
  undeployBotFailedAction,
  undeployBotSuccessAction,
  updateBotFailedAction,
  updateBotsFailedAction,
  updateBotsSuccessAction,
  updateBotSuccessAction,
  updateDescriptorFailedAction,
  updateDescriptorSuccessAction,
  updateJsonDataFailedAction,
  updatePackageFailedAction,
  updatePackagesFailedAction,
  updatePackagesSuccessAction,
  updatePackageSuccessAction,
  updatePluginSuccessAction,
} from '../actions/EddiApiActions';
import {
  ADD_NEW_PACKAGE_TO_BOTS,
  CREATE_NEW_BOT,
  CREATE_NEW_CONFIG,
  CREATE_NEW_PACKAGE,
  DEPLOY_BOT,
  DEPLOY_EXAMPLE_BOTS,
  DUPLICATE,
  END_CONVERSATION,
  FETCH_BOT,
  FETCH_BOTDATA,
  FETCH_BOTS,
  FETCH_BOTS_USING_PACKAGE,
  FETCH_BOT_DEPLOYMENT_STATUS,
  FETCH_CONVERSATION,
  FETCH_CONVERSATIONS,
  FETCH_CURRENT_BOT,
  FETCH_CURRENT_PACKAGE,
  FETCH_DEFAULT_PLUGIN_TYPES,
  FETCH_JSON_SCHEMA,
  FETCH_PACKAGE,
  FETCH_PACKAGEDATA,
  FETCH_PACKAGES,
  FETCH_PACKAGES_USING_PLUGIN,
  FETCH_PLUGIN,
  FETCH_PLUGINS,
  MASS_UPDATE_JSON_DATA,
  UNDEPLOY_BOT,
  UPDATE_BOT,
  UPDATE_BOTS,
  UPDATE_BOT_PACKAGES,
  UPDATE_DESCRIPTOR,
  UPDATE_JSON_DATA,
  UPDATE_PACKAGE,
  UPDATE_PACKAGES,
} from '../actions/EddiApiActionTypes';
import { closeModal, showParallelConfigModal } from '../actions/ModalActions';
import { hideLoader, showLoader } from '../actions/SystemActions';
import {
  getAPIUrl,
  getTypeFromResource,
} from '../components/utils/ApiFunctions';
import {
  addPackageToBot,
  deployBot as axiosDeployBot,
  deployExampleBots as axiosDeployExampleBots,
  duplicate,
  endConversation as axiosEndConversation,
  getAllDefaultPluginTypes,
  getBot,
  getBotData,
  getBotDescriptors,
  getBotsUsingPackage,
  getConversation,
  getConversations,
  getCurrentBot,
  getCurrentPackage,
  getCurrentPlugin,
  getDeploymentStatus,
  getPackage,
  getPackageData,
  getPackageDescriptors,
  getPackagesUsingPlugin,
  getPlugin,
  getPluginDescriptors,
  getSchema,
  IBot,
  IConversation,
  IConversationData,
  IDefaultPluginTypes,
  IEddiSchema,
  IPackage,
  IPlugin,
  IPlugins,
  patchDescriptor,
  postNewConfig,
  undeployBot as axiosUndeployBot,
  updateBot as axiosUpdateBot,
  updateBotPackages as axiosUpdateBotPackages,
  updateBots as axiosUpdateBots,
  updateJsonData as axiosUpdateJsonData,
  updatePackage as axiosUpdatePackage,
  updatePackages as axiosUpdatePackages,
} from '../components/utils/AxiosFunctions';
import * as Edditypes from '../components/utils/EddiTypes';
import { BOT, PACKAGE } from '../components/utils/EddiTypes';
import getIdsFromPath, {
  isPackagePage,
} from '../components/utils/helpers/getIdsFromPath';
import { parsePlugins } from '../components/utils/helpers/PluginParser';
import Parser from '../components/utils/Parser';
import { historyPush } from '../history';

export function* FetchBots(action: IFetchBotsAction) {
  try {
    const bots: IBot[] = yield call(
      getBotDescriptors,
      action.limit,
      action.index,
    );
    yield put(fetchBotsSuccessAction(bots, action.limit, action.index));
  } catch (err) {
    yield put(fetchBotsFailedAction(err));
  }
}

export function* watchFetchBots(): Iterator<{}> {
  yield takeEvery(FETCH_BOTS, FetchBots);
}

export function* FetchPackages(action: IFetchPackagesAction) {
  try {
    const packages: IPackage[] = yield call(
      getPackageDescriptors,
      action.limit,
      action.index,
    );
    yield put(fetchPackagesSuccessAction(packages, action.limit, action.index));
  } catch (err) {
    yield put(fetchPackagesFailedAction(err));
  }
}

export function* watchFetchPackages(): Iterator<{}> {
  yield takeEvery(FETCH_PACKAGES, FetchPackages);
}

export function* FetchBot(action: IFetchBotAction) {
  try {
    const bot: IBot = yield call(getBot, action.botId);
    yield put(fetchBotSuccessAction(bot));
  } catch (err) {
    yield put(fetchBotFailedAction(err));
  }
}

export function* watchFetchBot(): Iterator<{}> {
  yield takeEvery(FETCH_BOT, FetchBot);
}

export function* FetchCurrentBot(action: IFetchCurrentBotAction) {
  try {
    const bot: IBot = yield call(getCurrentBot, action.botId);
    yield put(fetchBotSuccessAction(bot));
  } catch (err) {
    yield put(fetchBotFailedAction(err));
  }
}

export function* watchFetchCurrentBot(): Iterator<{}> {
  yield takeEvery(FETCH_CURRENT_BOT, FetchCurrentBot);
}

export function* FetchBotData(action: IFetchBotDataAction) {
  try {
    const botData = yield call(getBotData, action.botResource);
    yield put(fetchBotDataSuccessAction(botData, action.botResource));
  } catch (err) {
    yield put(fetchBotDataFailedAction(err));
  }
}

export function* watchFetchBotData(): Iterator<{}> {
  yield takeEvery(FETCH_BOTDATA, FetchBotData);
}

export function* FetchPackage(action: IFetchPackageAction) {
  try {
    const pack: IPackage = yield call(getPackage, action.packageResource);
    yield put(fetchPackageSuccessAction(pack));
  } catch (err) {
    yield put(fetchPackageFailedAction(err));
  }
}

export function* watchFetchPackage(): Iterator<{}> {
  yield takeEvery(FETCH_PACKAGE, FetchPackage);
}

export function* FetchCurrentPackage(action: IFetchCurrentPackageAction) {
  try {
    const pkg: IPackage = yield call(getCurrentPackage, action.packageId);
    yield put(fetchPackageSuccessAction(pkg));
  } catch (err) {
    yield put(fetchPackageFailedAction(err));
  }
}

export function* watchFetchCurrentPackage(): Iterator<{}> {
  yield takeEvery(FETCH_CURRENT_PACKAGE, FetchCurrentPackage);
}

export function* FetchDefaultPluginTypes() {
  try {
    const defaultPluginTypes: IDefaultPluginTypes[] = yield call(
      getAllDefaultPluginTypes,
    );
    yield put(fetchDefaultPluginTypesSuccessAction(defaultPluginTypes));
  } catch (err) {
    yield put(fetchDefaultPluginTypesFailedAction(err));
  }
}

export function* watchFetchDefaultPluginTypes(): Iterator<{}> {
  yield takeEvery(FETCH_DEFAULT_PLUGIN_TYPES, FetchDefaultPluginTypes);
}

export function* FetchPlugins(action: IFetchPluginsAction) {
  try {
    const plugins: IPlugin[] = yield call(
      getPluginDescriptors,
      action.pluginType,
      action.limit,
      action.index,
    );
    yield put(
      fetchPluginsSuccessAction(
        plugins,
        action.pluginType,
        action.limit,
        action.index,
      ),
    );
  } catch (err) {
    yield put(fetchPluginsFailedAction(err));
  }
}

export function* watchFetchPlugins(): Iterator<{}> {
  yield takeEvery(FETCH_PLUGINS, FetchPlugins);
}

export function* FetchPlugin(action: IFetchPluginAction) {
  try {
    const plugin: IPlugin = yield call(getPlugin, action.pluginResource);
    yield put(fetchPluginSuccessAction(plugin));
  } catch (err) {
    yield put(fetchPluginFailedAction(err));
  }
}

export function* watchFetchPlugin(): Iterator<{}> {
  yield takeEvery(FETCH_PLUGIN, FetchPlugin);
}

export function* updateDescriptor(action: IUpdateDescriptorAction) {
  try {
    yield call(
      patchDescriptor,
      action.resource,
      action.name,
      action.description,
    );
    yield put(
      updateDescriptorSuccessAction(
        action.resource,
        action.name,
        action.description,
      ),
    );
  } catch (err) {
    yield put(updateDescriptorFailedAction(err));
  }
}

export function* watchUpdateDescriptor(): Iterator<{}> {
  yield takeEvery(UPDATE_DESCRIPTOR, updateDescriptor);
}

export function* updateBot(action: IUpdateBotAction) {
  try {
    const bot: IBot = yield call(
      axiosUpdateBot,
      action.bot,
      action.package.resource,
    );
    yield put(updateBotSuccessAction(bot));
  } catch (err) {
    yield put(updateBotFailedAction(err));
  }
}

export function* watchUpdateBot(): Iterator<{}> {
  yield takeEvery(UPDATE_BOT, updateBot);
}

export function* updateBotPackages(action: IUpdateBotPackagesAction) {
  try {
    const bot: IBot = yield call(
      axiosUpdateBotPackages,
      action.bot,
      action.packages,
    );
    yield put(updateBotSuccessAction(bot));
  } catch (err) {
    yield put(updateBotFailedAction(err));
  }
}

export function* watchUpdateBotPackages(): Iterator<{}> {
  yield takeEvery(UPDATE_BOT_PACKAGES, updateBotPackages);
}

export function* updatePackage(action: IUpdatePackageAction) {
  try {
    const pack: IPackage = yield call(
      axiosUpdatePackage,
      action.package,
      action.plugin.resource,
    );
    yield put(updatePackageSuccessAction(pack));
  } catch (err) {
    yield put(updatePackageFailedAction(err));
  }
}

export function* watchUpdatePackage(): Iterator<{}> {
  yield takeEvery(UPDATE_PACKAGE, updatePackage);
}

export function* FetchPackageData(action: IFetchPackageDataAction) {
  try {
    const packageData: IPlugins = yield call(
      getPackageData,
      action.packageResource,
    );
    yield put(
      fetchPackageDataSuccessAction(packageData, action.packageResource),
    );
  } catch (err) {
    yield put(fetchPackageDataFailedAction(err));
  }
}

export function* watchFetchPackageData(): Iterator<{}> {
  yield takeEvery(FETCH_PACKAGEDATA, FetchPackageData);
}

export function* fetchBotsUsingPackage(
  action: IFetchBotsUsingPackageAction,
): Iterator<{}> {
  try {
    const botsUsingPackage: IBot[] = yield call(
      getBotsUsingPackage,
      action.packageResource,
      action.anyVersion,
    );
    yield put(
      fetchBotsUsingPackageSuccessAction(
        action.packageResource,
        action.anyVersion,
        botsUsingPackage,
      ),
    );
  } catch (err) {
    yield put(fetchBotsUsingPackageFailedAction(err));
  }
}

export function* watchFetchBotsUsingPackage(): Iterator<{}> {
  yield takeEvery(FETCH_BOTS_USING_PACKAGE, fetchBotsUsingPackage);
}

export function* fetchPackagesUsingPlugin(
  action: IFetchPackagesUsingPluginAction,
): Iterator<{}> {
  try {
    const packagesUsingPlugin: IPackage[] = yield call(
      getPackagesUsingPlugin,
      action.pluginResource,
      action.anyVersion,
    );
    yield put(
      fetchPackagesUsingPluginSuccessAction(
        action.pluginResource,
        action.anyVersion,
        packagesUsingPlugin,
      ),
    );
  } catch (err) {
    yield put(fetchPackagesUsingPluginFailedAction(err));
  }
}

export function* watchFetchPackagesUsingPlugin(): Iterator<{}> {
  yield takeEvery(FETCH_PACKAGES_USING_PLUGIN, fetchPackagesUsingPlugin);
}

export function* watchCreateNewBot(): Iterator<{}> {
  yield takeEvery(CREATE_NEW_BOT, createNewBot);
}

export function* createNewBot(action: ICreateNewBotAction): Iterator<{}> {
  try {
    const bot: IBot = yield call(getCurrentBot, action.botId);
    yield put(createNewBotSuccessAction(bot));
  } catch (err) {
    yield put(createNewBotFailedAction(err));
  }
}

export function* watchCreateNewPackage(): Iterator<{}> {
  yield takeEvery(CREATE_NEW_PACKAGE, createNewPackage);
}

export function* createNewPackage(
  action: ICreateNewPackageAction,
): Iterator<{}> {
  try {
    const pkg: IPackage = yield call(getCurrentPackage, action.packageId);
    yield put(createNewPackageSuccessAction(pkg));
  } catch (err) {
    yield put(createNewPackageAction(err));
  }
}

export function* watchCreateNewConfig(): Iterator<{}> {
  yield takeEvery(CREATE_NEW_CONFIG, createNewConfig);
}

export function* createNewConfig(action: ICreateNewConfigAction): Iterator<{}> {
  try {
    const newResource = yield call(
      postNewConfig,
      action.eddiType,
      action.name,
      action.description,
      action.data,
    );
    switch (action.eddiType) {
      case BOT:
        const bot: IBot = yield call(getCurrentBot, Parser.getId(newResource));
        yield put(createNewBotSuccessAction(bot));
        break;
      case PACKAGE:
        const pkg: IPackage = yield call(
          getCurrentPackage,
          Parser.getId(newResource),
        );
        yield put(createNewPackageSuccessAction(pkg));
        break;
      default:
        const plugin = yield call(getCurrentPlugin, newResource);
        yield put(createNewPluginSuccessAction(plugin));
    }
  } catch (err) {
    yield put(createNewConfigFailedAction(err));
  }
}
export function* updateJsonData(action: IUpdateJsonDataAction): Iterator<{}> {
  try {
    yield put(showLoader());
    yield call(axiosUpdateJsonData, action.resource, action.data);
    if (action.resource.includes(Edditypes.BOT)) {
      const updatedBot: IBot = yield call(
        getCurrentBot,
        Parser.getId(action.resource),
      );
      yield put(updateBotSuccessAction(updatedBot));
    } else if (action.resource.includes(Edditypes.PACKAGE)) {
      const updatedPackage: IPackage = yield call(
        getCurrentPackage,
        Parser.getId(action.resource),
      );
      // auto update package
      if (action.data.botId) {
        const currentBot: IBot = yield call(getCurrentBot, action.data.botId);
        if (currentBot && updatedPackage) {
          const botToUpdate = {
            botResource: currentBot?.resource as string,
            packageResources: [updatedPackage.resource] as string[],
          };
          yield put(updatePackageSuccessAction(updatedPackage, true));
          const updatedBots: IBot[] = yield call(axiosUpdateBots, [
            botToUpdate,
          ]);
          yield put(updateBotsSuccessAction(updatedBots));
          if (action.data.deploy && !_.isEmpty(updatedBots)) {
            yield call(deployBot, {
              botResource: updatedBots[0].resource,
            } as IDeployBotAction);
            yield put(openChatAction());
          }
        }
      } else {
        yield put(updatePackageSuccessAction(updatedPackage));
      }
    } else {
      const updatedPlugin: IPlugin = yield call(
        getCurrentPlugin,
        action.resource,
      );

      // auto update package and bot
      if (action.data.botId && action.data.packageId) {
        yield put(updatePluginSuccessAction(updatedPlugin, true));
        const currentPackage: IPackage = yield call(
          getCurrentPackage,
          action.data.packageId,
        );

        const updatedPackage: IPackage = yield call(
          axiosUpdatePackage,
          currentPackage,
          updatedPlugin.resource,
        );

        yield put(updatePackageSuccessAction(updatedPackage, true));

        const currentBot: IBot = yield call(getCurrentBot, action.data.botId);
        const botToUpdate = {
          botResource: currentBot?.resource as string,
          packageResources: [updatedPackage.resource] as string[],
        };

        const updatedBots: IBot[] = yield call(axiosUpdateBots, [botToUpdate]);
        yield put(updateBotsSuccessAction(updatedBots));
        if (action.data.deploy && !_.isEmpty(updatedBots)) {
          yield call(deployBot, {
            botResource: updatedBots[0].resource,
          } as IDeployBotAction);
          yield put(openChatAction());
        }
      } else {
        yield put(updatePluginSuccessAction(updatedPlugin));
      }
    }
    yield put(hideLoader());
  } catch (err) {
    yield put(updateJsonDataFailedAction(err));
    yield put(hideLoader());
  }
}

export function* watchUpdateJsonData(): Iterator<{}> {
  yield takeEvery(UPDATE_JSON_DATA, updateJsonData);
}

function* iterateResources(resource: string, data: any, last: boolean) {
  const { packageId, botId } = getIdsFromPath();
  yield call(axiosUpdateJsonData, resource, JSON.parse(data));
  const updatedPlugin: IPlugin = yield call(getCurrentPlugin, resource);
  yield put(updatePluginSuccessAction(updatedPlugin, true));
  const currentPackage: IPackage = yield call(getCurrentPackage, packageId);
  const updatedPackage: IPackage = yield call(
    axiosUpdatePackage,
    currentPackage,
    updatedPlugin.resource,
  );
  yield put(
    updatePackageSuccessAction(updatedPackage, last && !botId ? false : true),
  );
  return updatedPackage.resource;
}

export function* massUpdateJsonData(
  action: IMassUpdateJsonDataAction,
): Iterator<{}> {
  try {
    const { packageId, botId } = getIdsFromPath();
    yield put(showLoader());
    yield put(closeModal());

    let updatedPackage: string;

    for (let [i, p] of action.plugins.entries()) {
      const last = action.plugins.length - 1 === i;
      const newPackage = yield call(iterateResources, p.resource, p.data, last);
      updatedPackage = newPackage;
    }

    if (botId) {
      const currentBot: IBot = yield call(getCurrentBot, botId);
      const botToUpdate = {
        botResource: currentBot?.resource as string,
        packageResources: [updatedPackage],
      };
      const updatedBots: IBot[] = yield call(axiosUpdateBots, [botToUpdate]);
      yield put(updateBotsSuccessAction(updatedBots));

      if (action.deploy && !_.isEmpty(updatedBots)) {
        yield call(deployBot, {
          botResource: updatedBots[0].resource,
        } as IDeployBotAction);
        yield put(openChatAction());
      } else {
        const currentPackage: IPackage = yield call(
          getCurrentPackage,
          packageId,
        );
        yield put(
          showParallelConfigModal(currentPackage, currentPackage.resource),
        );
        yield call(historyPush, `${location.pathname}`, [
          !isPackagePage() ? `packageId=${packageId}` : `botId=${botId}`,
        ]);
      }
    } else {
      const currentPackage: IPackage = yield call(getCurrentPackage, packageId);
      yield put(
        showParallelConfigModal(currentPackage, currentPackage.resource),
      );
      yield call(historyPush, `${location.pathname}`, [
        !isPackagePage ? `packageId=${packageId}` : `botId=${botId}`,
      ]);
    }
    yield put(clearEditedPluginDataAction());
    yield put(hideLoader());
  } catch (err) {
    yield put(updateJsonDataFailedAction(err));
    yield put(hideLoader());
  }
}

export function* watchMassUpdateJsonData(): Iterator<{}> {
  yield takeEvery(MASS_UPDATE_JSON_DATA, massUpdateJsonData);
}

export function* updatePackages(action: IUpdatePackagesAction): Iterator<{}> {
  try {
    const updatedPackages: IPackage[] = yield call(
      axiosUpdatePackages,
      action.pluginResource,
      action.packages,
    );
    yield put(updatePackagesSuccessAction(updatedPackages));
  } catch (err) {
    yield put(updatePackagesFailedAction(err));
  }
}

export function* watchUpdatePackages(): Iterator<{}> {
  yield takeEvery(UPDATE_PACKAGES, updatePackages);
}

export function* updateBots(action: IUpdateBotsAction): Iterator<{}> {
  try {
    const updatedBots: IBot[] = yield call(axiosUpdateBots, action.bots);
    yield put(updateBotsSuccessAction(updatedBots));
  } catch (err) {
    yield put(updateBotsFailedAction(err));
  }
}

export function* watchUpdateBots(): Iterator<{}> {
  yield takeEvery(UPDATE_BOTS, updateBots);
}

export function* deployBot(action: IDeployBotAction): Iterator<{}> {
  try {
    yield call(axiosDeployBot, action.botResource);
    const conversationUrl = `${yield call(
      getAPIUrl,
    )}/chat/unrestricted/${Parser.getId(action.botResource)}`;
    yield put(deployBotSuccessAction(action.botResource, conversationUrl));
  } catch (err) {
    yield put(deployBotFailedAction(err));
  }
}

export function* watchDeployBot(): Iterator<{}> {
  yield takeEvery(DEPLOY_BOT, deployBot);
}

export function* undeployBot(action: IUndeployBotAction): Iterator<{}> {
  try {
    yield call(axiosUndeployBot, action.botResource);
    yield put(undeployBotSuccessAction(action.botResource));
  } catch (err) {
    yield put(undeployBotFailedAction(err, err.response.data));
  }
}

export function* watchUndeployBot(): Iterator<{}> {
  yield takeEvery(UNDEPLOY_BOT, undeployBot);
}

export function* fetchBotDeploymentStatus(
  action: IFetchBotDeploymentStatusAction,
): Iterator<{}> {
  try {
    const status = yield call(getDeploymentStatus, action.botResource);
    yield put(
      fetchBotDeploymentStatusSuccessAction(action.botResource, status),
    );
  } catch (err) {
    yield put(fetchBotDeploymentStatusFailedAction(err));
  }
}

export function* watchFetchBotDeploymentStatus(): Iterator<{}> {
  yield takeEvery(FETCH_BOT_DEPLOYMENT_STATUS, fetchBotDeploymentStatus);
}

export function* addNewPackageToBots(
  action: IAddNewPackageToBotsAction,
): Iterator<{}> {
  try {
    const newBots: IBot[] = [];
    for (let i = 0; i < action.bots.length; i++) {
      const newBot = yield call(
        addPackageToBot,
        action.bots[i],
        action.packageResource,
      );
      newBots.push(newBot);
    }
    yield put(
      addNewPackageToBotsSuccessAction(newBots, action.packageResource),
    );
  } catch (err) {
    yield put(addNewPackageToBotsFailedAction(err));
  }
}

export function* watchAddNewPackageToBots(): Iterator<{}> {
  yield takeEvery(ADD_NEW_PACKAGE_TO_BOTS, addNewPackageToBots);
}

export function* fetchJsonSchema(action: IFetchJsonSchemaAction): Iterator<{}> {
  try {
    const schema: IEddiSchema = {
      name: action.eddiType,
      value: yield call(getSchema, action.eddiType),
    };
    switch (action.eddiType) {
      case BOT:
        yield put(fetchBotJsonSchemaSuccessAction(action.eddiType, schema));
      case PACKAGE:
        yield put(fetchPackageJsonSchemaSuccessAction(action.eddiType, schema));
      default:
        yield put(fetchPluginJsonSchemaSuccessAction(action.eddiType, schema));
    }
  } catch (err) {
    yield put(fetchJsonSchemaFailedAction(err));
  }
}

export function* watchFetchJsonSchema(): Iterator<{}> {
  yield takeEvery(FETCH_JSON_SCHEMA, fetchJsonSchema);
}

export function* duplicateResource(action: IDuplicateAction): Iterator<{}> {
  try {
    const newResource = yield call(duplicate, action.resource, action.deepCopy);
    const type = getTypeFromResource(newResource);
    let bot: IBot;
    let packages: IPackage[] = [];
    let plugins: IPlugin[] = [];
    if (type === BOT) {
      bot = yield call(getBot, newResource);
    } else if (type === PACKAGE) {
      packages = [yield call(getPackage, newResource)];
    } else {
      plugins = [yield call(getPlugin, newResource)];
    }

    if (action.deepCopy === true) {
      if (!!bot) {
        for (let packageResource of bot.packages) {
          packages.push(yield call(getPackage, packageResource));
        }
      }
      if (!_.isEmpty(packages)) {
        const pluginResources: string[] = [];
        for (let pkg of packages) {
          pluginResources.concat(
            parsePlugins(pkg.packageData.packageExtensions),
          );
        }
        for (let pluginResource of _.uniq(pluginResources)) {
          plugins.push(yield call(getPlugin, pluginResource));
        }
      }
    }

    yield put(duplicateSuccessAction(bot, packages, plugins));
  } catch (err) {
    yield put(duplicateFailedAction(err));
  }
}

export function* watchDuplicateResource(): Iterator<{}> {
  yield takeEvery(DUPLICATE, duplicateResource);
}

export function* fetchConversations(
  action: IFetchConversationsAction,
): Iterator<{}> {
  try {
    const conversations: IConversation[] = yield call(
      getConversations,
      action.limit,
      action.index,
      action.conversationId,
      action.botResource,
    );
    yield put(
      fetchConversationsSuccessAction(
        action.limit,
        action.index,
        action.conversationId,
        action.botResource,
        conversations,
      ),
    );
  } catch (err) {
    yield put(fetchConversationsFailedAction(err));
  }
}

export function* watchFetchConversations(): Iterator<{}> {
  yield takeEvery(FETCH_CONVERSATIONS, fetchConversations);
}

export function* fetchConversation(
  action: IFetchConversationAction,
): Iterator<{}> {
  try {
    const conversation: IConversationData = yield call(
      getConversation,
      action.conversationId,
    );
    yield put(
      fetchConversationSuccessAction(action.conversationId, conversation),
    );
  } catch (err) {
    yield put(fetchConversationFailedAction(err));
  }
}

export function* watchFetchConversation(): Iterator<{}> {
  yield takeEvery(FETCH_CONVERSATION, fetchConversation);
}

export function* endConversation(action: IEndConversationAction): Iterator<{}> {
  try {
    yield call(axiosEndConversation, action.conversationId);
    yield put(endConversationSuccessAction(action.conversationId));
  } catch (err) {
    yield put(endConversationFailedAction(err));
  }
}

export function* watchEndConversation(): Iterator<{}> {
  yield takeEvery(END_CONVERSATION, endConversation);
}

export function* deployExampleBots(
  action: IDeployExampleBotsAction,
): Iterator<{}> {
  try {
    const bots: IBot[] = yield call(axiosDeployExampleBots);
    yield put(deployExampleBotsSuccessAction(bots));
  } catch (err) {
    yield put(deployExampleBotsFailedAction(err));
  }
}

export function* watchDeployExampleBots(): Iterator<{}> {
  yield takeEvery(DEPLOY_EXAMPLE_BOTS, deployExampleBots);
}
