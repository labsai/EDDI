import { Reducer, Action } from 'redux';
import { IBot } from '../components/utils/AxiosFunctions';
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
  UPDATE_BOTS_SUCCESS,
  DEPLOY_BOT_SUCCESS,
  UNDEPLOY_BOT_SUCCESS,
  FETCH_BOT_DEPLOYMENT_STATUS_SUCCESS,
  CREATE_NEW_BOT_SUCCESS,
  ADD_NEW_PACKAGE_TO_BOTS_SUCCESS,
  FETCH_BOT_JSON_SCHEMA_SUCCESS,
  DUPLICATE_SUCCESS,
  FETCH_CONVERSATIONS_SUCCESS,
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
  IFetchBotsUsingPackageSuccessAction,
  IUpdateBotsSuccessAction,
  IDeployBotSuccessAction,
  IUndeployBotSuccessAction,
  IFetchBotDeploymentStatusSuccessAction,
  ICreateNewBotSuccessAction,
  IAddNewPackageToBotsSuccessAction,
  IFetchJsonSchemaSuccessAction,
  IDuplicateSuccessAction,
  IFetchConversationsSuccessAction,
} from '../actions/EddiApiActions';
import * as _ from 'lodash';
import { JSONSchema4 } from 'json-schema';
export type IBotReducer = Reducer<IBotState>;

export interface IBotState {
  bots: IBot[];
  error: Error;
  isLoadingAllBots: boolean;
  isLoadingBot: boolean;
  allBotsLoaded: boolean;
  botsLoaded: number;
  schema: JSONSchema4;
}

export const initialState: IBotState = {
  bots: [],
  error: null,
  isLoadingAllBots: false,
  isLoadingBot: false,
  allBotsLoaded: false,
  botsLoaded: 0,
  schema: null,
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
      const lastIndex =
        (action as IFetchBotsSuccessAction).limit >
        (action as IFetchBotsSuccessAction).bots.length;
      let newBotsLoaded;
      if (
        (action as IFetchBotsSuccessAction).index === 0 &&
        state.botsLoaded !== 0
      ) {
        newBotsLoaded = 0;
      } else {
        newBotsLoaded = (action as IFetchBotsSuccessAction).bots.length;
      }

      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            if (!_.isEmpty((action as IFetchBotsSuccessAction).bots)) {
              return _.uniqBy(
                bots.concat((action as IFetchBotsSuccessAction).bots),
                bot => bot.resource,
              );
            } else {
              return bots;
            }
          },
        },
        isLoadingAllBots: {
          $set: false,
        },
        allBotsLoaded: {
          $set: lastIndex,
        },
        botsLoaded: {
          $set: state.botsLoaded + newBotsLoaded,
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
                bot.resource === (action as IFetchBotSuccessAction).bot.resource
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

    case UPDATE_BOTS_SUCCESS:
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            const updatedBots: IBot[] = (action as IUpdateBotsSuccessAction)
              .bots;
            const newBotList: IBot[] = bots.map(bot => {
              for (let i = 0; i < _.size(updatedBots); i++) {
                if (bot.id === updatedBots[i].id) {
                  return update(bot, {
                    currentVersion: { $set: updatedBots[i].version },
                  });
                }
              }
              return bot;
            });
            return newBotList.concat(updatedBots);
          },
          isLoadingAllBots: {
            $set: false,
          },
        },
      });

    case DEPLOY_BOT_SUCCESS:
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            return bots.map(bot => {
              if (
                bot.resource === (action as IDeployBotSuccessAction).botResource
              ) {
                return update(bot, {
                  deploymentStatus: {
                    $set: 'IN_PROGRESS',
                  },
                });
              }
              return bot;
            });
          },
        },
      });

    case UNDEPLOY_BOT_SUCCESS:
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            return bots.map(bot => {
              if (
                bot.resource ===
                (action as IUndeployBotSuccessAction).botResource
              ) {
                return update(bot, {
                  deploymentStatus: {
                    $set: 'NOT_FOUND',
                  },
                });
              }
              return bot;
            });
          },
        },
      });

    case FETCH_BOT_DEPLOYMENT_STATUS_SUCCESS:
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            return bots.map(bot => {
              if (
                bot.resource ===
                (action as IFetchBotDeploymentStatusSuccessAction).botResource
              ) {
                return update(bot, {
                  deploymentStatus: {
                    $set: (action as IFetchBotDeploymentStatusSuccessAction)
                      .status,
                  },
                });
              }
              return bot;
            });
          },
        },
      });

    case CREATE_NEW_BOT_SUCCESS:
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            return _.uniqBy(
              bots.concat((action as ICreateNewBotSuccessAction).bot),
              bot => bot.resource,
            );
          },
        },
        botsLoaded: {
          $set: state.botsLoaded + 1,
        },
      });

    case ADD_NEW_PACKAGE_TO_BOTS_SUCCESS:
      const updatedBots: IBot[] = (action as IAddNewPackageToBotsSuccessAction)
        .bots;
      const botList: IBot[] = state.bots.map(bot => {
        const newBot = updatedBots.find(newBot => newBot.id === bot.id);
        if (!_.isEmpty(newBot)) {
          return update(bot, {
            currentVersion: { $set: newBot.version },
          });
        } else {
          return bot;
        }
      });
      const newBotList = _.uniqBy(
        updatedBots.concat(botList),
        bot => bot.resource,
      );
      return update(state, {
        bots: {
          $set: newBotList,
        },
      });

    case FETCH_BOT_JSON_SCHEMA_SUCCESS:
      return update(state, {
        schema: {
          $set: (action as IFetchJsonSchemaSuccessAction).schema.value,
        },
      });

    case DUPLICATE_SUCCESS: {
      return update(state, {
        bots: {
          $apply: (bots: IBot[]) => {
            const alreadyExists: boolean = !!bots.find(bot => {
              return (
                bot.resource ===
                (action as IDuplicateSuccessAction).bot.resource
              );
            });
            if (alreadyExists) {
              return bots;
            } else {
              return bots.concat((action as IDuplicateSuccessAction).bot);
            }
          },
        },
        botsLoaded: {
          $set: state.botsLoaded + 1,
        },
      });
    }

    default:
      return state;
  }
};

export default BotReducer;
