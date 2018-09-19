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
  UPDATE_PLUGIN_TYPE_IN_PACKAGE,
  FETCH_PLUGIN_TYPES_IN_PACKAGE,
  UPDATE_BOT_PACKAGES,
  FETCH_PACKAGES,
  FETCH_PLUGINS,
  FETCH_BOTS_USING_PACKAGE,
  FETCH_PACKAGES_USING_PLUGIN,
  UPDATE_JSON_DATA,
  CREATE_NEW_CONFIG,
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
  getAllBots,
  getPackageData,
  IPluginsResponse,
  getBotData,
  patchDescriptor,
  getCurrentPackage,
  addPluginType,
  getPluginTypes,
  getPackageDescriptors,
  getBot,
  IPluginTypes,
  getAllDefaultPluginTypes,
  IDefaultPluginTypes,
  getPluginDescriptors,
  getBotsUsingPackage,
  getPackagesUsingPlugin,
  getCurrentBot,
  getCurrentPlugin,
  postNewConfig,
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
  updatePluginTypeSuccessAction,
  updatePluginTypeFailedAction,
  IFetchPluginTypesAction,
  fetchPluginTypesSuccessAction,
  fetchPluginTypesFailedAction,
  IUpdatePluginTypeAction,
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
} from '../actions/EddiApiActions';
import * as Edditypes from '../components/utils/EddiTypes';
import Parser from '../components/utils/Parser';

export function* FetchBots() {
  try {
    const bots: IBot[] = yield call(getAllBots);
    yield put(fetchBotsSuccessAction(bots));
  } catch (err) {
    yield put(fetchBotsFailedAction(err));
  }
}

export function* watchFetchBots(): Iterator<{}> {
  yield takeEvery(FETCH_BOTS, FetchBots);
}

export function* FetchPackages() {
  try {
    const packages: IPackage[] = yield call(getPackageDescriptors);
    yield put(fetchPackagesSuccessAction(packages));
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
    yield put(fetchBotSuccessAction(bot, bot.resource));
  } catch (err) {
    yield put(fetchBotFailedAction(err));
  }
}

export function* watchFetchBot(): Iterator<{}> {
  yield takeEvery(FETCH_BOT, FetchBot);
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
    );
    yield put(fetchPluginsSuccessAction(plugins));
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

export function* fetchPluginTypes(action: IFetchPluginTypesAction) {
  try {
    const pluginTypes: IPluginTypes[] = yield call(
      getPluginTypes,
      action.packageResource,
    );
    yield put(
      fetchPluginTypesSuccessAction(action.packageResource, pluginTypes),
    );
  } catch (err) {
    yield put(fetchPluginTypesFailedAction(err));
  }
}

export function* watchFetchPluginTypes(): Iterator<{}> {
  yield takeEvery(FETCH_PLUGIN_TYPES_IN_PACKAGE, fetchPluginTypes);
}

export function* updatePluginType(action: IUpdatePluginTypeAction) {
  try {
    const extensions: IPackage = yield call(
      addPluginType,
      action.packageResource,
      action.pluginTypes,
    );
    yield put(
      updatePluginTypeSuccessAction(action.packageResource, extensions),
    );
  } catch (err) {
    yield put(updatePluginTypeFailedAction(err));
  }
}

export function* watchUpdatePluginType(): Iterator<{}> {
  yield takeEvery(UPDATE_PLUGIN_TYPE_IN_PACKAGE, updatePluginType);
}

export function* fetchBotsUsingPackage(
  action: IFetchBotsUsingPackageAction,
): Iterator<{}> {
  try {
    const botsUsingPackage: IBot[] = yield call(
      getBotsUsingPackage,
      action.packageResource,
    );
    yield put(
      fetchBotsUsingPackageSuccessAction(
        action.packageResource,
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
    );
    yield put(
      fetchPackagesUsingPluginSuccessAction(
        action.pluginResource,
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

export function* watchCreateNewConfig(): Iterator<{}> {
  yield takeEvery(CREATE_NEW_CONFIG, createNewConfig);
}

export function* createNewConfig(action: ICreateNewConfigAction): Iterator<{}> {
  try {
    const id = yield call(
      postNewConfig,
      action.eddiType,
      action.name,
      action.description,
      action.data,
    );
    // todo: yield success action.
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
