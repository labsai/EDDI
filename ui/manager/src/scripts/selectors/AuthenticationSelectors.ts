import { createSelector } from 'reselect';
import { IAppState } from '../reducers';
import { IAuthenticationState } from '../reducers/AuthenticationReducer';

export const AuthenticationStateSelector: (
  state: IAppState,
) => IAuthenticationState = state => state.authenticationState;

export const authenticationSelector: (
  state: IAppState,
) => {
  keycloak: Keycloak.KeycloakInstance;
  isKeycloakEnabled: boolean;
  isBasicAuthEnabled: boolean;
  isAuthenticated: boolean;
  error: Error;
} = createSelector(AuthenticationStateSelector, function(
  authenticationState: IAuthenticationState,
): {
  keycloak: Keycloak.KeycloakInstance;
  isKeycloakEnabled: boolean;
  isBasicAuthEnabled: boolean;
  isAuthenticated: boolean;
  error: Error;
} {
  return {
    keycloak: authenticationState.keycloak,
    isKeycloakEnabled: authenticationState.isKeycloakEnabled,
    isBasicAuthEnabled: authenticationState.isBasicAuthEnabled,
    isAuthenticated:
      authenticationState.keycloakAuthenticated &&
      authenticationState.basicAuthAuthenticated,
    error: authenticationState.error,
  };
});
