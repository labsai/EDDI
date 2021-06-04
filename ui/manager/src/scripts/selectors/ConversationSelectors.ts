import { createSelector } from 'reselect';
import { IAppState } from '../reducers';
import { IConversation } from '../components/utils/AxiosFunctions';
import * as _ from 'lodash';
import Parser from '../components/utils/Parser';
import { IConversationState } from '../reducers/ConversationReducer';

export const ConversationStateSelector: (
  state: IAppState,
) => IConversationState = (state) => state.conversationState;

export const conversationsSelector: (state: IAppState) => {
  conversations: IConversation[];
  isLoading: boolean;
  allConversationsLoaded: boolean;
  error: Error;
} = createSelector(
  ConversationStateSelector,
  function (conversationState: IConversationState): {
    conversations: IConversation[];
    isLoading: boolean;
    allConversationsLoaded: boolean;
    error: Error;
    conversationsLoaded: number;
  } {
    return {
      conversations: conversationState.conversations,
      isLoading: conversationState.isLoadingAllConversations,
      allConversationsLoaded: conversationState.allConversationsLoaded,
      error: conversationState.error,
      conversationsLoaded: conversationState.conversationsLoaded,
    };
  },
);

export interface IConversationSelectorProps {
  conversationId: string;
}

export function conversationSelector(
  state: IAppState,
  props: IConversationSelectorProps,
) {
  const conversation = state.conversationState.conversations.find((conv) =>
    conv.resource.includes(props.conversationId),
  );
  return {
    conversation,
    isLoading:
      state.conversationState.isLoadingAllConversations ||
      state.conversationState.isLoadingConversation,
  };
}

export interface IBotConversationsSelectorProps {
  botResource: string;
}

export function botConversationSelector(
  state: IAppState,
  props: IBotConversationsSelectorProps,
) {
  const conversations = state.conversationState.conversations.filter(
    (conversation) => conversation.botResource === props.botResource,
  );
  const sortedConversations = conversations.sort(function (a, b) {
    return b.createdOn - a.createdOn;
  });
  return {
    conversations: sortedConversations.slice(
      0,
      state.conversationState.conversationsLoaded,
    ),
    isLoading: state.conversationState.isLoadingAllConversations,
    allConversationsLoaded: state.conversationState.allConversationsLoaded,
    error: state.conversationState.error,
    conversationsLoaded: state.conversationState.conversationsLoaded,
  };
}
