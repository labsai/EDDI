import * as update from 'immutability-helper';
import { Action, Reducer } from 'redux';
import { APP_READY } from '../actions/SystemActionTypes';

export type ISystemReducer = Reducer<ISystemState>;

export interface ISystemState {
  isAppReady: boolean;
}

export const initialState: ISystemState = {
  isAppReady: false,
};

const SystemReducer: ISystemReducer = (
  state: ISystemState = initialState,
  action?: Action,
): ISystemState => {
  if (!action) {
    return state;
  }

  switch (action.type) {
    case APP_READY:
      return update(state, {
        isAppReady: {
          $set: true,
        },
      });

    default:
      return state;
  }
};

export default SystemReducer;
