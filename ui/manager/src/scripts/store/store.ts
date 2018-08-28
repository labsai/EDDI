import {
  createStore,
  applyMiddleware,
  compose,
  Store,
  Middleware,
  StoreEnhancer,
} from 'redux';
import createSagaMiddleware from 'redux-saga';
import reducers from '../reducers/index';
import { SagaMiddleware } from 'redux-saga';
import { IAppState } from '../reducers/index';
import * as _ from 'lodash';

export const sagaMiddleware: SagaMiddleware<any> = createSagaMiddleware();

export const store: Store<IAppState> = configureStore();

declare const __DEV__: boolean; // provided by webpack.DefinePlugin

function configureStore(initialState?: any) {
  console.log(`Booting in "${process.env.NODE_ENV}" environment`);

  if (__DEV__) {
    console.log(`__DEV__ ${__DEV__}`);
  }

  const middleWares: Middleware[] = [sagaMiddleware];

  let devTools: any = _.identity;

  if (__DEV__ && _.isFunction((window as any).__REDUX_DEVTOOLS_EXTENSION__)) {
    console.log('Redux devtools enabled');
    devTools = (window as any).__REDUX_DEVTOOLS_EXTENSION__();
  }

  const enhancer: StoreEnhancer<any> = compose(
    applyMiddleware(...middleWares),
    devTools,
  );

  const store: Store<IAppState> = createStore(reducers, initialState, enhancer);

  if (module.hot) {
    module.hot.accept(() => {
      const nextRootReducer: any = require('../reducers/index').default;
      store.replaceReducer(nextRootReducer);
    });
  }

  return store;
}
