import * as React from 'react';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import BotDescriptor from './BotDescriptor';
import PackageList from './PackageList';
import * as renderIf from 'render-if';
import styles from './BotView.styles';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IBot } from '../utils/AxiosFunctions';
import VersionSelectComponent from '../Assets/VersionSelectComponent';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import DeployButton from '../Assets/Buttons/DeployButton';
import { READY } from '../utils/helpers/BotHelper';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import Parser from '../utils/Parser';
import { historyPush } from '../../history';
import { getAPIUrl } from '../utils/ApiFunctions';
import { BOT } from '../utils/EddiTypes';
import Options from '../Assets/Buttons/BotOptions';

interface IProps {
  bot: IBot;
}

interface IState {
  apiUrl: string;
}

const warningIcon = require('../../../public/images/WarningIcon.png');
const foundUnpublishedChanges = false; // todo : add function to check if there are unpublished changes.
class BotView extends React.Component<IProps, IState> {
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
    ModalActionDispatchers.showEditBotModal(this.props.bot);
  };

  openEditJsonModal = () => {
    eddiApiActionDispatchers.fetchJsonSchemaAction(BOT);
    ModalActionDispatchers.showEditJsonModal(
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

  selectVersion = (newVersion: number) => {
    eddiApiActionDispatchers.fetchBotAction(
      Parser.replaceResourceVersion(this.props.bot.resource, newVersion),
    );
    historyPush(`${this.props.bot.id}`, [`version=${newVersion}`]);
  };

  render() {
    return (
      <div>
        {renderIf(this.props.bot)(() => (
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
                disabled={
                  this.props.bot.version !== this.props.bot.currentVersion
                }
              />
              <WhiteButton
                text={'Edit JSON'}
                onClick={this.openEditJsonModal}
                disabled={
                  this.props.bot.version !== this.props.bot.currentVersion
                }
                customStyles={styles.button}
              />
              {renderIf(foundUnpublishedChanges)(() => (
                <div style={styles.unpublishedChanges}>
                  <img src={warningIcon} style={styles.warningIcon} />
                  <div style={styles.unpublishedChangesText}>
                    {'This Bot has unpublished changes'}
                  </div>
                </div>
              ))}
              <div style={styles.botHeaderSpacing} />
              <div style={styles.options}>
                <Options bot={this.props.bot} apiUrl={this.state.apiUrl} />
              </div>
              <DeployButton
                name={this.props.bot.name}
                botResource={this.props.bot.resource}
                deploymentStatus={this.props.bot.deploymentStatus}
                customStyles={styles.deployButton}
              />
            </div>
            <BotDescriptor
              botCreated={this.props.bot.createdOn}
              botLastModified={this.props.bot.lastModifiedOn}
              botDescription={this.props.bot.description}
            />
            <PackageList
              packages={this.props.bot.packages}
              bot={this.props.bot}
            />
          </div>
        ))}
      </div>
    );
  }
}

const ComposedBotView: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('BotView'),
)(BotView);

export default ComposedBotView;
