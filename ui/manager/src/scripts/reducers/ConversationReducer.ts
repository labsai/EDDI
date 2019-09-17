import { Reducer, Action } from 'redux';
import { IBot, IConversation } from '../components/utils/AxiosFunctions';
import * as _ from 'lodash';
import * as update from 'immutability-helper';
import {
  END_CONVERSATION_SUCCESS,
  FETCH_CONVERSATION,
  FETCH_CONVERSATION_FAILED,
  FETCH_CONVERSATION_SUCCESS,
  FETCH_CONVERSATIONS,
  FETCH_CONVERSATIONS_FAILED,
  FETCH_CONVERSATIONS_SUCCESS,
} from '../actions/EddiApiActionTypes';
import {
  IEndConversationSuccessAction,
  IFetchBotDataSuccessAction,
  IFetchBotsSuccessAction,
  IFetchConversationFailedAction,
  IFetchConversationsFailedAction,
  IFetchConversationsSuccessAction,
  IFetchConversationSuccessAction,
} from '../actions/EddiApiActions';
import { CONVERSATION_ENDED } from '../components/utils/helpers/ConversationHelper';
export type IConversationReducer = Reducer<IConversationState>;

export interface IConversationState {
  conversations: IConversation[];
  error: Error;
  isLoadingAllConversations: boolean;
  isLoadingConversation: boolean;
  allConversationsLoaded: boolean;
  conversationsLoaded: number;
}

export const initialState: IConversationState = {
  conversations: [],
  error: null,
  isLoadingAllConversations: false,
  isLoadingConversation: false,
  allConversationsLoaded: false,
  conversationsLoaded: 0,
};

const ConversationReducer: IConversationReducer = (
  state: IConversationState = initialState,
  action?: Action,
): IConversationState => {
  if (!action) {
    return state;
  }

  switch (action.type) {
    case FETCH_CONVERSATIONS:
      return update(state, {
        isLoadingAllConversations: {
          $set: true,
        },
      });

    case FETCH_CONVERSATIONS_FAILED:
      return update(state, {
        error: {
          $set: (action as IFetchConversationsFailedAction).error,
        },
        isLoadingAllConversations: {
          $set: false,
        },
      });

    case FETCH_CONVERSATIONS_SUCCESS:
      const lastIndex =
        (action as IFetchConversationsSuccessAction).limit >
        (action as IFetchConversationsSuccessAction).conversations.length;
      let conversationsLoaded;
      if ((action as IFetchConversationsSuccessAction).index === 0) {
        conversationsLoaded = (action as IFetchConversationsSuccessAction)
          .conversations.length;
      } else {
        conversationsLoaded =
          state.conversationsLoaded +
          (action as IFetchConversationsSuccessAction).conversations.length;
      }
      return update(state, {
        conversations: {
          $apply: (conversations: IConversation[]) => {
            if (
              !_.isEmpty(
                (action as IFetchConversationsSuccessAction).conversations,
              )
            ) {
              return _.uniqBy(
                conversations.concat(
                  (action as IFetchConversationsSuccessAction).conversations,
                ),
                conversation => conversation.resource,
              );
            } else {
              return conversations;
            }
          },
        },
        isLoadingAllConversations: {
          $set: false,
        },
        allConversationsLoaded: {
          $set: lastIndex,
        },
        conversationsLoaded: {
          $set: conversationsLoaded,
        },
      });

    case FETCH_CONVERSATION:
      return update(state, {
        isLoadingConversation: {
          $set: true,
        },
      });

    case FETCH_CONVERSATION_FAILED:
      return update(state, {
        isLoadingConversation: {
          $set: false,
        },
        error: {
          $set: (action as IFetchConversationFailedAction).error,
        },
      });

    case FETCH_CONVERSATION_SUCCESS:
      return update(state, {
        isLoadingConversation: {
          $set: false,
        },
        conversations: {
          $apply: (conversations: IConversation[]) => {
            return conversations.map(conversation => {
              if (
                conversation.resource.includes(
                  (action as IFetchConversationSuccessAction).conversationId,
                )
              ) {
                return update(conversation, {
                  data: {
                    $set: (action as IFetchConversationSuccessAction)
                      .conversation,
                  },
                });
              }
              return conversation;
            });
          },
        },
      });

    case END_CONVERSATION_SUCCESS:
      return update(state, {
        conversations: {
          $apply: (conversations: IConversation[]) => {
            return conversations.map(conversation => {
              if (
                conversation.resource.includes(
                  (action as IEndConversationSuccessAction).conversationId,
                )
              ) {
                if (conversation.data) {
                  return update(conversation, {
                    conversationState: {
                      $set: CONVERSATION_ENDED,
                    },
                    data: {
                      conversationState: {
                        $set: CONVERSATION_ENDED,
                      },
                    },
                  });
                } else {
                  return update(conversation, {
                    conversationState: {
                      $set: CONVERSATION_ENDED,
                    },
                  });
                }
              }
              return conversation;
            });
          },
        },
      });

    default:
      return state;
  }
};

export default ConversationReducer;
