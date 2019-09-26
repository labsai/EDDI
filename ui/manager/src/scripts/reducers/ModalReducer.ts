import * as update from 'immutability-helper';
import { Action, Reducer } from 'redux';
import {
  IShowAddPackagesModalAction,
  IShowAddPluginsModalAction,
  IShowConfirmationModal,
  IShowConversationsModal,
  IShowCreateNewConfig2Modal,
  IShowCreateNewConfigModal,
  IShowEditDescriptorModalAction,
  IShowEditJsonModalAction,
  IShowModalAction,
  IShowViewJsonModalAction,
} from '../actions/ModalActions';
import {
  CLOSE_MODAL,
  SHOW_ADD_PACKAGES_MODAL,
  SHOW_ADD_PLUGINS_MODAL,
  SHOW_BASIC_AUTH_MODAL,
  SHOW_CONFIRMATION_MODAL,
  SHOW_CONVERSATIONS_MODAL,
  SHOW_CREATE_NEW_CONFIG_2_MODAL,
  SHOW_CREATE_NEW_CONFIG_MODAL,
  SHOW_EDIT_DESCRIPTOR_MODAL,
  SHOW_EDIT_JSON_MODAL,
  SHOW_MODAL,
  SHOW_UPDATE_PACKAGES_MODAL,
  SHOW_VIEW_JSON_MODAL,
} from '../actions/ModalActionTypes';
import {
  IBot,
  IDetailedDescriptor,
  IPackage,
} from '../components/utils/AxiosFunctions';
import { ModalEnum } from '../components/utils/ModalEnum';
import {
  CREATE_NEW_PACKAGE_SUCCESS,
  DEPLOY_BOT_SUCCESS,
  UNDEPLOY_BOT_FAILED,
  UPDATE_DESCRIPTOR_FAILED,
  UPDATE_JSON_DATA_FAILED,
  UPDATE_PACKAGE_SUCCESS,
  UPDATE_PACKAGES_SUCCESS,
  UPDATE_PLUGIN_SUCCESS,
} from '../actions/EddiApiActionTypes';
import {
  ICreateNewPackageSuccessAction,
  IDeployBotSuccessAction,
  IUndeployBotFailedAction,
  IUpdateDescriptorFailedAction,
  IUpdateJsonDataFailedAction,
  IUpdatePackagesSuccessAction,
  IUpdatePackageSuccessAction,
  IUpdatePluginSuccessAction,
} from '../actions/EddiApiActions';
import {
  BASIC_AUTH_SIGN_IN,
  BASIC_AUTH_SIGN_IN_FAILED,
  BASIC_AUTH_SIGN_IN_SUCCESS,
  CHECK_AUTHENTICATION_SUCCESS,
} from '../actions/AuthenticationActionTypes';
import { ICheckAuthenticationSuccessAction } from '../actions/AuthenticationActions';
import { AuthenticationEnum } from './AuthenticationReducer';

export type IModalReducer = Reducer<IModalState>;

export interface IModalState {
  isModalOpen: boolean;
  mode: ModalEnum;
  bot: IBot;
  packagePayload: IPackage;
  pluginType?: string;
  selectedResources?: string[];
  descriptor: IDetailedDescriptor;
  resource: string;
  data: {};
  message: string;
  title: string;
  addPlugin?: (plugins: string[]) => void;
  onConfirm?: () => void;
}

export const initialState: IModalState = {
  isModalOpen: false,
  mode: null,
  bot: null,
  packagePayload: null,
  pluginType: null,
  selectedResources: [],
  descriptor: null,
  resource: null,
  addPlugin: null,
  data: null,
  message: null,
  title: null,
  onConfirm: null,
};

