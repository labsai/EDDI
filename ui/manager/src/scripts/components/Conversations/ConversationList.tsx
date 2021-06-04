import * as _ from 'lodash';
import Radium from 'radium';
import * as React from 'react';
import * as InfiniteScrollTypes from 'react-infinite-scroller';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { conversationsSelector } from '../../selectors/ConversationSelectors';
import { IConversation } from '../utils/AxiosFunctions';
import Conversation from './Conversation';
import styles from './ConversationList.styles';
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

interface IState {
  loading: boolean;
}

class ConversationList extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      loading: false,
    };
  }

  async componentDidMount() {
    this.loadMore();
  }

  componentDidUpdate(prevProps) {
    if (prevProps.isLoading && !this.props.isLoading) {
      this.setState({ loading: false });
    }
  }

  filterConversations() {
    if (!_.isEmpty(this.props.filterText)) {
      return this.props.conversations.filter(
        (conversation) =>
          conversation.botName
            .toLowerCase()
            .includes(this.props.filterText.toLowerCase()) ||
          conversation.resource
            .toLowerCase()
            .includes(this.props.filterText.toLowerCase()) ||
          conversation.botResource
            .toLowerCase()
            .includes(this.props.filterText.toLowerCase()),
      );
    } else {
      return this.props.conversations;
    }
  }

  loadMore = () => {
    if (this.state.loading) {
      return;
    }
    this.setState({ loading: true });
    const limit = 20;
    if (
      this.props.conversations.length < limit &&
      !this.props.allConversationsLoaded
    ) {
      eddiApiActionDispatchers.fetchConversationsAction(limit, 0, null, null);
    } else {
      eddiApiActionDispatchers.fetchConversationsAction(
        limit,
        Math.floor(this.props.conversationsLoaded / limit),
        null,
        null,
      );
    }
  };

  render() {
    const conversationList = this.filterConversations();
    return (
      <div>
        <div style={styles.title}>
          <div>{'Bot name'}</div>
          <div style={styles.stepSize}>{'Step size'}</div>
          <div style={styles.environment}>{'Environment'}</div>
          <div style={styles.conversationState}>{'Conversation state'}</div>
          <div style={styles.lastModifiedOn}>{'Last message'}</div>
          <div style={styles.createdOn}>{'Created on'}</div>
        </div>
        {this.props.isLoading && _.isEmpty(this.props.conversations) && (
          <div style={styles.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        )}
        {!!this.props.error && <p>{'Error: Could not load conversations'}</p>}
        {!this.props.isLoading &&
          !this.props.error &&
          _.isEmpty(this.props.conversations) && (
            <p>{`There are no conversations yet`}</p>
          )}
        {!this.props.error && !_.isEmpty(this.props.conversations) && (
          <div style={styles.packageList}>
            {_.isEmpty(conversationList) && (
              <p>{`Found no conversations matching: "${this.props.filterText}"`}</p>
            )}
            <InfiniteScroll
              pageStart={0}
              loadMore={this.loadMore}
              hasMore={
                !this.props.allConversationsLoaded && !this.props.isLoading
              }
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
  }
}

const ComposedConversationList: React.ComponentClass<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(
  pure,
  connect(conversationsSelector),
  Radium,
  setDisplayName('ConversationList'),
)(ConversationList);

export default ComposedConversationList;
