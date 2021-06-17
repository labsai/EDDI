import { createSelector } from 'reselect';
import { IAppState } from '../reducers';
import { IChatState } from '../reducers/ChatReducer';

export const ChatStateSelector: (state: IAppState) => IChatState = (state) =>
  state.chatState;

export const isChatOpenedSelector = (state: IAppState) => {
  return {
    isOpened: state.chatState.isOpened,
  };
};

export const chatDataSelector: (state: IAppState) => {
  data: any;
  isLoading: boolean;
  error: string;
  step: number;
} = createSelector(ChatStateSelector, (chatState) => {
  return {
    data: chatState.data,
    isLoading: chatState.isLoading,
    error: chatState.error,
    step: chatState.step,
  };
});
