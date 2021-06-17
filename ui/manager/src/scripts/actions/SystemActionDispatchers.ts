import { ActionCreatorsMapObject, bindActionCreators } from 'redux';
import { store } from '../store/store';
import {
  appReady,
  IAppReadyAction,
  showLoader,
  ILoaderAction,
  hideLoader,
} from './SystemActions';

export interface ISystemActionDispatchers extends ActionCreatorsMapObject {
  appReady: () => IAppReadyAction;
  showLoader: () => ILoaderAction;
  hideLoader: () => ILoaderAction;
}

const actions: ISystemActionDispatchers = {
  appReady,
  showLoader,
  hideLoader,
};

const systemActionDispatchers: ISystemActionDispatchers = bindActionCreators(
  actions,
  store.dispatch,
);

export default systemActionDispatchers;