const ModalReducer: IModalReducer = (
  state: IModalState = initialState,
  action?: Action,
): IModalState => {
  if (!action) {
    return state;
  }
  let modalAction;
  switch (action.type) {
    case SHOW_MODAL:
      modalAction = action as IShowModalAction;
      return update(state, {
        isModalOpen: {
          $set: true,
        },
        bot: {
          $set: modalAction.bot,
        },
        packagePayload: {
          $set: modalAction.packagePayload,
        },
        mode: {
          $set: modalAction.mode,
        },
        pluginType: {
          $set: modalAction.pluginType,
        },
        selectedResources: {
          $set: modalAction.selectedResources,
        },
        descriptor: {
          $set: modalAction.descriptor,
        },
        data: {
          $set: modalAction.descriptor,
        },
        addPlugin: {
          $set: modalAction.addPlugin,
        },
      });

    case SHOW_VIEW_JSON_MODAL:
      modalAction = action as IShowViewJsonModalAction;
      return update(state, {
        mode: {
          $set: ModalEnum.viewJson,
        },
        isModalOpen: {
          $set: true,
        },
        resource: {
          $set: modalAction.resource,
        },
      });

    case SHOW_EDIT_JSON_MODAL:
      modalAction = action as IShowEditJsonModalAction;
      return update(state, {
        mode: {
          $set: ModalEnum.editJson,
        },
        isModalOpen: {
          $set: true,
        },
        resource: {
          $set: modalAction.resource,
        },
        data: {
          $set: modalAction.data,
        },
      });

    case SHOW_ADD_PACKAGES_MODAL:
      modalAction = action as IShowAddPackagesModalAction;
      return update(state, {
        mode: {
          $set: ModalEnum.addPackages,
        },
        isModalOpen: {
          $set: true,
        },
        bot: {
          $set: modalAction.bot,
        },
      });

    case SHOW_ADD_PLUGINS_MODAL:
      modalAction = action as IShowAddPluginsModalAction;
      return update(state, {
        mode: {
          $set: ModalEnum.addPlugins,
        },
        isModalOpen: {
          $set: true,
        },
        pluginType: {
          $set: modalAction.pluginType,
        },
        selectedResources: {
          $set: modalAction.oldPlugins,
        },
        addPlugin: {
          $set: modalAction.addPlugin,
        },
      });

    case SHOW_EDIT_DESCRIPTOR_MODAL:
      modalAction = action as IShowEditDescriptorModalAction;
      return update(state, {
        mode: {
          $set: ModalEnum.editDescriptor,
        },
        isModalOpen: {
          $set: true,
        },
        descriptor: {
          $set: modalAction.descriptor,
        },
      });

    case SHOW_CREATE_NEW_CONFIG_MODAL:
      modalAction = action as IShowCreateNewConfigModal;
      return update(state, {
        mode: {
          $set: ModalEnum.createNewConfig,
        },
        isModalOpen: {
          $set: true,
        },
        pluginType: {
          $set: modalAction.pluginType,
        },
        name: {
          $set: modalAction.name,
        },
        description: {
          $set: modalAction.description,
        },
        data: {
          $set: modalAction.data,
        },
        onConfirm: {
          $set: modalAction.onConfirm,
        },
      });

    case SHOW_CREATE_NEW_CONFIG_2_MODAL:
      modalAction = action as IShowCreateNewConfig2Modal;
      return update(state, {
        mode: {
          $set: ModalEnum.createNewConfig2,
        },
        isModalOpen: {
          $set: true,
        },
        pluginType: {
          $set: modalAction.pluginType,
        },
        name: {
          $set: modalAction.name,
        },
        description: {
          $set: modalAction.description,
        },
        data: {
          $set: modalAction.data,
        },
        onConfirm: {
          $set: modalAction.onConfirm,
        },
      });

    case SHOW_UPDATE_PACKAGES_MODAL:
      return update(state, {
        mode: {
          $set: ModalEnum.updatePackages,
        },
        isModalOpen: {
          $set: true,
        },
        resource: {
          $set: modalAction.resource,
        },
      });

    case UPDATE_PLUGIN_SUCCESS:
      return update(state, {
        mode: {
          $set: ModalEnum.updatePackages,
        },
        isModalOpen: {
          $set: true,
        },
        resource: {
          $set: (action as IUpdatePluginSuccessAction).plugin.resource,
        },
      });

    case UPDATE_PACKAGE_SUCCESS:
      return update(state, {
        mode: {
          $set: ModalEnum.updateBots,
        },
        isModalOpen: {
          $set: true,
        },
        selectedResources: {
          $set: [(action as IUpdatePackageSuccessAction).package.resource],
        },
      });

    case UPDATE_PACKAGES_SUCCESS:
      return update(state, {
        mode: {
          $set: ModalEnum.updateBots,
        },
        isModalOpen: {
          $set: true,
        },
        selectedResources: {
          $set: (action as IUpdatePackagesSuccessAction).packages.map(
            pkg => pkg.resource,
          ),
        },
      });

    case SHOW_CONFIRMATION_MODAL:
      return update(state, {
        mode: {
          $set: ModalEnum.confirmation,
        },
        isModalOpen: {
          $set: true,
        },
        onConfirm: {
          $set: (action as IShowConfirmationModal).onConfirm,
        },
        title: {
          $set: (action as IShowConfirmationModal).title,
        },
        message: {
          $set: (action as IShowConfirmationModal).message,
        },
      });

    case DEPLOY_BOT_SUCCESS:
      return update(state, {
        mode: {
          $set: ModalEnum.confirmation,
        },
        isModalOpen: {
          $set: true,
        },
        onConfirm: {
          $set: () =>
            window
              .open(
                (action as IDeployBotSuccessAction).conversationUrl,
                '_blank',
              )
              .focus(),
        },
        title: {
          $set: 'Bot successfully deployed!',
        },
        message: {
          $set: `${
            (action as IDeployBotSuccessAction).botResource
          } has been deployed. \n\n Do you wish to start a conversation?`,
        },
      });

    case UNDEPLOY_BOT_FAILED:
      return update(state, {
        mode: {
          $set: ModalEnum.error,
        },
        isModalOpen: {
          $set: true,
        },
        title: {
          $set: 'Failed to undeploy bot',
        },
        message: {
          $set: `${(action as IUndeployBotFailedAction).error.message}\n\n${
            (action as IUndeployBotFailedAction).response
          }`,
        },
      });

    case UPDATE_DESCRIPTOR_FAILED:
      return update(state, {
        mode: {
          $set: ModalEnum.error,
        },
        isModalOpen: {
          $set: true,
        },
        title: {
          $set: 'Failed to update descriptor',
        },
        message: {
          $set: (action as IUpdateDescriptorFailedAction).error.message,
        },
      });

    case UPDATE_JSON_DATA_FAILED:
      return update(state, {
        mode: {
          $set: ModalEnum.error,
        },
        isModalOpen: {
          $set: true,
        },
        title: {
          $set: 'Failed to update JSON data',
        },
        message: {
          $set: (action as IUpdateJsonDataFailedAction).error.message,
        },
      });

    case CREATE_NEW_PACKAGE_SUCCESS:
      return update(state, {
        mode: {
          $set: ModalEnum.addNewPackageToBot,
        },
        isModalOpen: {
          $set: true,
        },
        packagePayload: {
          $set: (action as ICreateNewPackageSuccessAction).pkg,
        },
      });

    case SHOW_CONVERSATIONS_MODAL: {
      return update(state, {
        mode: {
          $set: ModalEnum.conversations,
        },
        isModalOpen: {
          $set: true,
        },
        bot: {
          $set: (action as IShowConversationsModal).bot,
        },
      });
    }

    case SHOW_BASIC_AUTH_MODAL: {
      return update(state, {
        mode: {
          $set: ModalEnum.basicAuth,
        },
        isModalOpen: {
          $set: true,
        },
      });
    }

    case CHECK_AUTHENTICATION_SUCCESS: {
      if (
        (action as ICheckAuthenticationSuccessAction).authenticationMethod ===
        AuthenticationEnum.basicAuth
      ) {
        return update(state, {
          mode: {
            $set: ModalEnum.basicAuth,
          },
          isModalOpen: {
            $set: true,
          },
        });
      } else {
        return state;
      }
    }

    case BASIC_AUTH_SIGN_IN_SUCCESS: {
      return update(state, {
        mode: {
          $set: null,
        },
        isModalOpen: {
          $set: false,
        },
      });
    }

    case CLOSE_MODAL:
      return update(state, {
        isModalOpen: {
          $set: false,
        },
        bot: {
          $set: null,
        },
        packagePayload: {
          $set: null,
        },
        mode: {
          $set: null,
        },
        pluginType: {
          $set: null,
        },
        selectedResources: {
          $set: [],
        },
        resource: {
          $set: null,
        },
        data: {
          $set: null,
        },
        message: {
          $set: null,
        },
        title: {
          $set: null,
        },
        addPlugin: {
          $set: null,
        },
        onConfirm: {
          $set: null,
        },
      });

    default:
      return state;
  }
};

export default ModalReducer;
