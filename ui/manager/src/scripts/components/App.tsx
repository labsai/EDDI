import * as React from 'react';
import { Route } from 'react-router-dom';
import SystemActionDispatchers from '../actions/SystemActionDispatchers';
import { isAppReadySelector } from '../selectors/SystemSelectors';
import ModalComponentFrame from './ModalComponent/ModalComponentFrame';
import Dashboard from './pages/Dashboard';
import BotViewPage from './pages/BotViewPage';
import PackagePage from './pages/PackagePage';
import { run as runSagaMiddleware } from '../sagas';
import * as renderIf from 'render-if';
import { connect } from 'react-redux';
import PackageViewPage from './pages/PackageViewPage';
import ExtensionsPage from './pages/ExtensionsPage';
import * as Keycloak from 'keycloak-js';
import * as kcHelper from './utils/keycloakFunctions';
import { historyPush } from '../history';
import WhiteButton from './Assets/Buttons/WhiteButton';
import { CSSProperties } from 'react';
import {
  getAuthClientId,
  getReadOnly,
  setApiUrlQuery,
  setReadOnlyQuery,
} from './utils/ApiFunctions';
import { library } from '@fortawesome/fontawesome-svg-core';
import Parser from './utils/Parser';
import {
  faCheck,
  faCheckCircle,
  faCompress,
  faExpand,
  faRedo,
  faUndo,
  faEllipsisV,
  faPlus,
  faMinus,
  faComments,
  faSync,
} from '@fortawesome/free-solid-svg-icons';
import BotConversationViewPage from './pages/BotConversationViewPage';
import ConversationsPage from './pages/ConversationsPage';
import { authenticationSelector } from '../selectors/AuthenticationSelectors';
import { compose, pure, setDisplayName } from 'recompose';
import authenticationActionDispatchers from '../actions/AuthenticationActionDispatchers';

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
}

const sleep = milliseconds => {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
};

const App = ({
  location,
  keycloakAuthenticated,
  isKeycloakEnabled,
  keycloak,
  isAppReady,
  basicAuthAuthenticated,
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
    if (!keycloak) { return; }
    authenticationActionDispatchers.keycloakRefreshTokenAction(keycloak);
    await sleep(240000).then(() => refreshToken());
  };

  const initKeycloak = async () => {
    if (!keycloak) { return; }
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
    if (isKeycloakEnabled &&
      keycloakAuthenticated) {
        refreshToken();
      }
  }, []);

  const authenticated = keycloakAuthenticated && basicAuthAuthenticated;

  return (
    <div className="ui container">
      {renderIf(isAppReady)(() => (
        <div>
          {renderIf(!authenticated)(() => (
            <div>{'You need to login to see this page'}</div>
          ))}
          {renderIf(authenticated)(() => (
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
          ))}
          {renderIf(keycloakAuthenticated)(() => (
            <ModalComponentFrame />
          ))}
        </div>
      ))}
    </div>
  );
};

const ComposedApp: React.ComponentClass<IPrivateProps> = compose<IPrivateProps, IPrivateProps>(
  pure,
  connect(authenticationSelector),
  connect(isAppReadySelector),
  setDisplayName('App'),
)(App);

export default ComposedApp;
