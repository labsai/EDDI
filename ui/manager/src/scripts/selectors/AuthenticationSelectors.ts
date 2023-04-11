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
      // isKeycloakEnabled: authenticationState.isKeycloakEnabled,
      // keycloakAuthenticated: authenticationState.keycloakAuthenticated,
      // basicAuthAuthenticated: authenticationState.basicAuthAuthenticated,
      isKeycloakEnabled: true, // ! should be authenticationState.isKeycloakEnabled ^^^
      keycloakAuthenticated: true, // ! should be authenticationState.keycloakAuthenticated ^^^
      basicAuthAuthenticated: true, // ! should be authenticationState.basicAuthAuthenticated ^^^
      error: authenticationState.error,
    };
  },
);

export function readOnlySelector(state: IAppState) {
  return {
    // readOnly: state.authenticationState.readOnly,
    readOnly: false, // ! this should be like above ^^^
  };
}
