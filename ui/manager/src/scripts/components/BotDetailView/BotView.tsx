import * as React from 'react';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import BotDescriptor from './BotDescriptor';
import PackageList from './PackageList';
import * as renderIf from 'render-if';
import styles from './BotView.styles';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IBot } from '../utils/AxiosFunctions';
import { botSelector } from '../../selectors/BotSelectors';
import { ModalEnum } from '../utils/ModalEnum';
import { connect } from 'react-redux';
import VersionSelectComponent from '../Assets/VersionSelectComponent';
import WhiteButton from '../Assets/Buttons/WhiteButton';

interface IPublicProps {
  botResource: string;
  selectVersion(resource: string, newVersion): void;
}

interface IPrivateProps extends IPublicProps {
  bot: IBot;
  error: Error;
  isLoading: boolean;
}

interface IState {}

const warningIcon = require('../../../public/images/WarningIcon.png');
const foundUnpublishedChanges = false; // todo : add function to check if there are unpublished changes.
class BotView extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
  }

  openEditBotModal = () => {
    ModalActionDispatchers.showEditBotModal(this.props.bot);
  };

  openEditJsonModal = () => {
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
    this.props.selectVersion(this.props.bot.resource, newVersion);
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
                text={'Edit Bot'}
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
              <WhiteButton
                text={'Publish'}
                disabled={true}
                customStyles={styles.button}
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
  connect(botSelector),
  setDisplayName('BotView'),
)(BotView);

export default ComposedBotView;
