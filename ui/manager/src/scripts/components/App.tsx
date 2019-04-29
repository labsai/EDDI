import * as React from 'react';
import { Route } from 'react-router-dom';
import SystemActionDispatchers from '../actions/SystemActionDispatchers';
import { isAppReadySelector } from '../selectors/SystemSelectors';
import ModalComponentFrame from './ModalComponent/ModalComponentFrame';
import Dashboard from './pages/Dashboard';
import BotViewPage from './pages/BotViewPage';
import { run as runSagaMiddleware } from '../sagas';
import * as renderIf from 'render-if';
import { connect } from 'react-redux';
import PackageViewPage from './pages/PackageViewPage';
import RegularDictionaryPage from './pages/ExtensionsPage';
import * as Keycloak from 'keycloak-js';
import * as kcHelper from './utils/keycloakFunctions';
import { history, historyPush } from '../history';
import WhiteButton from './Assets/Buttons/WhiteButton';
import { CSSProperties } from 'react';
import * as _ from 'lodash';
import {
  getAPIUrl,
  getAuthClientId,
  getAuthMethod,
  getAuthRealm,
  getAuthUrl,
  setApiUrlQuery,
} from './utils/ApiFunctions';
import Parser from './utils/Parser';

const styles: CSSProperties = {
  logoutButton: {
    height: '36px',
    marginTop: '7px',
    float: 'right',
  },
};

interface IRouteProps {
  match: { params: { id: string } };
  location: { search: string };
}

interface IPrivateProps extends IRouteProps {
  isAppReady: boolean;
}

interface IState {
  keycloak: Keycloak.KeycloakInstance;
  isAuthenticated: boolean;
}

const sleep = milliseconds => {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
};

class App extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      keycloak: null,
      isAuthenticated: false,
    };
  }

  async componentDidMount() {
    await runSagaMiddleware();
    const queryStrings = Parser.getQueryStrings(this.props.location.search);
    console.log(queryStrings.apiUrl);
    console.log(decodeURIComponent('http%3A%2F%2Flocalhost%3A7070'));
    if (
      queryStrings.apiUrl &&
      decodeURIComponent(queryStrings.apiUrl) !== (await getAPIUrl())
    ) {
      console.log(decodeURIComponent(queryStrings.apiUrl));
      setApiUrlQuery(decodeURIComponent(queryStrings.apiUrl));
    }
    console.log('test');
    console.log(await getAPIUrl());
    SystemActionDispatchers.appReady();
    if (await kcHelper.keycloakEnabled()) {
      await this.initKeycloak();
    } else {
      this.setState({ isAuthenticated: true });
    }
  }

  async initKeycloak() {
    const k = await kcHelper.createKeycloakInstance();
    this.setState({
      keycloak: k,
    });
    await kcHelper.initKeycloak(k, this.authenticate);
  }

  authenticate = () => {
    this.setState({
      isAuthenticated: true,
    });
    this.refreshToken();
  };

  async refreshToken() {
    kcHelper.updateToken(this.state.keycloak);
    await sleep(240000).then(() => this.refreshToken());
  }

  logout = () => {
    historyPush('/');
    kcHelper.logout(this.state.keycloak);
  };

  render() {
    console.log(this.props);
    return (
      <div className="ui container">
        {renderIf(this.props.isAppReady)(() => (
          <div>
            {renderIf(!this.state.isAuthenticated)(() => (
              <div>{'You need to login to see this page'}</div>
            ))}
            {renderIf(this.state.isAuthenticated)(() => (
              <div>
                {renderIf(!!this.state.keycloak)(() => (
                  <WhiteButton
                    text={'Logout'}
                    customStyles={styles.logoutButton}
                    onClick={this.logout}
                  />
                ))}
                <Route path="/" exact component={Dashboard} />
                <Route path="/botview/:id" component={BotViewPage} />
                <Route path="/packageview/:id" component={PackageViewPage} />
                <Route
                  path="/resources/:type"
                  exact
                  component={RegularDictionaryPage}
                />
              </div>
            ))}
            <ModalComponentFrame />
          </div>
        ))}
      </div>
    );
  }
}

export default connect(isAppReadySelector)(App);
