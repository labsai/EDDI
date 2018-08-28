import { createSelector } from 'reselect';
import { IAppState } from '../reducers';
import { ISystemState } from '../reducers/SystemReducer';

export const SystemStateSelector: (state: IAppState) => ISystemState = state =>
  state.systemState;

export const isAppReadySelector: (
  state: IAppState,
) => { isAppReady: boolean } = createSelector(SystemStateSelector, function(
  systemState: ISystemState,
): { isAppReady } {
  return { isAppReady: systemState.isAppReady };
});
