import { Action } from 'redux';
import {
  CLOSE_SIDE_CHAT,
  OPEN_SIDE_CHAT,
  REPLY_IN_CHAT,
  REPLY_IN_CHAT_FAILED,
  REPLY_IN_CHAT_SUCCESS,
  START_CHAT,
  START_CHAT_FAILED,
  START_CHAT_SUCCESS,
} from './ChatActionsTypes';

export const openChatAction = () => {
  return {
    type: OPEN_SIDE_CHAT,
  };
};

export const closeChatAction = () => {
  return {
    type: CLOSE_SIDE_CHAT,
  };
};

export interface IStartChatAction extends Action {
  botId: string;
}

export function startChatAction(botId: string): IStartChatAction {
  return {
    botId,
    type: START_CHAT,
  };
}

export interface IStartChatSuccessAction extends Action {
  data: any;
}

export function startChatSuccessAction(data: any): IStartChatSuccessAction {
  return {
    data,
    type: START_CHAT_SUCCESS,
  };
}

export interface IStartChatFailedAction extends Action {
  error: string;
}

export function startChatFailedAction(error: string): IStartChatFailedAction {
  return {
    error,
    type: START_CHAT_FAILED,
  };
}

export interface IReplyInChatAction extends Action {
  botId: string;
  conversationId: string;
  input: string;
  context?: any;
}

export function replyInChatAction(
  botId: string,
  conversationId: string,
  input: string,
  context?: any,
): IReplyInChatAction {
  return {
    botId,
    conversationId,
    input,
    context,
    type: REPLY_IN_CHAT,
  };
}

export interface IReplyInChatSuccesAction extends Action {
  data: any;
}

export function replyInChatSuccessAction(data: any): IReplyInChatSuccesAction {
  return {
    data,
    type: REPLY_IN_CHAT_SUCCESS,
  };
}

export interface IReplyInChatFailedAction extends Action {
  error: string;
}

export function replyInChatFailedAction(
  error: string,
): IReplyInChatFailedAction {
  return {
    error,
    type: REPLY_IN_CHAT_FAILED,
  };
}
