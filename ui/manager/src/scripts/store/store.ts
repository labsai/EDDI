import {
  createStore,
  applyMiddleware,
  compose,
  Store,
  Middleware,
  StoreEnhancer,
} from 'redux';
import createSagaMiddleware, { SagaMiddleware } from 'redux-saga';
import reducers from '../reducers/index';
import { IAppState } from '../reducers/index';
import * as _ from 'lodash';
import { persistStore, persistReducer, Persistor } from 'redux-persist';
import storage from 'redux-persist/lib/storage';

const persistConfig = {
  key: 'root',
  storage,
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
