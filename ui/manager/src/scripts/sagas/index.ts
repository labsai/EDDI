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
  watchFetchBotsUsingPackage,
  watchFetchPackagesUsingPlugin,
  watchUpdateJsonData,
  watchCreateNewConfig,
  watchUpdatePackages,
  watchUpdateBots,
  watchDeployBot,
  watchUndeployBot,
  watchFetchBotDeploymentStatus,
  watchFetchCurrentBot,
  watchCreateNewBot,
  watchCreateNewPackage,
  watchAddNewPackageToBots,
  watchFetchJsonSchema,
  watchDuplicateResource,
  watchFetchConversations,
  watchFetchConversation,
  watchEndConversation,
  watchDeployExampleBots,
} from './EddiApiSaga';
import {
  watchBasicAuthSignIn,
  watchCheckAuthentication,
  watchKeycloakRefreshToken,
  watchKeycloakSignIn,
  watchSignOut,
} from './AuthenticationSaga';
import { watchChat } from './ChatSaga';

function* root() {
  yield fork(watchFetchBot);
  yield fork(watchFetchCurrentBot);
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
  yield fork(watchFetchBotsUsingPackage);
  yield fork(watchFetchPackagesUsingPlugin);
  yield fork(watchUpdateJsonData);
  yield fork(watchCreateNewConfig);
  yield fork(watchUpdatePackages);
  yield fork(watchUpdateBots);
  yield fork(watchDeployBot);
  yield fork(watchUndeployBot);
  yield fork(watchFetchBotDeploymentStatus);
  yield fork(watchCreateNewBot);
  yield fork(watchCreateNewPackage);
  yield fork(watchAddNewPackageToBots);
  yield fork(watchFetchJsonSchema);
  yield fork(watchDuplicateResource);
  yield fork(watchFetchConversations);
  yield fork(watchFetchConversation);
  yield fork(watchEndConversation);
  yield fork(watchBasicAuthSignIn);
  yield fork(watchKeycloakSignIn);
  yield fork(watchKeycloakRefreshToken);
  yield fork(watchCheckAuthentication);
  yield fork(watchSignOut);
  yield fork(watchDeployExampleBots);
  yield fork(watchChat);
}

export const run: () => Task = () => sagaMiddleware.run(root);
