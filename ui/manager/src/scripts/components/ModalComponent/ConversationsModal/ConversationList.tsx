import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { botConversationSelector } from '../../../selectors/ConversationSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import { DEFAULT_LIMIT } from '../../utils/ApiFunctions';
import { IConversation } from '../../utils/AxiosFunctions';
import Conversation from './Conversation';
import useStyles from './ConversationList.styles';

interface IPublicProps {
  botResource: string;
}

interface IPrivateProps extends IPublicProps {
  conversations: IConversation[];
  isLoading: boolean;
  allConversationsLoaded: boolean;
  error: Error;
  conversationsLoaded: number;
}

const ConversationList = ({
  botResource,
  conversations,
  isLoading,
  allConversationsLoaded,
  error,
  conversationsLoaded,
}: IPrivateProps) => {
  const [loading, setLoading] = React.useState(false);

  const classes = useStyles();

  React.useEffect(() => {
    loadMore();
  }, []);

  React.useEffect(() => {
    if (!isLoading) {
      setLoading(false);
    }
  }, [isLoading]);

  React.useEffect(() => {
    if (botResource) {
      eddiApiActionDispatchers.fetchConversationsAction(
        DEFAULT_LIMIT,
        0,
        null,
        botResource,
      );
      setLoading(true);
    }
  }, [botResource]);

  const loadMore = () => {
    const fetchIndex = Math.floor(conversationsLoaded / DEFAULT_LIMIT);
    if (loading || _.isEmpty(conversations)) {
      return;
    }
    setLoading(true);
    eddiApiActionDispatchers.fetchConversationsAction(
      DEFAULT_LIMIT,
      fetchIndex,
      null,
      botResource,
    );
  };

  return (
    <div className={classes.conversationList}>
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
      {!isLoading && !!error && <p>{'Error: Could not load conversations'}</p>}
      {!isLoading && !error && _.isEmpty(conversations) && (
        <p>{`There are no conversations yet`}</p>
      )}
      {!error && !_.isEmpty(conversations) && (
        <div>
          {conversations.map((conversation) => (
            <Conversation
              key={conversation.resource}
              conversation={conversation}
            />
          ))}
        </div>
      )}
      {!allConversationsLoaded && !isLoading && !loading && (
        <BlueButton
          classes={{ button: classes.loadMoreButton }}
          onClick={loadMore}
          text={'Load More'}
        />
      )}
    </div>
  );
};

const ComposedConversationList: React.ComponentClass<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(
  pure,
  connect(botConversationSelector),
  setDisplayName('ConversationList'),
)(ConversationList);

export default ComposedConversationList;
