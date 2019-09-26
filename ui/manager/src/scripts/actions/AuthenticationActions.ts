import { Action } from 'redux';
import {
  BASIC_AUTH_SIGN_IN,
  BASIC_AUTH_SIGN_IN_FAILED,
  BASIC_AUTH_SIGN_IN_SUCCESS,
  CHECK_AUTHENTICATION,
  CHECK_AUTHENTICATION_FAILED,
  CHECK_AUTHENTICATION_SUCCESS,
  KEYCLOAK_REFRESH_TOKEN,
  KEYCLOAK_REFRESH_TOKEN_FAILED,
  KEYCLOAK_REFRESH_TOKEN_SUCCESS,
  KEYCLOAK_SIGN_IN,
  KEYCLOAK_SIGN_IN_FAILED,
  KEYCLOAK_SIGN_IN_SUCCESS,
  KEYCLOAK_SIGN_OUT,
  SIGN_OUT,
  SIGN_OUT_FAILED,
  SIGN_OUT_SUCCESS,
} from './AuthenticationActionTypes';
import * as Keycloak from 'keycloak-js';
import { AuthenticationEnum } from '../reducers/AuthenticationReducer';

export interface IBasicAuthSignInAction extends Action {
  username: string;
  password: string;
}

export function basicAuthSignInAction(
  username: string,
  password: string,
): IBasicAuthSignInAction {
  return {
    username,
    password,
    type: BASIC_AUTH_SIGN_IN,
  };
}

export interface IBasicAuthSignInFailedAction extends Action {
  error: Error;
}

export function basicAuthSignInFailedAction(
  error: Error,
): IBasicAuthSignInFailedAction {
  return {
    error,
    type: BASIC_AUTH_SIGN_IN_FAILED,
  };
}

export interface IBasicAuthSignInSuccessAction extends Action {}

export function basicAuthSignInSuccessAction(): IBasicAuthSignInSuccessAction {
  return {
    type: BASIC_AUTH_SIGN_IN_SUCCESS,
  };
}

export interface IKeycloakSignInAction extends Action {
  keycloak: Keycloak.KeycloakInstance;
}

export function keycloakSignInAction(
  keycloak: Keycloak.KeycloakInstance,
): IKeycloakSignInAction {
  return {
    keycloak,
    type: KEYCLOAK_SIGN_IN,
  };
}

export interface IKeycloakSignInSuccessAction extends Action {
  keycloak: Keycloak.KeycloakInstance;
}

export function keycloakSignInSuccessAction(
  keycloak: Keycloak.KeycloakInstance,
): IKeycloakSignInSuccessAction {
  return {
    keycloak,
    type: KEYCLOAK_SIGN_IN_SUCCESS,
  };
}

export interface IKeycloakSignInFailedAction extends Action {
  error: Error;
}

export function keycloakSignInFailedAction(
  error: Error,
): IKeycloakSignInFailedAction {
  return {
    error,
    type: KEYCLOAK_SIGN_IN_FAILED,
  };
}

export interface IKeycloakRefreshTokenAction extends Action {
  keycloak: Keycloak.KeycloakInstance;
}

export function keycloakRefreshTokenAction(
  keycloak: Keycloak.KeycloakInstance,
): IKeycloakRefreshTokenAction {
  return {
    keycloak,
    type: KEYCLOAK_REFRESH_TOKEN,
  };
}

export interface IKeycloakRefreshTokenSuccessAction extends Action {}

export function keycloakRefreshTokenSuccessAction(): IKeycloakRefreshTokenSuccessAction {
  return {
    type: KEYCLOAK_REFRESH_TOKEN_SUCCESS,
  };
}

export interface IKeycloakRefreshTokenFailedAction extends Action {
  error: Error;
}

export function keycloakRefreshTokenFailedAction(
  error: Error,
): IKeycloakRefreshTokenFailedAction {
  return {
    error,
    type: KEYCLOAK_REFRESH_TOKEN_FAILED,
  };
}

export interface ISignOutAction extends Action {
  keycloak: Keycloak.KeycloakInstance;
}

export function signOutAction(
  keycloak: Keycloak.KeycloakInstance,
): ISignOutAction {
  return {
    keycloak,
    type: SIGN_OUT,
  };
}

export interface ISignOutSuccessAction extends Action {}

export function signOutSuccessAction(): ISignOutSuccessAction {
  return {
    type: SIGN_OUT_SUCCESS,
  };
}

export interface ISignOutFailedAction extends Action {
  error: Error;
}

export function signOutFailedAction(error: Error): ISignOutFailedAction {
  return {
    error,
    type: SIGN_OUT_FAILED,
  };
}

export interface ICheckAuthenticationAction extends Action {}

export function checkAuthenticationAction(): ICheckAuthenticationAction {
  return {
    type: CHECK_AUTHENTICATION,
  };
}

export interface ICheckAuthenticationSuccessAction extends Action {
  authenticationMethod: AuthenticationEnum;
  keycloak: Keycloak.KeycloakInstance;
}

export function checkAuthenticationSuccessAction(
  authenticationMethod: AuthenticationEnum,
  keycloak: Keycloak.KeycloakInstance,
): ICheckAuthenticationSuccessAction {
  return {
    keycloak,
    authenticationMethod,
    type: CHECK_AUTHENTICATION_SUCCESS,
  };
}

export interface ICheckAuthenticationFailedAction extends Action {
  error: Error;
}

export function checkAuthenticationFailedAction(
  error: Error,
): ICheckAuthenticationFailedAction {
  return {
    error,
    type: CHECK_AUTHENTICATION_FAILED,
  };
}
