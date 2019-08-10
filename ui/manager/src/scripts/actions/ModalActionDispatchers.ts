import { ActionCreatorsMapObject, bindActionCreators } from 'redux';
import {
  IBot,
  IDescriptor,
  IDetailedDescriptor,
  IPackage,
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
  IShowEditDescriptorModalAction,
  showEditDescriptorModalAction,
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
  IShowErrorMessageModal,
  showErrorMessageModal,
  IShowConversationsModal,
  showConversationsModal,
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
  showEditDescriptorModalAction: (
    descriptor: IDescriptor,
  ) => IShowEditDescriptorModalAction;
  showCreateNewConfigModal: (
    pluginType: string,
    name?: string,
    description?: string,
    data?: string,
    onConfirm?: () => void,
  ) => IShowCreateNewConfigModal;
  showCreateNewConfig2Modal: (
    pluginType: string,
    name: string,
    description: string,
    data?: string,
    onConfirm?: () => void,
  ) => IShowCreateNewConfig2Modal;
  showUpdatePackagesModal: (resource: string) => IShowUpdatePackagesModal;
  showUpdateBotsModal: (packageResources: string[]) => IShowUpdateBotsModal;
  showConfirmationModal: (
    title: string,
    message?: string,
    onConfirm?: () => void,
  ) => IShowConfirmationModal;
  showErrorMessageModal: (
    title: string,
    message: string,
  ) => IShowErrorMessageModal;
  showConversationsModal: (bot: IBot) => IShowConversationsModal;
}

const actions: IModalActionDispatchers = {
  showModal,
  closeModal,
  showViewJsonModal,
  showEditJsonModal,
  showAddPackagesModal,
  showAddPluginsModal,
  showCreateNewConfigModal,
  showCreateNewConfig2Modal,
  showUpdatePackagesModal,
  showUpdateBotsModal,
  showConfirmationModal,
  showErrorMessageModal,
  showEditDescriptorModalAction,
  showConversationsModal,
};

const modalActionDispatchers: IModalActionDispatchers = bindActionCreators<
  IModalActionDispatchers
>(actions, store.dispatch);

export default modalActionDispatchers;
