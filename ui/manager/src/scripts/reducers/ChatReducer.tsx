import update from 'immutability-helper';
import { Action, Reducer } from 'redux';
import {
  IStartChatFailedAction,
  IStartChatSuccessAction,
  IReplyInChatSuccesAction,
  IReplyInChatFailedAction,
} from '../actions/ChatActions';
import {
  CLOSE_SIDE_CHAT,
  OPEN_SIDE_CHAT,
  REPLY_IN_CHAT,
  REPLY_IN_CHAT_FAILED,
  REPLY_IN_CHAT_SUCCESS,
  START_CHAT,
  START_CHAT_FAILED,
  START_CHAT_SUCCESS,
} from '../actions/ChatActionsTypes';
import * as _ from 'lodash';

export interface IChatState {
  isOpened: boolean;
  isLoading: boolean;
  error: string;
  data: any;
  step: number;
}

export const initialState: IChatState = {
  isOpened: false,
  isLoading: false,
  error: null,
  data: null,
  step: 0,
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
      return update(state, {
        isLoading: {
          $set: true,
        },
        error: {
          $set: null,
        },
      });
    case START_CHAT_SUCCESS:
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
    case START_CHAT_FAILED:
      return update(state, {
        isLoading: {
          $set: false,
        },
        error: {
          $set: (action as IStartChatFailedAction).error,
        },
      });
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
            return step++;
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

    default:
      return state;
  }
};

export default ChatReducer;
