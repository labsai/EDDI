import * as React from 'react';
import '../ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import * as renderIf from 'render-if';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import styles from '../ViewJsonModal/ViewJsonModal.styles';
import * as moment from 'moment';
import * as _ from 'lodash';
import { IBot } from '../../utils/AxiosFunctions';
import ModalActionDispatchers, {
  default as modalActionDispatchers,
} from '../../../actions/ModalActionDispatchers';
import BlueButton from '../../Assets/Buttons/BlueButton';
import VersionSelectComponent from '../../Assets/VersionSelectComponent';
import Parser from '../../utils/Parser';
import Options from '../../Assets/Buttons/Options';
import Radium from 'radium';
import { historyPush } from '../../../history';
import ConversationList from './ConversationList';

interface IProps {
  bot: IBot;
}

interface IState {
  selectedResource: string;
}

class ConversationsModal extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedResource: this.props.bot.resource,
    };
  }

  selectVersion = (version: number) => {
    this.setState({
      selectedResource: Parser.replaceResourceVersion(
        this.props.bot.resource,
        version,
      ),
    });
  };

  render() {
    const { bot } = this.props;
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
        <ConversationList botResource={this.state.selectedResource} />
      </div>
    );
  }
}

const ComposedConversationsModal: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('ConversationsModal'),
)(ConversationsModal);

export default ComposedConversationsModal;
