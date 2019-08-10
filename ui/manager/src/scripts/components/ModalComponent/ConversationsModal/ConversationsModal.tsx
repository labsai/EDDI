import * as React from 'react';
import '../ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import * as renderIf from 'render-if';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import styles from '../ViewJsonModal/ViewJsonModal.styles';
import * as moment from 'moment';
import * as _ from 'lodash';
import { IBot } from '../../utils/AxiosFunctions';
import ModalActionDispatchers, {
  default as modalActionDispatchers,
} from '../../../actions/ModalActionDispatchers';
import { IDetailedDescriptor } from '../../utils/AxiosFunctions';
import BlueButton from '../../Assets/Buttons/BlueButton';
import VersionSelectComponent from '../../Assets/VersionSelectComponent';
import Parser from '../../utils/Parser';
import Options from '../../Assets/Buttons/Options';
import EnvironmentSelectComponent from './EnvironmentSelectComponent';
import { connect } from 'react-redux';
import * as Radium from 'radium';
import { specificBotSelector } from '../../../selectors/BotSelectors';
import { historyPush } from '../../../history';

interface IPrivateProps extends IPublicProps {
  botId: string;
  botVersion: string;
}

interface IPublicProps {
  bot: IBot;
  error: Error;
  isLoading: boolean;
}

interface IState {
  selectedResource: string;
  selectedEnvironment: string;
}

class ConversationsModal extends React.Component<IPrivateProps> {
  constructor(props) {
    super(props);
    this.state = {
      selectedResource: this.props.bot,
      selectedEnvironment: 'unrestricted',
    };
  }

  onComponentDidMount() {
    this.setState({ selectedResource: this.props.bot });
  }

  selectVersion(version: number) {
    Parser.replaceResourceVersion(this.props.bot.resource, version);
  }

  selectConversation = (resource: string) => {
    historyPush(`/botconversationview/${Parser.getId(resource)}`);
    modalActionDispatchers.closeModal();
  };

  render() {
    const bot = this.props.bot;
    console.log(this.props.bot.conversations);
    return (
      <div>
        <div style={styles.header}>
          <div style={styles.topHeader}>
            <div style={styles.title}>{bot.name}</div>
            <VersionSelectComponent
              selectedVersion={bot.version}
              currentVersion={bot.currentVersion}
              selectVersion={this.selectVersion}
            />
            <div style={styles.centerFlex} />
            <div style={styles.options}>
              <Options descriptor={bot} data={bot.packages} />
            </div>
          </div>
          <div style={styles.bottomHeader}>
            <div style={styles.descriptionContainer}>
              <div style={styles.smallTitle}>{'Description'}</div>
              <div style={styles.smallText}>{bot.description}</div>
            </div>
            <div style={styles.dateContainer}>
              <div style={styles.smallTitle}>{'Created'}</div>
              <div style={styles.smallText}>
                {moment(bot.createdOn).format('DD.MM.YYYY')}
              </div>
            </div>
            <div style={styles.dateContainer}>
              <div style={styles.smallTitle}>{'Last modified'}</div>
              <div style={styles.smallText}>
                {moment(bot.lastModifiedOn).format('DD.MM.YYYY')}
              </div>
            </div>
          </div>
        </div>
        <div style={styles.data}>
          {renderIf(!_.isEmpty(bot.conversations))(() => (
            <div>
              {bot.conversations.map(conversation => (
                <div
                  style={styles.conversation}
                  key={conversation.resource}
                  onClick={() =>
                    this.selectConversation(conversation.resource)
                  }>
                  <div>{`ID: ${Parser.getId(conversation.resource)} `}</div>
                  <div>{`Environment: ${conversation.environment} `}</div>
                  <div>{`ConversationSteps: ${
                    conversation.conversationStepSize
                  } `}</div>
                  <div
                    style={
                      styles.conversationRight
                    }>{`Last Message received: ${moment(
                    conversation.lastModifiedOn,
                  ).fromNow()}`}</div>
                </div>
              ))}
            </div>
          ))}
        </div>
      </div>
    );
  }
}

const ComposedConversationsModal: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  connect(specificBotSelector),
  setDisplayName('ConversationsModal'),
)(ConversationsModal);

export default ComposedConversationsModal;
