import * as React from 'react';
import * as renderIf from 'render-if';
import styles from './Bot.styles';
import * as Radium from 'radium';
import { Link, browserHistory } from 'react-router-dom';
import {
  IBot,
  IPackage,
  getDeploymentStatus,
  DeploymentStatus,
} from '../utils/AxiosFunctions';
import Packages from './Packages';
import { Component, compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import * as _ from 'lodash';
import * as moment from 'moment';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import {
  READY,
  ERROR,
  IN_PROGRESS,
  NOT_FOUND,
} from '../utils/helpers/BotHelper';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';

interface IPublicProps {
  bot: IBot;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  isLoading: boolean;
}

const warningIcon = require('../../../public/images/WarningIcon.png');

class Bot extends React.Component<IPrivateProps> {
  constructor(props) {
    super(props);
    this.state = {};
  }

  async componentDidMount() {
    eddiApiActionDispatchers.fetchBotDataAction(this.props.bot.resource);
    getDeploymentStatus(this.props.bot.resource);
    console.log(await getDeploymentStatus(this.props.bot.resource));
  }

  getDeployButton() {
    let buttonText;
    let style = styles.publishButton;
    let disabled = false;
    switch (this.props.bot.deploymentStatus) {
      case READY:
        buttonText = 'Undeploy';
        style = { ...styles.publishButton, ...styles.undeployButton };
        return (
          <button
            disabled={disabled}
            style={style}
            onClick={() =>
              modalActionDispatchers.showConfirmationModal(
                `Are you sure you want to undeploy ${this.props.bot.name}?`,
                () =>
                  eddiApiActionDispatchers.undeployBotAction(
                    this.props.bot.resource,
                  ),
              )
            }>
            {buttonText}
          </button>
        );
      case ERROR:
        buttonText = 'ERROR';
        style = { ...styles.publishButton, ...styles.errorButton };
        disabled = true;
        break;
      case IN_PROGRESS:
        buttonText = 'In Progress';
        style = { ...styles.publishButton, ...styles.inProgressButton };
        disabled = true;
        break;
      case NOT_FOUND:
        buttonText = 'Deploy';
        style = { ...styles.publishButton, ...styles.deployButton };
        return (
          <button
            disabled={disabled}
            style={style}
            onClick={() =>
              eddiApiActionDispatchers.deployBotAction(this.props.bot.resource)
            }>
            {buttonText}
          </button>
        );
      default:
        buttonText = 'STATUS ERROR';
        style = { ...styles.publishButton, ...styles.errorButton };
        disabled = true;
        break;
    }
    return (
      <button disabled={disabled} style={style}>
        {buttonText}
      </button>
    );
  }

  render() {
    console.log(
      'Deploymentstatus: ' +
        this.props.bot.deploymentStatus +
        '  ' +
        DeploymentStatus.READY,
    );
    return (
      <div>
        <div style={styles.botBox}>
          <div style={styles.botHeader}>
            <Link
              style={styles.link}
              to={{
                pathname: `/botview/${this.props.bot.id}`,
              }}>
              <div style={styles.botHeaderName}>
                {this.props.bot.name || this.props.bot.id}
              </div>
              <div style={styles.versionName}>
                {'V'}
                {this.props.bot.version}
              </div>
              {renderIf(this.props.bot.hasAvailableUpdates)(() => (
                <div style={styles.warning}>
                  <img src={warningIcon} style={styles.warningIcon} />
                  <div style={styles.updateAvailable}>
                    {'Updates Available'}
                  </div>
                </div>
              ))}
              <div style={styles.botIDNumber}>
                {'Id:'}
                {this.props.bot.id}
              </div>
              <div style={styles.botHeaderCenter} />
              <div style={styles.lastModified}>
                {'Last Modified: '}
                <span style={styles.lastModifiedDate}>
                  {moment(this.props.bot.lastModifiedOn).format('DD.MM.YYYY')}
                </span>
              </div>
            </Link>
            {this.getDeployButton()}
          </div>
          <div style={styles.botContent}>
            {renderIf(_.isEmpty(this.props.bot.packages))(() => (
              <p>{`There are no packages yet`}</p>
            ))}
            {renderIf(!_.isEmpty(this.props.bot.packages))(() => (
              <Packages
                packages={this.props.bot.packages}
                bot={this.props.bot}
              />
            ))}
          </div>
        </div>
      </div>
    );
  }
}

const ComposedBot: Component<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(pure, Radium, setDisplayName('Bot'))(Bot);

export default ComposedBot;
