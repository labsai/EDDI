import { Action } from 'redux';
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
  context?: any;
}

export function startChatAction(
  botId: string,
  context?: any,
): IStartChatAction {
  return {
    botId,
    context,
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

export interface ISetChatContextAction extends Action {
  context: string;
}

export function setChatContext(context: string): ISetChatContextAction {
  return {
    context,
    type: SET_CHAT_CONTEXT,
  };
}

export function setChatAnimation(enabled: boolean) {
  return enabled
    ? {
        type: ENABLE_ANIMATION,
      }
    : {
        type: DISABLE_ANIMATION,
      };
}

export interface IRestartChatAction extends Action {
  botId: string;
  conversationId?: string;
}

export function restartChatAction(
  botId: string,
  conversationId?: string,
): IRestartChatAction {
  return {
    botId,
    conversationId,
    type: RESTART_CHAT,
  };
}

export interface IRestartChatSuccessAction extends Action {
  data: any;
}

export function restartChatSuccessAction(data: any): IRestartChatSuccessAction {
  return {
    data,
    type: RESTART_CHAT_SUCCESS,
  };
}

export interface IRestartChatFailedAction extends Action {
  error: string;
}

export function restartChatFailedAction(
  error: string,
): IRestartChatFailedAction {
  return {
    error,
    type: RESTART_CHAT_FAILED,
  };
}

export function clearChatAction() {
  return {
    type: CLEAR_CHAT,
  };
}
