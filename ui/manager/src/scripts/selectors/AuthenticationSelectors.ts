import { createSelector } from 'reselect';
import { IAppState } from '../reducers';
import { IAuthenticationState } from '../reducers/AuthenticationReducer';

export const AuthenticationStateSelector: (
  state: IAppState,
) => IAuthenticationState = (state) => state.authenticationState;

export const authenticationSelector: (state: IAppState) => {
  keycloak: Keycloak.KeycloakInstance;
  isKeycloakEnabled: boolean;
  isBasicAuthEnabled: boolean;
  keycloakAuthenticated: boolean;
  basicAuthAuthenticated: boolean;
  error: Error;
} = createSelector(
  AuthenticationStateSelector,
  function (authenticationState: IAuthenticationState): {
    keycloak: Keycloak.KeycloakInstance;
    isKeycloakEnabled: boolean;
    isBasicAuthEnabled: boolean;
    keycloakAuthenticated: boolean;
    basicAuthAuthenticated: boolean;
    error: Error;
  } {
    return {
      keycloak: authenticationState.keycloak,
      isBasicAuthEnabled: authenticationState.isBasicAuthEnabled,
      isKeycloakEnabled: true,
      keycloakAuthenticated: true,
      basicAuthAuthenticated: true,
      error: authenticationState.error,
    };
  },
);

export function readOnlySelector(state: IAppState) {
  return {
    readOnly: false,
  };
}
