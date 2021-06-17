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
      isKeycloakEnabled: authenticationState.isKeycloakEnabled,
      isBasicAuthEnabled: authenticationState.isBasicAuthEnabled,
      keycloakAuthenticated: authenticationState.keycloakAuthenticated,
      basicAuthAuthenticated: authenticationState.basicAuthAuthenticated,
      error: authenticationState.error,
    };
  },
);

export function readOnlySelector(state: IAppState) {
  return {
    readOnly: state.authenticationState.readOnly,
  };
}
