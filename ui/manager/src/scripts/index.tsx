import * as React from 'react';
import '../styles/base.scss';
import { render } from 'react-dom';
import App from './components/App';
import { Provider } from 'react-redux';
import { Router, Route } from 'react-router-dom';
import createBrowserHistory from 'history/createBrowserHistory';
import { store } from './store/store';

export const history = createBrowserHistory();

render(
  <Provider store={store}>
    <Router history={history}>
      <Route component={App} />
    </Router>
  </Provider>,
  document.getElementById('root'),
);
