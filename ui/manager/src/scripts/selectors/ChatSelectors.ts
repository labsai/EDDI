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

export const currentChatIdSelector: (state: IAppState) => string =
  createSelector(ChatStateSelector, (chatState) => {
    return chatState.data?.[0]?.conversationId;
  });

export const getBotId: (state: IAppState) => string = createSelector(
  ChatStateSelector,
  (chatState) => {
    return chatState.data?.[0]?.botId;
  },
);

export const getBotVersion: (state: IAppState) => string = createSelector(
  ChatStateSelector,
  (chatState) => {
    return chatState.data?.[0]?.botVersion;
  },
);

export const getBotEnvironment: (state: IAppState) => string = createSelector(
  ChatStateSelector,
  (chatState) => {
    return chatState.data?.[0]?.environment;
  },
);

export const getApiUrl: (state: IAppState) => string = createSelector(
  ChatStateSelector,
  (chatState) => {
    return chatState.data?.[0]?.apiUrl;
  },
);

export const getChatContext = (state: IAppState) => {
  return state.chatState.context;
};

export const getChatAnimation = (state: IAppState) => {
  return state.chatState.animation;
};

export const getUserInput: (state: IAppState) => string = createSelector(
  ChatStateSelector,
  (chatState) => {
    return chatState.data?.[chatState.data.length - 1]?.userReply;
  },
);
