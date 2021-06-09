import * as _ from 'lodash';
import * as React from 'react';
import * as InfiniteScrollTypes from 'react-infinite-scroller';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { conversationsSelector } from '../../selectors/ConversationSelectors';
import { IConversation } from '../utils/AxiosFunctions';
import Conversation from './Conversation';
import useStyle from './ConversationList.styles';

const InfiniteScroll =
  require('react-infinite-scroller') as InfiniteScrollTypes;

interface IPublicProps {
  filterText: string;
}

interface IPrivateProps extends IPublicProps {
  conversations: IConversation[];
  isLoading: boolean;
  allConversationsLoaded: boolean;
  error: Error;
  conversationsLoaded: number;
}

const ConversationList = ({
  filterText,
  conversations,
  isLoading,
  allConversationsLoaded,
  error,
  conversationsLoaded,
}: IPrivateProps) => {
  const [loading, setLoading] = React.useState(false);
  const classes = useStyle();

  React.useEffect(() => {
    loadMore();
  }, []);

  React.useEffect(() => {
    if (!isLoading) {
      setLoading(false);
    }
  }, [isLoading]);

  const filterConversations = () => {
    if (!_.isEmpty(filterText)) {
      return conversations.filter(
        (conversation) =>
          conversation.botName
            .toLowerCase()
            .includes(filterText.toLowerCase()) ||
          conversation.resource
            .toLowerCase()
            .includes(filterText.toLowerCase()) ||
          conversation.botResource
            .toLowerCase()
            .includes(filterText.toLowerCase()),
      );
    } else {
      return conversations;
    }
  };

  const loadMore = () => {
    if (loading) {
      return;
    }
    setLoading(true);
    const limit = 20;
    if (conversations.length < limit && !allConversationsLoaded) {
      eddiApiActionDispatchers.fetchConversationsAction(limit, 0, null, null);
    } else {
      eddiApiActionDispatchers.fetchConversationsAction(
        limit,
        Math.floor(conversationsLoaded / limit),
        null,
        null,
      );
    }
  };

  const conversationList = filterConversations();
  return (
    <div>
      <div className={classes.title}>
        <div>{'Bot name'}</div>
        <div className={classes.stepSize}>{'Step size'}</div>
        <div className={classes.environment}>{'Environment'}</div>
        <div className={classes.conversationState}>{'Conversation state'}</div>
        <div className={classes.lastModifiedOn}>{'Last message'}</div>
        <div className={classes.createdOn}>{'Created on'}</div>
      </div>
      {isLoading && _.isEmpty(conversations) && (
        <div className={classes.loadingWrapper}>
          <ClimbingBoxLoader loading />
        </div>
      )}
      {!!error && !isLoading && <p>{'Error: Could not load conversations'}</p>}
      {!isLoading && !error && _.isEmpty(conversations) && (
        <p>{`There are no conversations yet`}</p>
      )}
      {!error && !_.isEmpty(conversations) && (
        <div className={classes.packageList}>
          {_.isEmpty(conversationList) && (
            <p>{`Found no conversations matching: "${filterText}"`}</p>
          )}
          <InfiniteScroll
            pageStart={0}
            loadMore={loadMore}
            hasMore={!allConversationsLoaded && !isLoading}
            loader={
              <div className="loader" key={0}>
                Loading ...
              </div>
            }>
            {conversationList.map((conversation) => (
              <Conversation
                key={conversation.resource}
                conversation={conversation}
              />
            ))}
          </InfiniteScroll>
        </div>
      )}
    </div>
  );
};

const ComposedConversationList: React.ComponentClass<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(
  pure,
  connect(conversationsSelector),
  setDisplayName('ConversationList'),
)(ConversationList);

export default ComposedConversationList;
