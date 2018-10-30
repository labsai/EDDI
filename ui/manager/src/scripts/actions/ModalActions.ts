import { Action } from 'redux';
import {
  IBot,
  IDetailedDescriptor,
  IPackage,
} from '../components/utils/AxiosFunctions';
import { ModalEnum } from '../components/utils/ModalEnum';
import {
  CLOSE_MODAL,
  SHOW_MODAL,
  SHOW_VIEW_JSON_MODAL,
  SHOW_EDIT_JSON_MODAL,
  SHOW_ADD_PACKAGES_MODAL,
  SHOW_ADD_PLUGINS_MODAL,
  SHOW_EDIT_BOT_MODAL,
  SHOW_EDIT_PACKAGE_MODAL,
  SHOW_CREATE_NEW_CONFIG_MODAL,
  SHOW_CREATE_NEW_CONFIG_2_MODAL,
  SHOW_UPDATE_PACKAGES_MODAL,
  SHOW_UPDATE_BOTS_MODAL,
  SHOW_CONFIRMATION_MODAL,
  SHOW_ERROR_MESSAGE_MODAL,
} from './ModalActionTypes';

export interface IShowModalAction extends Action {
  bot?: IBot;
  packagePayload?: IPackage;
  mode: ModalEnum;
  pluginType?: string;
  selectedResources?: string[];
  descriptor?: IDetailedDescriptor;
  data?: string;
  message?: string;
  addPlugin?: (plugins: string[]) => void;
  onConfirm?: () => void;
}

export function showModal(
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
): IShowModalAction {
  return {
    mode,
    bot,
    packagePayload,
    pluginType,
    selectedResources,
    descriptor,
    data,
    message,
    addPlugin,
    onConfirm,
    type: SHOW_MODAL,
  };
}

export interface ICloseModalAction extends Action {}

export function closeModal(): ICloseModalAction {
  return {
    type: CLOSE_MODAL,
  };
}

export interface IShowViewJsonModalAction extends Action {
  resource: string;
}

export function showViewJsonModal(resource: string): IShowViewJsonModalAction {
  return {
    resource,
    type: SHOW_VIEW_JSON_MODAL,
  };
}

export interface IShowEditJsonModalAction extends Action {
  resource: string;
  data: {};
}

export function showEditJsonModal(
  resource: string,
  data: {},
): IShowEditJsonModalAction {
  return {
    resource,
    data,
    type: SHOW_EDIT_JSON_MODAL,
  };
}

export interface IShowAddPackagesModalAction extends Action {
  bot: IBot;
}

export function showAddPackagesModal(bot: IBot): IShowAddPackagesModalAction {
  return {
    bot,
    type: SHOW_ADD_PACKAGES_MODAL,
  };
}

export interface IShowAddPluginsModalAction extends Action {
  pluginType: string;
  oldPlugins: string[];
  addPlugin: (plugins: string[]) => void;
}

export function showAddPluginsModal(
  pluginType: string,
  oldPlugins: string[],
  addPlugin: (plugins: string[]) => void,
): IShowAddPluginsModalAction {
  return {
    pluginType,
    oldPlugins,
    addPlugin,
    type: SHOW_ADD_PLUGINS_MODAL,
  };
}

export interface IShowEditBotModalAction extends Action {
  bot: IBot;
}

export function showEditBotModal(bot: IBot): IShowEditBotModalAction {
  return {
    bot,
    type: SHOW_EDIT_BOT_MODAL,
  };
}

export interface IShowEditPackageModalAction extends Action {
  packagePayload: IPackage;
}

export function showEditPackageModal(
  packagePayload: IPackage,
): IShowEditPackageModalAction {
  return {
    packagePayload,
    type: SHOW_EDIT_PACKAGE_MODAL,
  };
}

export interface IShowCreateNewConfigModal extends Action {
  pluginType: string;
  name: string;
  description: string;
  data: string;
}

export function showCreateNewConfigModal(
  pluginType: string,
  name: string,
  description: string,
  data: string,
): IShowCreateNewConfigModal {
  return {
    pluginType,
    name,
    description,
    data,
    type: SHOW_CREATE_NEW_CONFIG_MODAL,
  };
}

export interface IShowCreateNewConfig2Modal extends Action {
  pluginType: string;
  name: string;
  description: string;
  data: string;
}

export function showCreateNewConfig2Modal(
  pluginType: string,
  name: string,
  description: string,
  data = '',
): IShowCreateNewConfig2Modal {
  return {
    pluginType,
    name,
    description,
    data,
    type: SHOW_CREATE_NEW_CONFIG_2_MODAL,
  };
}

export interface IShowUpdatePackagesModal extends Action {
  resource: string;
}

export function showUpdatePackagesModal(
  resource: string,
): IShowUpdatePackagesModal {
  return {
    resource,
    type: SHOW_UPDATE_PACKAGES_MODAL,
  };
}

export interface IShowUpdateBotsModal extends Action {
  packageResources: string[];
}

export function showUpdateBotsModal(
  packageResources: string[],
): IShowUpdateBotsModal {
  return {
    packageResources,
    type: SHOW_UPDATE_BOTS_MODAL,
  };
}

export interface IShowConfirmationModal extends Action {
  message: string;
  onConfirm: () => void;
}

export function showConfirmationModal(
  message: string,
  onConfirm: () => void,
): IShowConfirmationModal {
  return {
    message,
    onConfirm,
    type: SHOW_CONFIRMATION_MODAL,
  };
}

export interface IShowErrorMessageModal extends Action {
  title: string;
  message: string;
}

export function showErrorMessageModal(
  title: string,
  message: string,
): IShowErrorMessageModal {
  return {
    title,
    message,
    type: SHOW_ERROR_MESSAGE_MODAL,
  };
}
