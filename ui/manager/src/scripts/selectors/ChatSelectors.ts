import * as _ from 'lodash';
import { createSelector } from 'reselect';
import { IAppState } from '../reducers';
import { IChatState } from '../reducers/ChatReducer';

interface IConversationStep {
  key: string;
  value: any;
  timestampt: Date;
  originPackageId: string;
}

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

const filterRules = {
  parser: ['input', 'expressions'],
  behavior: ['behavior_rules', 'actions'],
  output: ['output', 'template', 'quickReplies'],
  property: ['properties'],
  httpcalls: ['httpCalls'],
  templating: ['preTemplated', 'postTemplated'],
};

const getFilteredConversationsSteps = (
  allConversationSteps: IConversationStep[],
  type: string,
) => {
  const filtered = allConversationSteps.filter((c: IConversationStep) => {
    return filterRules[type]?.some((e: string) => {
      return (
        c.key.includes(e) &&
        !c.key.includes(filterRules.templating[0]) &&
        !c.key.includes(filterRules.templating[1])
      );
    });
  });
  return filtered;
};

export const getAllConversationSteps = (
  state: IAppState,
  type: string = null,
) => {
  const currentStep = state.chatState.step;
  const allConversationSteps: IConversationStep[] = [];
  const data = state.chatState.data?.[currentStep];
  const conversationStep = data?.conversationSteps?.map((c) => {
    return c.conversationStep;
  })?.[0];

  if (!_.isEmpty(conversationStep)) {
    allConversationSteps.push.apply(allConversationSteps, conversationStep);
  }
  if (type) {
    return getFilteredConversationsSteps(allConversationSteps, type);
  }
  return allConversationSteps;
};
