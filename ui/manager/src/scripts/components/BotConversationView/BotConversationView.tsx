import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { connect } from 'react-redux';
import styles from './BotConversionView.styles';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { IConversation, IConversationData } from '../utils/AxiosFunctions';
import { conversationSelector } from '../../selectors/ConversationSelectors';
import * as renderIf from 'render-if';
import ConversationSteps from './ConversationTab/ConversationSteps';
import ReactJson from 'react-json-view';
import Parser from '../utils/Parser';
import * as moment from 'moment';
import HomeButtonComponent from '../HomeButton/HomeButtonComponent';
import { historyPush } from '../../history';
import ConversationProperties from './ConversationTab/ConversationProperties';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import { CONVERSATION_READY } from '../utils/helpers/ConversationHelper';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import { ClipLoader } from 'react-spinners';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import { readOnlySelector } from '../../selectors/AuthenticationSelectors';

interface IPublicProps {
  conversationId: string;
}

interface IPrivateProps extends IPublicProps {
  conversation: IConversation;
  isLoading: boolean;
  readOnly: boolean;
}

enum TabEnum {
  'conversationSteps',
  'json',
}

interface IState {
  selectedTab: TabEnum;
}

class BotConversationView extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedTab: TabEnum.conversationSteps,
    };
  }

  componentDidMount() {
    if (this.props.conversation) {
      this.fetchConversation();
    } else {
      eddiApiActionDispatchers.fetchConversationsAction(
        1,
        0,
        this.props.conversationId,
        null,
      );
    }
  }

  componentDidUpdate(prevProps) {
    if (!prevProps.conversation && this.props.conversation) {
      this.fetchConversation(this.props);
    }
  }

  fetchConversation(props = this.props) {
    eddiApiActionDispatchers.fetchConversationAction(props.conversationId);
  }

  endConversation = () => {
    modalActionDispatchers.showConfirmationModal(
      `Are you sure you want to end this conversation?`,
      null,
      () =>
        eddiApiActionDispatchers.endConversationAction(
          this.props.conversationId,
        ),
    );
  };

  render() {
    const { conversation } = this.props;
    return (
      <div style={styles.content}>
        <HomeButtonComponent extraPath={'conversations'} />
        {renderIf(this.props.isLoading && !conversation)(() => (
          <div style={styles.loadingWrapper}>
            <ClipLoader color={BLUE_COLOR} />
          </div>
        ))}
        {renderIf(conversation)(() => (
          <div>
            <div style={styles.header}>
              <div style={styles.topHeader}>
                <div
                  style={styles.botName}
                  onClick={() =>
                    historyPush(
                      `/botview/${Parser.getId(conversation.botResource)}`,
                      [
                        `version=${Parser.getVersion(
                          conversation.botResource,
                        )}`,
                      ],
                    )
                  }>
                  {conversation.botName}
                </div>
                <div style={styles.botVersion}>{`V${Parser.getVersion(
                  conversation.botResource,
                )}`}</div>
                <WhiteButton
                  text={'End Conversation'}
                  customStyles={styles.endConversationButton}
                  disabled={
                    conversation.conversationState !== CONVERSATION_READY ||
                    this.props.readOnly
                  }
                  onClick={this.endConversation}
                />
              </div>
              <div style={styles.bottomHeader}>
                <div style={styles.descriptor}>
                  <div style={styles.title}>{'Environment'}</div>
                  <div style={styles.descriptorContent}>
                    {conversation.environment}
                  </div>
                </div>
                <div style={styles.descriptor}>
                  <div style={styles.title}>{'Conversation state'}</div>
                  <div style={styles.descriptorContent}>
                    {conversation.conversationState}
                  </div>
                </div>
                <div style={styles.descriptor}>
                  <div style={styles.title}>{'Last message'}</div>
                  <div style={styles.descriptorContent}>
                    {moment(conversation.lastModifiedOn).fromNow()}
                  </div>
                </div>
                <div style={styles.descriptor}>
                  <div style={styles.title}>{'Created on'}</div>
                  <div style={styles.descriptorContent}>
                    {moment(conversation.createdOn).format('DD.MM.YYYY')}
                  </div>
                </div>
                <div style={styles.descriptor}>
                  <div style={styles.title}>{'User id'}</div>
                  {renderIf(conversation.data)(() => (
                    <div style={styles.descriptorContent}>
                      {conversation.data.userId}
                    </div>
                  ))}
                </div>
              </div>
            </div>
            <div style={styles.tabs}>
              <div
                style={
                  this.state.selectedTab === TabEnum.conversationSteps
                    ? styles.tab
                    : { ...styles.tab, ...styles.tabDisabled }
                }
                onClick={() =>
                  this.setState({ selectedTab: TabEnum.conversationSteps })
                }>
                {'Conversation'}
              </div>
              <div
                style={
                  this.state.selectedTab === TabEnum.json
                    ? styles.tab
                    : { ...styles.tab, ...styles.tabDisabled }
                }
                onClick={() => this.setState({ selectedTab: TabEnum.json })}>
                {'Raw JSON'}
              </div>
            </div>
            {renderIf(!conversation.data)(() => (
              <div style={styles.loadingWrapper}>
                <ClipLoader color={BLUE_COLOR} />
              </div>
            ))}
            {renderIf(conversation.data)(() => (
              <div>
                {renderIf(this.state.selectedTab === TabEnum.conversationSteps)(
                  () => (
                    <div>
                      <ConversationProperties
                        conversationProperties={
                          conversation.data.conversationProperties
                        }
                      />
                      <ConversationSteps
                        isLoading={this.props.isLoading}
                        conversationId={this.props.conversationId}
                        conversationSteps={conversation.data.conversationSteps}
                        conversationOutputs={
                          conversation.data.conversationOutputs
                        }
                      />
                    </div>
                  ),
                )}
                {renderIf(this.state.selectedTab === TabEnum.json)(() => (
                  <ReactJson
                    style={styles.rjv}
                    src={conversation.data}
                    theme={'monokai'}
                    collapsed={2}
                    displayDataTypes={false}
                    enableClipboard={false}
                  />
                ))}
              </div>
            ))}
          </div>
        ))}
      </div>
    );
  }
}

const ComposedBotConversationView: Component<IPrivateProps> = compose<
  IPrivateProps
>(
  pure,
  connect(conversationSelector),
  connect(readOnlySelector),
  setDisplayName('BotConversationView'),
)(BotConversationView);

export default ComposedBotConversationView;
