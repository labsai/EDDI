import { library } from '@fortawesome/fontawesome-svg-core';
import {
  faCheck,
  faCheckCircle,
  faComments,
  faCompress,
  faEllipsisV,
  faExpand,
  faMinus,
  faPlus,
  faRedo,
  faSync,
  faUndo,
} from '@fortawesome/free-solid-svg-icons';
import * as Keycloak from 'keycloak-js';
import * as React from 'react';
import { connect } from 'react-redux';
import { Route } from 'react-router-dom';
import { compose, pure, setDisplayName } from 'recompose';
import authenticationActionDispatchers from '../actions/AuthenticationActionDispatchers';
import SystemActionDispatchers from '../actions/SystemActionDispatchers';
import { run as runSagaMiddleware } from '../sagas';
import { authenticationSelector } from '../selectors/AuthenticationSelectors';
import { isChatOpenedSelector } from '../selectors/ChatSelectors';
import { isAppReadySelector } from '../selectors/SystemSelectors';
import Chat from './Chat/Chat';
import ModalComponentFrame from './ModalComponent/ModalComponentFrame';
import BotConversationViewPage from './pages/BotConversationViewPage';
import BotViewPage from './pages/BotViewPage';
import ConversationsPage from './pages/ConversationsPage';
import Dashboard from './pages/Dashboard';
import ExtensionsPage from './pages/ExtensionsPage';
import PackagePage from './pages/PackagePage';
import PackageViewPage from './pages/PackageViewPage';
import { setApiUrlQuery, setReadOnlyQuery } from './utils/ApiFunctions';
import * as kcHelper from './utils/keycloakFunctions';
import Parser from './utils/Parser';
import useChangeBodyMaxWidth from './utils/useChangeBodyMaxWidth';

library.add(faUndo);
library.add(faRedo);
library.add(faCheck);
library.add(faExpand);
library.add(faCompress);
library.add(faCheckCircle);
library.add(faEllipsisV);
library.add(faPlus);
library.add(faMinus);
library.add(faComments);
library.add(faSync);

interface IRouteProps {
  match: { params: { id: string } };
  location: { search: string };
}

interface IPrivateProps extends IRouteProps {
  isAppReady: boolean;
  keycloak: Keycloak.KeycloakInstance;
  isKeycloakEnabled: boolean;
  isBasicAuthEnabled: boolean;
  keycloakAuthenticated: boolean;
  basicAuthAuthenticated: boolean;
  isOpened: boolean;
}

const sleep = (milliseconds) => {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
};

const App = ({
  location,
  keycloakAuthenticated,
  isKeycloakEnabled,
  keycloak,
  isAppReady,
  basicAuthAuthenticated,
  isOpened: isChatOpened,
}: IPrivateProps) => {
  React.useEffect(() => {
    runSagaMiddleware();
    const queryStrings = Parser.getQueryStrings(location.search);
    if (queryStrings.apiUrl) {
      setApiUrlQuery(decodeURIComponent(queryStrings.apiUrl));
    }
    if (queryStrings.readOnly) {
      setReadOnlyQuery(decodeURIComponent(queryStrings.readOnly));
    }
    SystemActionDispatchers.appReady();
    authenticationActionDispatchers.checkAuthenticationAction();
  }, []);

  const refreshToken = async () => {
    if (!keycloak) {
      return;
    }
    authenticationActionDispatchers.keycloakRefreshTokenAction(keycloak);
    await sleep(240000).then(() => refreshToken());
  };

  const initKeycloak = async () => {
    if (!keycloak) {
      return;
    }
    await kcHelper.initKeycloak(keycloak);
  };

  React.useEffect(() => {
    if (isKeycloakEnabled && !keycloakAuthenticated) {
      if (!keycloak.authenticated) {
        initKeycloak();
      }
    }
  }, [isKeycloakEnabled, keycloakAuthenticated, keycloak]);

  React.useEffect(() => {
    if (isKeycloakEnabled && keycloakAuthenticated) {
      refreshToken();
    }
  }, []);

  const authenticated = keycloakAuthenticated && basicAuthAuthenticated;

  useChangeBodyMaxWidth(isChatOpened);

  return (
    <>
      <div className={`ui container ${isChatOpened ? 'with-chat' : null}`}>
        {isAppReady && (
          <div>
            {!authenticated && (
              <div>{'You need to login to see this page'}</div>
            )}
            {authenticated && (
              <div>
                <Route path={'/'} exact component={Dashboard} />
                <Route path={'/packages'} exact component={PackagePage} />
                <Route
                  path={'/conversations'}
                  exact
                  component={ConversationsPage}
                />
                <Route path={'/resources'} component={ExtensionsPage} />
                <Route path={'/botview/:id'} component={BotViewPage} />
                <Route
                  path={'/conversationview/:id'}
                  component={BotConversationViewPage}
                />
                <Route path={'/packageview/:id'} component={PackageViewPage} />
              </div>
            )}
            {keycloakAuthenticated && <ModalComponentFrame />}
          </div>
        )}
      </div>
      {isChatOpened && <Chat />}
    </>
  );
};

const ComposedApp: React.ComponentClass<IPrivateProps> = compose<
  IPrivateProps,
  IPrivateProps
>(
  pure,
  connect(authenticationSelector),
  connect(isAppReadySelector),
  connect(isChatOpenedSelector),
  setDisplayName('App'),
)(App);

export default ComposedApp;
