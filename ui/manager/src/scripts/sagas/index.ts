import { fork } from 'redux-saga/effects';
import { sagaMiddleware } from '../store/store';
import { Task } from 'redux-saga';
import {
  watchFetchBot,
  watchFetchBots,
  watchFetchPackages,
  watchFetchPackageData,
  watchFetchBotData,
  watchFetchPackage,
  watchFetchPlugins,
  watchFetchPlugin,
  watchFetchDefaultPluginTypes,
  watchUpdateBot,
  watchUpdateBotPackages,
  watchUpdatePackage,
  watchUpdateDescriptor,
  watchFetchCurrentPackage,
  watchFetchPluginTypes,
  watchUpdatePluginType,
  watchFetchBotsUsingPackage,
  watchFetchPackagesUsingPlugin,
  watchUpdateJsonData,
  watchCreateNewConfig,
  watchUpdatePackages,
  watchUpdateBots,
  watchDeployBot,
  watchUndeployBot,
  watchUpdateBotDeploymentStatus,
} from './EddiApiSaga';

function* root() {
  yield fork(watchFetchBot);
  yield fork(watchFetchBots);
  yield fork(watchFetchPackages);
  yield fork(watchFetchBotData);
  yield fork(watchFetchPackage);
  yield fork(watchFetchCurrentPackage);
  yield fork(watchFetchDefaultPluginTypes);
  yield fork(watchFetchPlugins);
  yield fork(watchFetchPlugin);
  yield fork(watchUpdateBot);
  yield fork(watchUpdateBotPackages);
  yield fork(watchUpdatePackage);
  yield fork(watchFetchPackageData);
  yield fork(watchUpdateDescriptor);
  yield fork(watchFetchPluginTypes);
  yield fork(watchUpdatePluginType);
  yield fork(watchFetchBotsUsingPackage);
  yield fork(watchFetchPackagesUsingPlugin);
  yield fork(watchUpdateJsonData);
  yield fork(watchCreateNewConfig);
  yield fork(watchUpdatePackages);
  yield fork(watchUpdateBots);
  yield fork(watchDeployBot);
  yield fork(watchUndeployBot);
  yield fork(watchUpdateBotDeploymentStatus);
}

export const run: () => Task = () => sagaMiddleware.run(root);
