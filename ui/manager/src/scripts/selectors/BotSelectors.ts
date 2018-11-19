import { createSelector } from 'reselect';
import { IAppState } from '../reducers';
import { IBotState } from '../reducers/BotReducer';
import { IBot } from '../components/utils/AxiosFunctions';
import * as _ from 'lodash';
import Parser from '../components/utils/Parser';

export const BotStateSelector: (state: IAppState) => IBotState = state =>
  state.botState;

export const botsSelector: (
  state: IAppState,
) => {
  bots: IBot[];
  isLoading: boolean;
  allBotsLoaded: boolean;
  error: Error;
} = createSelector(BotStateSelector, function(
  botState: IBotState,
): {
  bots: IBot[];
  isLoading: boolean;
  allBotsLoaded: boolean;
  error: Error;
} {
  const bots = botState.bots.filter(bot => bot.version === bot.currentVersion);
  const sortedBots = bots.sort(function(a, b) {
    return b.lastModifiedOn - a.lastModifiedOn;
  });
  return {
    bots: sortedBots,
    isLoading: botState.isLoadingAllBots,
    allBotsLoaded: botState.allBotsLoaded,
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

export interface IBotsWithPackageSelectorProps {
  packageResources: string[];
}

export function botsWithPackageSelector(
  state: IAppState,
  props: IBotsWithPackageSelectorProps,
) {
  const botLists = [];
  for (let i = 0; i < _.size(props.packageResources); i++) {
    const botlist = state.botState.bots.filter(
      bot =>
        bot.version === bot.currentVersion &&
        !_.isEmpty(bot.packages) &&
        JSON.stringify(bot.packages).includes(
          Parser.getId(props.packageResources[i]),
        ),
    );
    botLists.push(botlist);
  }
  return {
    botLists,
    allBotsLoaded: state.botState.allBotsLoaded,
    isLoading: state.botState.isLoadingAllBots,
  };
}

export interface ISpecificBotSelectorProps {
  botId: string;
  botVersion: string;
}

export function specificBotSelector(
  state: IAppState,
  props: ISpecificBotSelectorProps,
) {
  let bot: IBot;
  if (!_.isEmpty(props.botVersion)) {
    bot = state.botState.bots.find(
      bot =>
        bot.id === props.botId && bot.version.toString() === props.botVersion,
    );
  } else {
    bot = state.botState.bots.find(
      bot => bot.id === props.botId && bot.version === bot.currentVersion,
    );
  }
  return {
    bot,
    isLoading: state.botState.isLoadingBot,
    error: state.botState.error,
  };
}
