import * as React from 'react';
import '../styles/base.scss';
import { render } from 'react-dom';
import App from './components/App';
import { Provider } from 'react-redux';
import { Router, Route } from 'react-router-dom';
import { persistor, store } from './store/store';
import { history } from './history';
import { PersistGate } from 'redux-persist/integration/react';

render(
  <Provider store={store}>
    <PersistGate loading={null} persistor={persistor}>
      <Router history={history}>
        <Route component={App} />
      </Router>
    </PersistGate>
  </Provider>,
  document.getElementById('root'),
);
