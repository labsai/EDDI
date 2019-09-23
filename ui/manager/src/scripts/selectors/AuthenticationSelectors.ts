import { createSelector } from 'reselect';
import { IAppState } from '../reducers';
import { IAuthenticationState } from '../reducers/AuthenticationReducer';

export const AuthenticationStateSelector: (
  state: IAppState,
) => IAuthenticationState = state => state.authenticationState;

export const authenticationSelector: (
  state: IAppState,
) => {
  isAuthenticated: boolean;
  error: Error;
} = createSelector(AuthenticationStateSelector, function(
  authenticationState: IAuthenticationState,
): {
  isAuthenticated: boolean;
  error: Error;
} {
  return {
    isAuthenticated: authenticationState.isAuthenticated,
    error: authenticationState.error,
  };
});
