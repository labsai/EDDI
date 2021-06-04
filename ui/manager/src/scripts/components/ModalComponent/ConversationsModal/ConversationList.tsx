import * as _ from 'lodash';
import Radium from 'radium';
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
import styles from './ConversationList.styles';

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
    if (prevProps.botResource !== this.props.botResource) {
      eddiApiActionDispatchers.fetchConversationsAction(
        DEFAULT_LIMIT,
        0,
        null,
        this.props.botResource,
      );
      this.setState({ loading: true });
    }
  }

  loadMore = () => {
    const fetchIndex = Math.floor(
      this.props.conversationsLoaded / DEFAULT_LIMIT,
    );
    if (this.state.loading || _.isEmpty(this.props.conversations)) {
      return;
    }
    this.setState({ loading: true });
    eddiApiActionDispatchers.fetchConversationsAction(
      DEFAULT_LIMIT,
      fetchIndex,
      null,
      this.props.botResource,
    );
  };

  render() {
    return (
      <div style={styles.conversationList}>
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
          <div>
            {this.props.conversations.map((conversation) => (
              <Conversation
                key={conversation.resource}
                conversation={conversation}
              />
            ))}
          </div>
        )}
        {!this.props.allConversationsLoaded &&
          !this.props.isLoading &&
          !this.state.loading && (
            <BlueButton
              customStyles={styles.loadMoreButton}
              onClick={this.loadMore}
              text={'Load More'}
            />
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
  connect(botConversationSelector),
  Radium,
  setDisplayName('ConversationList'),
)(ConversationList);

export default ComposedConversationList;
