import * as update from 'immutability-helper';
import { Action, Reducer } from 'redux';
import {
  IShowAddPackagesModalAction,
  IShowAddPluginsModalAction,
  IShowCreateNewConfigModal,
  IShowEditBotModalAction,
  IShowEditJsonModalAction,
  IShowEditPackageModalAction,
  IShowModalAction,
  IShowViewJsonModalAction,
} from '../actions/ModalActions';
import {
  CLOSE_MODAL,
  SHOW_ADD_PACKAGES_MODAL,
  SHOW_ADD_PLUGINS_MODAL,
  SHOW_CREATE_NEW_CONFIG_MODAL,
  SHOW_EDIT_BOT_MODAL,
  SHOW_EDIT_JSON_MODAL,
  SHOW_EDIT_PACKAGE_MODAL,
  SHOW_MODAL,
  SHOW_VIEW_JSON_MODAL,
} from '../actions/ModalActionTypes';
import {
  IBot,
  IDetailedDescriptor,
  IPackage,
} from '../components/utils/AxiosFunctions';
import { ModalEnum } from '../components/utils/ModalEnum';

export type IModalReducer = Reducer<IModalState>;

export interface IModalState {
  isModalOpen: boolean;
  mode: ModalEnum;
  bot?: IBot;
  packagePayload: IPackage;
  pluginType?: string;
  selectedResources?: string[];
  descriptor: IDetailedDescriptor;
  resource: string;
  data: {};
  addPlugin?: (plugins: string[]) => void;
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

    case SHOW_EDIT_BOT_MODAL:
      modalAction = action as IShowEditBotModalAction;
      return update(state, {
        mode: {
          $set: ModalEnum.editBot,
        },
        isModalOpen: {
          $set: true,
        },
        bot: {
          $set: modalAction.bot,
        },
      });

    case SHOW_EDIT_PACKAGE_MODAL:
      modalAction = action as IShowEditPackageModalAction;
      return update(state, {
        mode: {
          $set: ModalEnum.editPackage,
        },
        isModalOpen: {
          $set: true,
        },
        packagePayload: {
          $set: modalAction.packagePayload,
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
      });

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
        addPlugin: {
          $set: null,
        },
      });

    default:
      return state;
  }
};

export default ModalReducer;
