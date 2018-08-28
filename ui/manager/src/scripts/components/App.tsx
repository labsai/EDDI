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

interface IPublicProps {}

interface IPrivateProps extends IPublicProps {
  isAppReady: boolean;
}

class App extends React.Component<IPrivateProps> {
  async componentDidMount() {
    await runSagaMiddleware();
    SystemActionDispatchers.appReady();
  }

  render() {
    return (
      <div className="ui container">
        {renderIf(this.props.isAppReady)(() => (
          <div>
            <Route path="/" exact component={Dashboard} />
            <Route path="/botview/:id" exact component={BotViewPage} />
            <Route path="/packageview/:id" exact component={PackageViewPage} />
            <ModalComponentFrame />
          </div>
        ))}
      </div>
    );
  }
}

export default connect(isAppReadySelector)(App);
