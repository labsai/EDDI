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
  IShowAddPackagesModalAction,
  IShowAddPluginsModalAction,
  IShowBasicAuthModal,
  IShowBotLogsModalAction,
  IShowConfirmationModal,
  IShowConversationsModal,
  IShowCreateNewConfig2Modal,
  IShowCreateNewConfigModal,
  IShowEditDescriptorModalAction,
  IShowEditJsonModalAction,
  IShowErrorMessageModal,
  IShowModalAction,
  IShowParallelConfigModalAction,
  IShowUpdateBotsModal,
  IShowUpdatePackagesModal,
  IShowViewJsonModalAction,
  showAddPackagesModal,
  showAddPluginsModal,
  showBasicAuthModal,
  showBotLogsModal,
  showConfirmationModal,
  showConversationsModal,
  showCreateNewConfig2Modal,
  showCreateNewConfigModal,
  showEditDescriptorModalAction,
  showEditJsonModal,
  showErrorMessageModal,
  showModal,
  showParallelConfigModal,
  showUpdateBotsModal,
  showUpdatePackagesModal,
  showViewJsonModal,
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
  showBotLogsModal: (bot?: IBot) => IShowBotLogsModalAction;
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
  showConversationsModal: (bot) => IShowConversationsModal;
  showBasicAuthModal: () => IShowBasicAuthModal;

  showParallelConfigModal: (
    packagePayload: IPackage,
    pluginResource?: string,
  ) => IShowParallelConfigModalAction;
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
  showBasicAuthModal,
  showBotLogsModal,
  showParallelConfigModal,
};

const modalActionDispatchers: IModalActionDispatchers = bindActionCreators(
  actions,
  store.dispatch,
);

export default modalActionDispatchers;
