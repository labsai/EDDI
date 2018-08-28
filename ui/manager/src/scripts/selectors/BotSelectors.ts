import { createSelector } from 'reselect';
import { IAppState } from '../reducers';
import { IBotState } from '../reducers/BotReducer';
import { IBot } from '../components/utils/AxiosFunctions';
import * as _ from 'lodash';

export const BotStateSelector: (state: IAppState) => IBotState = state =>
  state.botState;

export const botsSelector: (
  state: IAppState,
) => {
  bots: IBot[];
  isLoading: boolean;
  error: Error;
} = createSelector(BotStateSelector, function(
  botState: IBotState,
): {
  bots: IBot[];
  isLoading: boolean;
  error: Error;
} {
  const bots = botState.bots.filter(bot => bot.version === bot.currentVersion);
  const sortedBots = bots.sort(function(a, b) {
    return b.lastModifiedOn - a.lastModifiedOn;
  });
  return {
    bots: sortedBots,
    isLoading: botState.isLoadingAllBots,
    error: botState.error,
  };
});

export interface ILatestBotSelectorProps {
  botId: string;
}
export interface IBotSelectorProps {
  botResource: string;
}

export function botSelector(state: IAppState, props: IBotSelectorProps) {
  const bot = state.botState.bots.find(
    bot => bot.resource === props.botResource,
  );
  return {
    bot,
    isLoading: state.botState.isLoadingAllBots || state.botState.isLoadingBot,
  };
}

export function latestBotSelector(
  state: IAppState,
  props: ILatestBotSelectorProps,
) {
  const bots = state.botState.bots.filter(bot => bot.id === props.botId);
  const bot = _.maxBy(bots, b => b.version);
  return {
    bot,
    isLoading: state.botState.isLoadingAllBots || state.botState.isLoadingBot,
  };
}
