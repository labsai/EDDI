import * as _ from 'lodash';
import {
  applyMiddleware,
  compose,
  createStore,
  Middleware,
  Store,
  StoreEnhancer,
} from 'redux';
import { Persistor, persistReducer, persistStore } from 'redux-persist';
import storage from 'redux-persist/lib/storage';
import createSagaMiddleware, { SagaMiddleware } from 'redux-saga';
import reducers, { IAppState } from '../reducers/index';

const persistConfig = {
  key: 'root',
  storage,
  whitelist: ['authenticationState'],
};

export const sagaMiddleware: SagaMiddleware<any> = createSagaMiddleware();

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

  const persistedReducer = persistReducer(persistConfig, reducers);

  const store: Store<IAppState> = createStore(
    persistedReducer,
    initialState,
    enhancer,
  );
  const persistor: Persistor = persistStore(store);

  if (module.hot) {
    module.hot.accept(() => {
      const nextRootReducer: any = require('../reducers/index').default;
      store.replaceReducer(nextRootReducer);
    });
  }

  return { store, persistor };
}

export const persistor: Persistor = configureStore().persistor;
export const store: Store<IAppState> = configureStore().store;
