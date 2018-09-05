import { ActionCreatorsMapObject, bindActionCreators } from 'redux';
import {
  IBot,
  IDescriptor,
  IDetailedDescriptor,
  IPackage,
  IPlugin,
} from '../components/utils/AxiosFunctions';
import { ModalEnum } from '../components/utils/ModalEnum';
import { store } from '../store/store';
import {
  closeModal,
  ICloseModalAction,
  IShowModalAction,
  showModal,
  IShowViewJsonModalAction,
  showViewJsonModal,
  IShowEditJsonModalAction,
  showEditJsonModal,
  IShowAddPackagesModalAction,
  showAddPackagesModal,
  IShowAddPluginsModalAction,
  showAddPluginsModal,
  IShowEditBotModalAction,
  showEditBotModal,
  IShowEditPackageModalAction,
  showEditPackageModal,
  IShowCreateNewModal,
  showCreateNewModal,
} from './ModalActions';

export interface IModalActionDispatchers extends ActionCreatorsMapObject {
  showModal: (
    mode: ModalEnum,
    bot?: IBot,
    packagePayload?: IPackage,
    pluginType?: string,
    selectedResources?: string[],
    descriptor?: IDetailedDescriptor,
    data?: string,
    addPlugin?: (plugins: string[]) => void,
  ) => IShowModalAction;
  closeModal: () => ICloseModalAction;
  showViewJsonModal: (resource: string) => IShowViewJsonModalAction;
  showEditJsonModal: (resource: string, data: {}) => IShowEditJsonModalAction;
  showAddPackagesModal: (bot: IBot) => IShowAddPackagesModalAction;
  showAddPluginsModal: (
    pluginType: string,
    oldPlugins: string[],
    addPlugin: (plugins: string[]) => void,
  ) => IShowAddPluginsModalAction;
  showEditBotModal: (bot: IBot) => IShowEditBotModalAction;
  showEditPackageModal: (
    packagePayload: IPackage,
  ) => IShowEditPackageModalAction;
  showCreateNewModal: (pluginType: string) => IShowCreateNewModal;
}

const actions: IModalActionDispatchers = {
  showModal,
  closeModal,
  showViewJsonModal,
  showEditJsonModal,
  showAddPackagesModal,
  showAddPluginsModal,
  showEditBotModal,
  showEditPackageModal,
  showCreateNewModal,
};

const modalActionDispatchers: IModalActionDispatchers = bindActionCreators<
  IModalActionDispatchers
>(actions, store.dispatch);

export default modalActionDispatchers;
