import { Reducer, Action } from 'redux';
export type IAuthenticationReducer = Reducer<IAuthenticationState>;
import * as Keycloak from 'keycloak-js';
import * as update from 'immutability-helper';
import {
  BASIC_AUTH_SIGN_IN,
  BASIC_AUTH_SIGN_IN_FAILED,
  BASIC_AUTH_SIGN_IN_SUCCESS,
} from '../actions/AuthenticationActionTypes';
import { IBasicAuthSignInFailedAction } from '../actions/AuthenticationActions';

export interface IAuthenticationState {
  isAuthenticated: boolean;
  keycloak: Keycloak.KeycloakInstance;
  error: Error;
}

export const initialState: IAuthenticationState = {
  isAuthenticated: false,
  keycloak: null,
  error: null,
};

const AuthenticationReducer: IAuthenticationReducer = (
  state: IAuthenticationState = initialState,
  action?: Action,
): IAuthenticationState => {
  if (!action) {
    return state;
  }

  switch (action.type) {
    case BASIC_AUTH_SIGN_IN:
      return update(state, {
        error: {
          $set: null,
        },
      });
    case BASIC_AUTH_SIGN_IN_SUCCESS:
      return update(state, {
        isAuthenticated: {
          $set: true,
        },
      });

    case BASIC_AUTH_SIGN_IN_FAILED:
      return update(state, {
        error: {
          $set: (action as IBasicAuthSignInFailedAction).error,
        },
      });
    default:
      return state;
  }
};

export default AuthenticationReducer;
