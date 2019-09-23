import { bindActionCreators, ActionCreatorsMapObject } from 'redux';
import { store } from '../store/store';
import {
  basicAuthSignInAction,
  basicAuthSignInFailedAction,
  basicAuthSignInSuccessAction,
  IBasicAuthSignInAction,
  IBasicAuthSignInFailedAction,
  IBasicAuthSignInSuccessAction,
  IKeycloakRefreshTokenAction,
  IKeycloakRefreshTokenFailedAction,
  IKeycloakRefreshTokenSuccessAction,
  IKeycloakSignInAction,
  IKeycloakSignInFailedAction,
  IKeycloakSignInSuccessAction,
  keycloakRefreshTokenAction,
  keycloakRefreshTokenFailedAction,
  keycloakRefreshTokenSuccessAction,
  keycloakSignInAction,
  keycloakSignInFailedAction,
  keycloakSignInSuccessAction,
} from './AuthenticationActions';

export interface IAuthenticationActionDispatchers
  extends ActionCreatorsMapObject {
  basicAuthSignInAction: (username, password) => IBasicAuthSignInAction;
  basicAuthSignInSuccessAction: () => IBasicAuthSignInSuccessAction;
  basicAuthSignInFailedAction: (error) => IBasicAuthSignInFailedAction;
  keycloakSignInAction: () => IKeycloakSignInAction;
  keycloakSignInSuccessAction: (keycloak) => IKeycloakSignInSuccessAction;
  keycloakSignInFailedAction: (error) => IKeycloakSignInFailedAction;
  keycloakRefreshTokenAction: (keycloak) => IKeycloakRefreshTokenAction;
  keycloakRefreshTokenSuccessAction: () => IKeycloakRefreshTokenSuccessAction;
  keycloakRefreshTokenFailedAction: (
    error,
  ) => IKeycloakRefreshTokenFailedAction;
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
};

const authenticationActionDispatchers: IAuthenticationActionDispatchers = bindActionCreators<
  IAuthenticationActionDispatchers
>(actions, store.dispatch);

export default authenticationActionDispatchers;
