import { bindActionCreators, ActionCreatorsMapObject } from 'redux';
import { store } from '../store/store';
import {
  basicAuthSignInAction,
  basicAuthSignInFailedAction,
  basicAuthSignInSuccessAction,
  checkAuthenticationAction,
  checkAuthenticationFailedAction,
  checkAuthenticationSuccessAction,
  IBasicAuthSignInAction,
  IBasicAuthSignInFailedAction,
  IBasicAuthSignInSuccessAction,
  ICheckAuthenticationAction,
  ICheckAuthenticationFailedAction,
  ICheckAuthenticationSuccessAction,
  IKeycloakRefreshTokenAction,
  IKeycloakRefreshTokenFailedAction,
  IKeycloakRefreshTokenSuccessAction,
  IKeycloakSignInAction,
  IKeycloakSignInFailedAction,
  IKeycloakSignInSuccessAction,
  ISignOutAction,
  ISignOutFailedAction,
  ISignOutSuccessAction,
  keycloakRefreshTokenAction,
  keycloakRefreshTokenFailedAction,
  keycloakRefreshTokenSuccessAction,
  keycloakSignInAction,
  keycloakSignInFailedAction,
  keycloakSignInSuccessAction,
  signOutAction,
  signOutFailedAction,
  signOutSuccessAction,
} from './AuthenticationActions';

export interface IAuthenticationActionDispatchers
  extends ActionCreatorsMapObject {
  basicAuthSignInAction: (username, password) => IBasicAuthSignInAction;
  basicAuthSignInSuccessAction: () => IBasicAuthSignInSuccessAction;
  basicAuthSignInFailedAction: (error) => IBasicAuthSignInFailedAction;
  keycloakSignInAction: (keycloak) => IKeycloakSignInAction;
  keycloakSignInSuccessAction: (keycloak) => IKeycloakSignInSuccessAction;
  keycloakSignInFailedAction: (error) => IKeycloakSignInFailedAction;
  keycloakRefreshTokenAction: (keycloak) => IKeycloakRefreshTokenAction;
  keycloakRefreshTokenSuccessAction: () => IKeycloakRefreshTokenSuccessAction;
  keycloakRefreshTokenFailedAction: (
    error,
  ) => IKeycloakRefreshTokenFailedAction;
  checkAuthenticationAction: () => ICheckAuthenticationAction;
  checkAuthenticationSuccessAction: (
    isKeycloakEnabled,
    isBasicAuthEnabled,
    isReadOnly,
    keycloak,
  ) => ICheckAuthenticationSuccessAction;
  checkAuthenticationFailedAction: (error) => ICheckAuthenticationFailedAction;
  signOutAction: (keycloak) => ISignOutAction;
  signOutSuccessAction: () => ISignOutSuccessAction;
  signOutFailedAction: (error) => ISignOutFailedAction;
}

const actions: IAuthenticationActionDispatchers = {
  basicAuthSignInAction,
  basicAuthSignInFailedAction,
  basicAuthSignInSuccessAction,
  keycloakSignInAction,
  keycloakSignInFailedAction,
  keycloakSignInSuccessAction,
  keycloakRefreshTokenAction,
  keycloakRefreshTokenFailedAction,
  keycloakRefreshTokenSuccessAction,
  checkAuthenticationAction,
  checkAuthenticationFailedAction,
  checkAuthenticationSuccessAction,
  signOutAction,
  signOutFailedAction,
  signOutSuccessAction,
};

const authenticationActionDispatchers: IAuthenticationActionDispatchers = bindActionCreators<
  IAuthenticationActionDispatchers
>(actions, store.dispatch);

export default authenticationActionDispatchers;
