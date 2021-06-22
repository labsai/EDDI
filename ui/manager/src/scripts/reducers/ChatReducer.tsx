import update from 'immutability-helper';
import { Action, Reducer } from 'redux';
import {
  IStartChatFailedAction,
  IStartChatSuccessAction,
  IReplyInChatSuccesAction,
  IReplyInChatFailedAction,
  ISetChatContextAction,
  ISetUserReplyAction,
} from '../actions/ChatActions';
import {
  CLEAR_CHAT,
  CLOSE_SIDE_CHAT,
  DISABLE_ANIMATION,
  ENABLE_ANIMATION,
  OPEN_SIDE_CHAT,
  REPLY_IN_CHAT,
  REPLY_IN_CHAT_FAILED,
  REPLY_IN_CHAT_SUCCESS,
  RESTART_CHAT,
  RESTART_CHAT_FAILED,
  RESTART_CHAT_SUCCESS,
  SET_CHAT_CONTEXT,
  SET_USER_REPLY,
  START_CHAT,
  START_CHAT_FAILED,
  START_CHAT_SUCCESS,
} from '../actions/ChatActionsTypes';
import * as _ from 'lodash';

export interface IChatState {
  isOpened: boolean;
  isLoading: boolean;
  error: string;
  context: string;
  data: any;
  step: number;
  animation: boolean;
}

export const initialState: IChatState = {
  isOpened: false,
  isLoading: false,
  error: null,
  data: null,
  step: 0,
  context: null,
  animation: true,
};

export type IChatReducer = Reducer<IChatState>;

const ChatReducer: IChatReducer = (
  state: IChatState = initialState,
  action?: Action,
): IChatState => {
  if (!action) {
    return state;
  }

  switch (action.type) {
    case OPEN_SIDE_CHAT:
      return update(state, {
        isOpened: {
          $set: true,
        },
      });
    case CLOSE_SIDE_CHAT:
      return update(state, {
        isOpened: {
          $set: false,
        },
      });
    case START_CHAT:
    case RESTART_CHAT:
      return update(state, {
        isLoading: {
          $set: true,
        },
        error: {
          $set: null,
        },
      });
    case RESTART_CHAT_SUCCESS:
    case START_CHAT_SUCCESS: {
      if (!(action as IStartChatSuccessAction).data) {
        return update(state, {
          isLoading: {
            $set: false,
          },
          error: {
            $set: null,
          },
        });
      }
      return update(state, {
        data: {
          $set: [(action as IStartChatSuccessAction).data],
        },
        isLoading: {
          $set: false,
        },
        error: {
          $set: null,
        },
      });
    }
    case RESTART_CHAT_FAILED:
    case START_CHAT_FAILED:
      return update(state, {
        isLoading: {
          $set: false,
        },
        error: {
          $set: (action as IStartChatFailedAction).error,
        },
      });
    case SET_USER_REPLY: {
      return update(state, {
        data: {
          $apply: (data: any) => {
            const newData = [...data];
            const lastStepData = newData.pop();
            Object.assign(lastStepData, {
              userReply: (action as ISetUserReplyAction).userReply,
            });
            return [...newData, lastStepData];
          },
        },
      });
    }
    case REPLY_IN_CHAT:
      return update(state, {
        isLoading: {
          $set: true,
        },
        error: {
          $set: null,
        },
      });
    case REPLY_IN_CHAT_SUCCESS:
      return update(state, {
        data: {
          $apply: (data: any) => {
            if (!_.isEmpty((action as IReplyInChatSuccesAction).data)) {
              return [...data, (action as IReplyInChatSuccesAction).data];
            } else {
              return data;
            }
          },
        },
        step: {
          $apply: (step: number) => {
            const newStep = step + 1;
            return newStep;
          },
        },
        isLoading: {
          $set: false,
        },
        error: {
          $set: null,
        },
      });
    case REPLY_IN_CHAT_FAILED:
      return update(state, {
        isLoading: {
          $set: false,
        },
        error: {
          $set: (action as IReplyInChatFailedAction).error,
        },
      });
    case SET_CHAT_CONTEXT:
      return update(state, {
        context: {
          $set: (action as ISetChatContextAction).context,
        },
      });
    case DISABLE_ANIMATION:
      return update(state, {
        animation: {
          $set: false,
        },
      });
    case ENABLE_ANIMATION:
      return update(state, {
        animation: {
          $set: true,
        },
      });
    case CLEAR_CHAT:
      return update(state, {
        data: {
          $set: null,
        },
        step: {
          $set: 0,
        },
        isLoading: {
          $set: false,
        },
        error: {
          $set: null,
        },
      });

    default:
      return state;
  }
};

export default ChatReducer;
