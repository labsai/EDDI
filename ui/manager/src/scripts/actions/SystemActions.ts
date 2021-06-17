import { Action } from 'redux';
import { APP_READY, HIDE_LOADER, SHOW_LOADER } from './SystemActionTypes';

export interface IAppReadyAction extends Action {}
export interface ILoaderAction extends Action {}

export function appReady(): IAppReadyAction {
  return {
    type: APP_READY,
  };
}

export function showLoader(): ILoaderAction {
  return {
    type: SHOW_LOADER,
  };
}

export function hideLoader(): ILoaderAction {
  return {
    type: HIDE_LOADER,
  };
}
