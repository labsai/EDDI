import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import { historyPush } from '../../history';
import { readOnlySelector } from '../../selectors/AuthenticationSelectors';
import Options from '../Assets/Buttons/BotOptions';
import DeployButton from '../Assets/Buttons/DeployButton';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import VersionSelectComponent from '../Assets/VersionSelectComponent';
import { getAPIUrl } from '../utils/ApiFunctions';
import { IBot } from '../utils/AxiosFunctions';
import { BOT } from '../utils/EddiTypes';
import { READY } from '../utils/helpers/BotHelper';
import Parser from '../utils/Parser';
import BotDescriptor from './BotDescriptor';
import styles from './BotView.styles';
import PackageList from './PackageList';

interface IPublicProps {
  bot: IBot;
}

interface IPrivateProps extends IPublicProps {
  readOnly: boolean;
}

interface IState {
  apiUrl: string;
}

const warningIcon = require('../../../public/images/WarningIcon.png');
const foundUnpublishedChanges = false; // todo : add function to check if there are unpublished changes.

class BotView extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      apiUrl: '',
    };
  }

  async componentDidMount() {
    this.setState({ apiUrl: await getAPIUrl() });
  }

  openEditBotModal = () => {
    modalActionDispatchers.showEditDescriptorModalAction(this.props.bot);
  };

  openEditJsonModal = () => {
    eddiApiActionDispatchers.fetchJsonSchemaAction(BOT);
    modalActionDispatchers.showEditJsonModal(
      this.props.bot.resource,
      JSON.stringify(
        {
          packages: this.props.bot.packages,
          channels: this.props.bot.channels,
        },
        null,
        '\t',
      ),
    );
  };

  openViewConversationsModal = () => {
    eddiApiActionDispatchers.fetchConversationsAction(
      20,
      0,
      null,
      this.props.bot.resource,
    );
    modalActionDispatchers.showConversationsModal(this.props.bot);
  };

  selectVersion = (newVersion: number) => {
    eddiApiActionDispatchers.fetchBotAction(
      Parser.replaceResourceVersion(this.props.bot.resource, newVersion),
    );
    historyPush(`${this.props.bot.id}`, [`version=${newVersion}`]);
  };

  render() {
    const isCurrentVersion =
      this.props.bot.version !== this.props.bot.currentVersion;
    return (
      <div>
        {!!this.props.bot && (
          <div>
            <div style={styles.botHeader}>
              <div style={styles.botName}>
                {this.props.bot.name || this.props.bot.id}
              </div>
              <VersionSelectComponent
                selectedVersion={this.props.bot.version}
                currentVersion={this.props.bot.currentVersion}
                selectVersion={this.selectVersion}
              />
              <WhiteButton
                text={'Rename'}
                onClick={this.openEditBotModal}
                customStyles={styles.button}
                disabled={isCurrentVersion || this.props.readOnly}
              />
              <WhiteButton
                text={'Edit JSON'}
                onClick={this.openEditJsonModal}
                disabled={isCurrentVersion || this.props.readOnly}
                customStyles={styles.button}
              />
              {foundUnpublishedChanges && (
                <div style={styles.unpublishedChanges}>
                  <img src={warningIcon} style={styles.warningIcon} />
                  <div style={styles.unpublishedChangesText}>
                    {'This Bot has unpublished changes'}
                  </div>
                </div>
              )}
              <div style={styles.botHeaderSpacing} />
              <div style={styles.options}>
                <Options bot={this.props.bot} apiUrl={this.state.apiUrl} />
              </div>
              <WhiteButton
                text={'Open Chat'}
                customStyles={styles.chatButton}
                disabled={this.props.bot.deploymentStatus !== READY}
                onClick={() =>
                  window
                    .open(
                      `${this.state.apiUrl}/chat/unrestricted/${this.props.bot.id}`,
                      '_blank',
                    )
                    .focus()
                }
              />
              <DeployButton
                botName={this.props.bot.name}
                botResource={this.props.bot.resource}
                deploymentStatus={this.props.bot.deploymentStatus}
                customStyles={styles.deployButton}
                readOnly={this.props.readOnly}
              />
            </div>
            <BotDescriptor
              botCreated={this.props.bot.createdOn}
              botLastModified={this.props.bot.lastModifiedOn}
              botDescription={this.props.bot.description}
            />
            <PackageList bot={this.props.bot} readOnly={this.props.readOnly} />
          </div>
        )}
      </div>
    );
  }
}

const ComposedBotView: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(readOnlySelector),
  setDisplayName('BotView'),
)(BotView);

export default ComposedBotView;
