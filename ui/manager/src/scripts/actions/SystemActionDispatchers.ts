import { ActionCreatorsMapObject, bindActionCreators } from 'redux';
import { store } from '../store/store';
import { appReady, IAppReadyAction } from './SystemActions';

export interface ISystemActionDispatchers extends ActionCreatorsMapObject {
  appReady: () => IAppReadyAction;
}

const actions: ISystemActionDispatchers = {
  appReady,
};

const systemActionDispatchers: ISystemActionDispatchers = bindActionCreators(
  actions,
  store.dispatch,
);

export default systemActionDispatchers;
