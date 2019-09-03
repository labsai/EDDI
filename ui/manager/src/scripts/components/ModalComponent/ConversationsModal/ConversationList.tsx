import * as React from 'react';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import * as Radium from 'radium';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { connect } from 'react-redux';
import styles from './ConversationList.styles';
import { ClimbingBoxLoader } from 'react-spinners';
import Conversation from './Conversation';
import { botConversationSelector } from '../../../selectors/ConversationSelectors';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { IConversation } from '../../utils/AxiosFunctions';
import { DEFAULT_LIMIT } from '../../utils/ApiFunctions';
import BlueButton from '../../Assets/Buttons/BlueButton';

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

  componentWillReceiveProps(nextProps) {
    if (this.props.isLoading && !nextProps.isLoading) {
      this.setState({ loading: false });
    }
    if (this.props.botResource !== nextProps.botResource) {
      console.log('fetching triggered!');
      eddiApiActionDispatchers.fetchConversationsAction(
        DEFAULT_LIMIT,
        0,
        nextProps.botResource,
      );
      this.setState({ loading: true });
    }
  }

  loadMore = () => {
    const fetchIndex = Math.floor(
      this.props.conversationsLoaded / DEFAULT_LIMIT,
    );
    console.log('Fetchindex: ' + fetchIndex);
    if (this.state.loading || _.isEmpty(this.props.conversations)) {
      return;
    }
    this.setState({ loading: true });
    eddiApiActionDispatchers.fetchConversationsAction(
      DEFAULT_LIMIT,
      fetchIndex,
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
        {renderIf(this.props.isLoading && _.isEmpty(this.props.conversations))(
          () => (
            <div style={styles.loadingWrapper}>
              <ClimbingBoxLoader loading />
            </div>
          ),
        )}
        {renderIf(this.props.error)(() => (
          <p>{'Error: Could not load conversations'}</p>
        ))}
        {renderIf(
          !this.props.isLoading &&
            !this.props.error &&
            _.isEmpty(this.props.conversations),
        )(() => <p>{`There are no conversations yet`}</p>)}
        {renderIf(!this.props.error && !_.isEmpty(this.props.conversations))(
          () => (
            <div>
              {this.props.conversations.map(conversation => (
                <Conversation
                  key={conversation.resource}
                  conversation={conversation}
                />
              ))}
            </div>
          ),
        )}
        {renderIf(
          !this.props.allConversationsLoaded &&
            !this.props.isLoading &&
            !this.state.loading,
        )(() => (
          <BlueButton
            customStyles={styles.loadMoreButton}
            onClick={this.loadMore}
            text={'Load More'}
          />
        ))}
      </div>
    );
  }
}

const ComposedConversationList: Component<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(
  pure,
  Radium,
  connect(botConversationSelector),
  setDisplayName('ConversationList'),
)(ConversationList);

export default ComposedConversationList;
