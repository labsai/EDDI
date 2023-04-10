import { Reducer, Action } from 'redux';
export type IAuthenticationReducer = Reducer<IAuthenticationState>;
import * as Keycloak from 'keycloak-js';
import update from 'immutability-helper';
import {
  BASIC_AUTH_SIGN_IN,
  BASIC_AUTH_SIGN_IN_FAILED,
  BASIC_AUTH_SIGN_IN_SUCCESS,
  CHECK_AUTHENTICATION,
  CHECK_AUTHENTICATION_SUCCESS,
  KEYCLOAK_SIGN_IN,
  KEYCLOAK_SIGN_IN_SUCCESS,
  SIGN_OUT_SUCCESS,
} from '../actions/AuthenticationActionTypes';
import {
  IBasicAuthSignInFailedAction,
  ICheckAuthenticationSuccessAction,
  IKeycloakSignInSuccessAction,
} from '../actions/AuthenticationActions';

export interface IAuthenticationState {
  isKeycloakEnabled: boolean;
  isBasicAuthEnabled: boolean;
  readOnly: boolean;
  keycloakAuthenticated: boolean;
  basicAuthAuthenticated: boolean;
  keycloak: Keycloak.KeycloakInstance;
  error: Error;
}

export const initialState: IAuthenticationState = {
  isKeycloakEnabled: false,
  isBasicAuthEnabled: false,
  readOnly: false,
  // keycloakAuthenticated: false,
  // basicAuthAuthenticated: false,
  keycloakAuthenticated: true, // ! should be false ^^^
  basicAuthAuthenticated: true, // ! should be false ^^^
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
        basicAuthAuthenticated: {
          $set: true,
        },
      });

    case BASIC_AUTH_SIGN_IN_FAILED:
      return update(state, {
        error: {
          $set: (action as IBasicAuthSignInFailedAction).error,
        },
      });

    case KEYCLOAK_SIGN_IN:
      return update(state, {
        error: {
          $set: null,
        },
      });

    case KEYCLOAK_SIGN_IN_SUCCESS:
      return update(state, {
        keycloakAuthenticated: {
          $set: (action as IKeycloakSignInSuccessAction).keycloak.authenticated,
        },
      });

    case CHECK_AUTHENTICATION_SUCCESS:
      return update(state, {
        isKeycloakEnabled: {
          $set: (action as ICheckAuthenticationSuccessAction).isKeycloakEnabled,
        },
        isBasicAuthEnabled: {
          $set: (action as ICheckAuthenticationSuccessAction)
            .isBasicAuthEnabled,
        },
        readOnly: {
          $set: (action as ICheckAuthenticationSuccessAction).isReadOnly,
        },
        keycloak: {
          $set: (action as ICheckAuthenticationSuccessAction).keycloak,
        },
        keycloakAuthenticated: {
          $set: !(action as ICheckAuthenticationSuccessAction)
            .isKeycloakEnabled,
        },
        basicAuthAuthenticated: {
          $set: !(action as ICheckAuthenticationSuccessAction)
            .isBasicAuthEnabled,
        },
      });

    case SIGN_OUT_SUCCESS:
      return update(state, {
        keycloakAuthenticated: {
          $set: !(action as ICheckAuthenticationSuccessAction)
            .isKeycloakEnabled,
        },
        basicAuthAuthenticated: {
          $set: !(action as ICheckAuthenticationSuccessAction)
            .isBasicAuthEnabled,
        },
      });

    default:
      return state;
  }
};

export default AuthenticationReducer;
