import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { connect } from 'react-redux';
import styles from './BotConversionView.styles';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { IConversation } from '../utils/AxiosFunctions';
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
import { CONVERSATION_ENDED } from '../utils/helpers/ConversationHelper';

interface IPrivateProps {
  conversationId: string;
}

interface IPublicProps extends IPrivateProps {
  conversation: IConversation;
  isLoading: boolean;
}

enum TabEnum {
  'conversationSteps',
  'json',
}

interface IState {
  selectedTab: TabEnum;
}

class BotConversationView extends React.Component<IPublicProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedTab: TabEnum.conversationSteps,
    };
  }

  componentDidMount() {
    if (this.props.conversation) {
      this.fetchConversation();
    }
  }

  componentWillReceiveProps(nextProps) {
    if (!this.props.conversation && nextProps.conversation) {
      this.fetchConversation(nextProps);
    }
  }

  fetchConversation(props = this.props) {
    eddiApiActionDispatchers.fetchConversationAction(
      props.conversation.environment,
      Parser.getId(props.conversation.botResource),
      props.conversationId,
    );
  }

  endConversation = () => {
    eddiApiActionDispatchers.endConversationAction(this.props.conversationId);
  };

  render() {
    const { conversation } = this.props;
    return (
      <div style={styles.content}>
        <HomeButtonComponent extraPath={'conversations'} />
        <div style={styles.header}>
          <div style={styles.topHeader}>
            <div
              style={styles.botName}
              onClick={() =>
                historyPush(
                  `/botview/${Parser.getId(conversation.botResource)}`,
                  [`version=${Parser.getVersion(conversation.botResource)}`],
                )
              }>
              {conversation.botName}
            </div>
            <div style={styles.botVersion}>{`V${Parser.getVersion(
              conversation.botResource,
            )}`}</div>
            {renderIf(conversation.conversationState === CONVERSATION_ENDED)(
              () => (
                <WhiteButton
                  text={'End Conversation'}
                  customStyles={styles.endConversationButton}
                  onClick={this.endConversation}
                />
              ),
            )}
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
                    conversationSteps={conversation.data.conversationSteps}
                    conversationOutputs={conversation.data.conversationOutputs}
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
    );
  }
}

const ComposedBotConversationView: Component<IProps> = compose<IProps>(
  pure,
  connect(conversationSelector),
  setDisplayName('BotConversationView'),
)(BotConversationView);

export default ComposedBotConversationView;
