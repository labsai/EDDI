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
  IShowCreateNewConfigModal,
  showCreateNewConfigModal,
  IShowCreateNewConfig2Modal,
  showCreateNewConfig2Modal,
  IShowUpdatePackagesModal,
  IShowUpdateBotsModal,
  showUpdatePackagesModal,
  showUpdateBotsModal,
  showConfirmationModal,
  IShowConfirmationModal,
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
    message?: string,
    addPlugin?: (plugins: string[]) => void,
    onConfirm?: () => void,
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
  showCreateNewConfigModal: (
    pluginType: string,
    name?: string,
    description?: string,
    data?: string,
  ) => IShowCreateNewConfigModal;
  showCreateNewConfig2Modal: (
    pluginType: string,
    name: string,
    description: string,
    data?: string,
  ) => IShowCreateNewConfig2Modal;
  showUpdatePackagesModal: (resource: string) => IShowUpdatePackagesModal;
  showUpdateBotsModal: (packageResources: string[]) => IShowUpdateBotsModal;
  showConfirmationModal: (
    message: string,
    onConfirm: () => void,
  ) => IShowConfirmationModal;
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
  showCreateNewConfigModal,
  showCreateNewConfig2Modal,
  showUpdatePackagesModal,
  showUpdateBotsModal,
  showConfirmationModal,
};

const modalActionDispatchers: IModalActionDispatchers = bindActionCreators<
  IModalActionDispatchers
>(actions, store.dispatch);

export default modalActionDispatchers;
