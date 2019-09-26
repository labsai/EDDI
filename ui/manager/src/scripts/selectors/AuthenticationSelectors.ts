import { createSelector } from 'reselect';
import { IAppState } from '../reducers';
import {
  AuthenticationEnum,
  IAuthenticationState,
} from '../reducers/AuthenticationReducer';

export const AuthenticationStateSelector: (
  state: IAppState,
) => IAuthenticationState = state => state.authenticationState;

export const authenticationSelector: (
  state: IAppState,
) => {
  keycloak: Keycloak.KeycloakInstance;
  authenticationMethod: AuthenticationEnum;
  isAuthenticated: boolean;
  error: Error;
} = createSelector(AuthenticationStateSelector, function(
  authenticationState: IAuthenticationState,
): {
  keycloak: Keycloak.KeycloakInstance;
  authenticationMethod: AuthenticationEnum;
  isAuthenticated: boolean;
  error: Error;
} {
  return {
    keycloak: authenticationState.keycloak,
    authenticationMethod: authenticationState.authenticationMethod,
    isAuthenticated: authenticationState.isAuthenticated,
    error: authenticationState.error,
  };
});
