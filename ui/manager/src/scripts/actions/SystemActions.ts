import { Action } from 'redux';
import { APP_READY } from './SystemActionTypes';

export interface IAppReadyAction extends Action {}

export function appReady(): IAppReadyAction {
  return {
    type: APP_READY,
  };
}
