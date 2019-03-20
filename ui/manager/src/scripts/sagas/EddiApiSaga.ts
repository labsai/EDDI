import { call, put, takeEvery } from 'redux-saga/effects';
import {
  FETCH_BOT,
  FETCH_BOTS,
  FETCH_PACKAGEDATA,
  FETCH_BOTDATA,
  FETCH_PACKAGE,
  FETCH_PLUGIN,
  FETCH_DEFAULT_PLUGIN_TYPES,
  UPDATE_BOT,
  UPDATE_PACKAGE,
  UPDATE_DESCRIPTOR,
  FETCH_CURRENT_PACKAGE,
  UPDATE_BOT_PACKAGES,
  FETCH_PACKAGES,
  FETCH_PLUGINS,
  FETCH_BOTS_USING_PACKAGE,
  FETCH_PACKAGES_USING_PLUGIN,
  UPDATE_JSON_DATA,
  CREATE_NEW_CONFIG,
  UPDATE_PACKAGES,
  UPDATE_BOTS,
  DEPLOY_BOT,
  UNDEPLOY_BOT,
  FETCH_BOT_DEPLOYMENT_STATUS,
  FETCH_CURRENT_BOT,
  CREATE_NEW_BOT,
  CREATE_NEW_PACKAGE,
  ADD_NEW_PACKAGE_TO_BOTS,
} from '../actions/EddiApiActionTypes';
import {
  getPackage,
  getPlugin,
  updateBot as axiosUpdateBot,
  updateBotPackages as axiosUpdateBotPackages,
  updatePackage as axiosUpdatePackage,
  updateJsonData as axiosUpdateJsonData,
  IBot,
  IPackage,
  IPlugin,
  getPackageData,
  IPluginsResponse,
  getBotData,
  patchDescriptor,
  getCurrentPackage,
  addPluginType,
  getPackageDescriptors,
  getBot,
  getAllDefaultPluginTypes,
  IDefaultPluginTypes,
  getPluginDescriptors,
  getBotsUsingPackage,
  getPackagesUsingPlugin,
  getCurrentBot,
  getCurrentPlugin,
  postNewConfig,
  updatePackages as axiosUpdatePackages,
  updateBots as axiosUpdateBots,
  deployBot as axiosDeployBot,
  undeployBot as axiosUndeployBot,
  getDeploymentStatus,
  getBotDescriptors,
  addPackageToBot,
} from '../components/utils/AxiosFunctions';
import {
  fetchBotFailedAction,
  fetchBotsFailedAction,
  fetchBotsSuccessAction,
  fetchBotSuccessAction,
  fetchBotDataFailedAction,
  fetchBotDataSuccessAction,
  fetchPackageFailedAction,
  fetchPackageSuccessAction,
  fetchPluginFailedAction,
  fetchPluginSuccessAction,
  fetchDefaultPluginTypesFailedAction,
  fetchDefaultPluginTypesSuccessAction,
  fetchPackageDataFailedAction,
  fetchPackageDataSuccessAction,
  updateBotFailedAction,
  updateBotSuccessAction,
  IFetchBotAction,
  IFetchBotDataAction,
  IFetchPackageAction,
  IFetchPluginAction,
  IUpdateBotAction,
  IUpdatePackageAction,
  IFetchPackageDataAction,
  updatePackageSuccessAction,
  updatePackageFailedAction,
  IUpdateDescriptorAction,
  updateDescriptorSuccessAction,
  updateDescriptorFailedAction,
  IFetchCurrentPackageAction,
  IUpdateBotPackagesAction,
  fetchPackagesSuccessAction,
  fetchPackagesFailedAction,
  IFetchPluginsAction,
  fetchPluginsSuccessAction,
  fetchPluginsFailedAction,
  IFetchBotsUsingPackageAction,
  fetchBotsUsingPackageFailedAction,
  fetchBotsUsingPackageSuccessAction,
  IFetchPackagesUsingPluginAction,
  fetchPackagesUsingPluginSuccessAction,
  fetchPackagesUsingPluginFailedAction,
  IUpdateJsonDataAction,
  updateJsonDataFailedAction,
  updatePluginSuccessAction,
  ICreateNewConfigAction,
  createNewConfigFailedAction,
  IUpdatePackagesAction,
  updatePackagesFailedAction,
  updatePackagesSuccessAction,
  IUpdateBotsAction,
  updateBotsFailedAction,
  updateBotsSuccessAction,
  IDeployBotAction,
  deployBotSuccessAction,
  deployBotFailedAction,
  IUndeployBotAction,
  undeployBotSuccessAction,
  undeployBotFailedAction,
  IFetchBotDeploymentStatusAction,
  fetchBotDeploymentStatusSuccessAction,
  fetchBotDeploymentStatusFailedAction,
  createNewBotSuccessAction,
  createNewPackageSuccessAction,
  createNewPluginSuccessAction,
  IFetchCurrentBotAction,
  IFetchBotsAction,
  IFetchPackagesAction,
  ICreateNewBotAction,
  createNewBotFailedAction,
  ICreateNewPackageAction,
  createNewPackageAction,
  IAddNewPackageToBotsAction,
  addNewPackageToBotsFailedAction,
  addNewPackageToBotsSuccessAction,
} from '../actions/EddiApiActions';
import * as Edditypes from '../components/utils/EddiTypes';
import Parser from '../components/utils/Parser';
import { PACKAGE_PATH } from '../components/utils/EddiTypes';
import { BEHAVIOR_PATH } from '../components/utils/EddiTypes';
import { OUTPUT } from '../components/utils/EddiTypes';
import { BOT } from '../components/utils/EddiTypes';
import { BEHAVIOR } from '../components/utils/EddiTypes';
import { OUTPUT_PATH } from '../components/utils/EddiTypes';
import { REGULAR_DICTIONARY_PATH } from '../components/utils/EddiTypes';
import { REGULAR_DICTIONARY } from '../components/utils/EddiTypes';
import { BOT_PATH } from '../components/utils/EddiTypes';
import { PACKAGE } from '../components/utils/EddiTypes';
import { getAPIUrl } from '../components/utils/ApiFunctions';

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
    const packageData: IPluginsResponse[] = yield call(
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
      yield put(updatePackageSuccessAction(updatedPackage));
    } else {
      const updatedPlugin: IPlugin = yield call(
        getCurrentPlugin,
        action.resource,
      );
      yield put(updatePluginSuccessAction(updatedPlugin));
    }
  } catch (err) {
    yield put(updateJsonDataFailedAction(err));
  }
}

export function* watchUpdateJsonData(): Iterator<{}> {
  yield takeEvery(UPDATE_JSON_DATA, updateJsonData);
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
