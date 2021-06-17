import update from 'immutability-helper';
import { Action, Reducer } from 'redux';
import {
  APP_READY,
  HIDE_LOADER,
  SHOW_LOADER,
} from '../actions/SystemActionTypes';

export type ISystemReducer = Reducer<ISystemState>;

export interface ISystemState {
  isAppReady: boolean;
  isLoading: boolean;
}

export const initialState: ISystemState = {
  isAppReady: false,
  isLoading: false,
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
    case SHOW_LOADER:
      return update(state, {
        isLoading: {
          $set: true,
        },
      });
    case HIDE_LOADER:
      return update(state, {
        isLoading: {
          $set: false,
        },
      });

    default:
      return state;
  }
};

export default SystemReducer;
