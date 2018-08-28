import { Reducer, Action } from 'redux';
import { IBot, IDescriptor, IPlugin } from '../components/utils/AxiosFunctions';
import {
  FETCH_BOT,
  FETCH_BOT_FAILED,
  FETCH_BOT_SUCCESS,
  FETCH_BOTS,
  FETCH_BOTS_FAILED,
  FETCH_BOTS_SUCCESS,
  FETCH_BOTDATA_SUCCESS,
  UPDATE_BOT_SUCCESS,
  UPDATE_DESCRIPTOR_SUCCESS,
  UPDATE_BOT_PACKAGES_SUCCESS,
  FETCH_BOTS_USING_PACKAGE_SUCCESS,
} from '../actions/EddiApiActionTypes';
import * as update from 'immutability-helper';
import {
  IFetchBotsFailedAction,
  IFetchBotsSuccessAction,
  IFetchBotSuccessAction,
  IFetchBotDataSuccessAction,
  IUpdateBotSuccessAction,
  IUpdateDescriptorSuccessAction,
  IUpdateBotPackagesSuccessAction,
  IFetchPluginsSuccessAction,
  IFetchBotsUsingPackageSuccessAction,
} from '../actions/EddiApiActions';
import * as _ from 'lodash';

export type IBotReducer = Reducer<IBotState>;

export interface IBotState {
  bots: IBot[];
  error: Error;
  isLoadingAllBots: boolean;
  isLoadingBot: boolean;
}

export const initialState: IBotState = {
  bots: [],
  error: null,
  isLoadingAllBots: false,
  isLoadingBot: false,
};

const BotReducer: IBotReducer = (
  state: IBotState = initialState,
  action?: Action,
): IBotState => {
  if (!action) {
    return state;
  }

  switch (action.type) {
    case FETCH_BOTS:
      return update(state, {
        isLoadingAllBots: {
          $set: true,
        },
      });

    case FETCH_BOTS_SUCCESS:
      return update(state, {
        bots: {
          $set: (action as IFetchBotsSuccessAction).bots,
        },
        isLoadingAllBots: {
          $set: false,
        },
      });

    case FETCH_BOTS_FAILED:
      return update(state, {
        error: {
          $set: (action as IFetchBotsFailedAction).error,
        },
        isLoadingAllBots: {
          $set: false,
        },
      });

    case FETCH_BOTDATA_SUCCESS:
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            return bots.map(bot => {
              if (
                bot.resource ===
                (action as IFetchBotDataSuccessAction).botResource
              ) {
                return update(bot, {
                  packages: {
                    $set: (action as IFetchBotDataSuccessAction).botData
                      .packages,
                  },
                  channels: {
                    $set: (action as IFetchBotDataSuccessAction).botData
                      .channels,
                  },
                });
              }
              return bot;
            });
          },
        },
      });

    case FETCH_BOT:
      return update(state, {
        isLoadingBot: {
          $set: true,
        },
      });

    case FETCH_BOT_SUCCESS:
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            const alreadyExists: boolean = !!bots.find(bot => {
              return (
                bot.resource === (action as IFetchBotSuccessAction).botResource
              );
            });
            if (alreadyExists) {
              return bots;
            } else {
              return bots.concat((action as IFetchBotSuccessAction).bot);
            }
          },
        },
        isLoadingBot: {
          $set: false,
        },
      });

    case FETCH_BOT_FAILED:
      return update(state, {
        error: {
          $set: (action as IFetchBotsFailedAction).error,
        },
        isLoadingBot: {
          $set: false,
        },
      });

    case UPDATE_BOT_SUCCESS:
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            const updatedBot = (action as IUpdateBotSuccessAction).bot;
            const updatedBotList = bots.map(bot => {
              if (bot.id === updatedBot.id) {
                return update(bot, {
                  currentVersion: { $set: updatedBot.version },
                });
              }
              return bot;
            });
            updatedBotList.unshift(updatedBot);
            return updatedBotList;
          },
        },
      });

    case UPDATE_BOT_PACKAGES_SUCCESS:
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            const updatedBot = (action as IUpdateBotPackagesSuccessAction).bot;
            const updatedBotList = bots.map(bot => {
              if (bot.id === updatedBot.id) {
                return update(bot, {
                  currentVersion: { $set: updatedBot.version },
                });
              }
              return bot;
            });
            return updatedBotList.unshift(updatedBot);
          },
        },
      });

    case UPDATE_DESCRIPTOR_SUCCESS:
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            return bots.map(bot => {
              if (
                bot.resource ===
                (action as IUpdateDescriptorSuccessAction).resource
              ) {
                return update(bot, {
                  description: {
                    $set: (action as IUpdateDescriptorSuccessAction)
                      .description,
                  },
                  name: {
                    $set: (action as IUpdateDescriptorSuccessAction).name,
                  },
                });
              } else {
                return bot;
              }
            });
          },
        },
      });

    case FETCH_BOTS_USING_PACKAGE_SUCCESS:
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            if (
              !_.isEmpty((action as IFetchBotsUsingPackageSuccessAction).bots)
            ) {
              return _.uniqBy(
                bots.concat(
                  (action as IFetchBotsUsingPackageSuccessAction).bots,
                ),
                bot => bot.resource,
              );
            } else {
              return bots;
            }
          },
        },
      });
    default:
      return state;
  }
};

export default BotReducer;
