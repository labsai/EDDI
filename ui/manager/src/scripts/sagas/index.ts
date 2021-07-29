import { Task } from 'redux-saga';
import { fork } from 'redux-saga/effects';
import { sagaMiddleware } from '../store/store';
import {
  watchBasicAuthSignIn,
  watchCheckAuthentication,
  watchKeycloakRefreshToken,
  watchKeycloakSignIn,
  watchSignOut,
} from './AuthenticationSaga';
import { watchBotLogs } from './BotSaga';
import { watchChat } from './ChatSaga';
import {
  watchAddNewPackageToBots,
  watchCreateNewBot,
  watchCreateNewConfig,
  watchCreateNewPackage,
  watchDeployBot,
  watchDeployExampleBots,
  watchDuplicateResource,
  watchEndConversation,
  watchFetchBot,
  watchFetchBotData,
  watchFetchBotDeploymentStatus,
  watchFetchBots,
  watchFetchBotsUsingPackage,
  watchFetchConversation,
  watchFetchConversations,
  watchFetchCurrentBot,
  watchFetchCurrentPackage,
  watchFetchDefaultPluginTypes,
  watchFetchJsonSchema,
  watchFetchPackage,
  watchFetchPackageData,
  watchFetchPackages,
  watchFetchPackagesUsingPlugin,
  watchFetchPlugin,
  watchFetchPlugins,
  watchMassUpdateJsonData,
  watchUndeployBot,
  watchUpdateBot,
  watchUpdateBotPackages,
  watchUpdateBots,
  watchUpdateDescriptor,
  watchUpdateJsonData,
  watchUpdatePackage,
  watchUpdatePackages,
  watchUpdateExtensionsOrder,
} from './EddiApiSaga';

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
  yield fork(watchBotLogs);
  yield fork(watchMassUpdateJsonData);
  yield fork(watchUpdateExtensionsOrder);
}

export const run: () => Task = () => sagaMiddleware.run(root);
