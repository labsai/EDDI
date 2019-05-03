import { createSelector } from 'reselect';
import { IAppState } from '../reducers';
import { ISystemState } from '../reducers/SystemReducer';
import { BOT, PACKAGE } from '../components/utils/EddiTypes';

export const SystemStateSelector: (state: IAppState) => ISystemState = state =>
  state.systemState;

export const isAppReadySelector: (
  state: IAppState,
) => { isAppReady: boolean } = createSelector(SystemStateSelector, function(
  systemState: ISystemState,
): { isAppReady } {
  return { isAppReady: systemState.isAppReady };
});

export interface ISchemaSelectorProps {
  type: string;
}

export function schemaSelector(state: IAppState, props: ISchemaSelectorProps) {
  switch (props.type) {
    case BOT:
      return {
        schema: state.botState.schema,
      };
    case PACKAGE:
      return {
        schema: state.packageState.schema,
      };
    default:
      const eddiSchema = state.pluginState.schemas.find(
        schema => schema.name === props.type,
      );
      if (eddiSchema) {
        return {
          schema: eddiSchema.value,
        };
      } else {
        return {};
      }
  }
}
