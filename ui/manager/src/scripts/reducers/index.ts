import { combineReducers, ReducersMapObject } from 'redux';
import BotReducer, { IBotReducer, IBotState } from './BotReducer';
import ModalReducer, { IModalReducer, IModalState } from './ModalReducer';
import SystemReducer, { ISystemReducer, ISystemState } from './SystemReducer';
import PackageReducer, {
  IPackageReducer,
  IPackageState,
} from './PackageReducer';
import {
  IPluginReducer,
  IPluginState,
  default as PluginReducer,
} from './PluginReducer';

interface IBotNameSpace<T> {
  botState: T;
}

interface ISystemNameSpace<T> {
  systemState: T;
}

interface IPackageNameSpace<T> {
  packageState: T;
}

interface IPluginNameSpace<T> {
  pluginState: T;
}

interface IModalNameSpace<T> {
  modalState: T;
}

export interface IAppState
  extends IBotNameSpace<IBotState>,
    ISystemNameSpace<ISystemState>,
    IPackageNameSpace<IPackageState>,
    IPluginNameSpace<IPluginState>,
    IModalNameSpace<IModalState> {}

interface IReducers
  extends ReducersMapObject,
    IBotNameSpace<IBotReducer>,
    ISystemNameSpace<ISystemReducer>,
    IPackageNameSpace<IPackageReducer>,
    IPluginNameSpace<IPluginReducer>,
    IModalNameSpace<IModalReducer> {}

const reducers: IReducers = {
  botState: BotReducer,
  packageState: PackageReducer,
  pluginState: PluginReducer,
  systemState: SystemReducer,
  modalState: ModalReducer,
};

export default combineReducers(reducers);
