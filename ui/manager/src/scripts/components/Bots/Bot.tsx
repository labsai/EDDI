import * as React from 'react';
import * as renderIf from 'render-if';
import styles from './Bot.styles';
import * as Radium from 'radium';
import { Link, browserHistory } from 'react-router-dom';
import { IBot } from '../utils/AxiosFunctions';
import Packages from './Packages';
import { Component, compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import * as _ from 'lodash';
import * as moment from 'moment';
import DeployButton from '../Assets/Buttons/DeployButton';
import { ClipLoader } from 'react-spinners';
import { historyPush } from '../../history';
import Options from '../Assets/Buttons/BotOptions';
import { readOnlySelector } from '../../selectors/AuthenticationSelectors';
import { connect } from 'react-redux';

interface IPublicProps {
  bot: IBot;
  apiUrl: string;
}
interface IPrivateProps extends IPublicProps {
  readOnly: boolean;
}

const warningIcon = require('../../../public/images/WarningIcon.png');

class Bot extends React.Component<IPrivateProps> {
  async componentDidMount() {
    if (_.isUndefined(this.props.bot.packages)) {
      eddiApiActionDispatchers.fetchBotDataAction(this.props.bot.resource);
    }
  }

  componentDidUpdate(prevProps) {
    if (this.props.bot.deploymentStatus === null && prevProps !== this.props) {
      eddiApiActionDispatchers.fetchBotDeploymentStatusAction(
        this.props.bot.resource,
      );
    }
  }

  render() {
    return (
      <div>
        <div style={styles.botBox}>
          <div
            style={styles.botHeader}
            onClick={() => historyPush(`/botview/${this.props.bot.id}`)}>
            <div style={styles.link}>
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
            </div>
            <div style={styles.optionsMenu} onClick={e => e.stopPropagation()}>
              <Options bot={this.props.bot} apiUrl={this.props.apiUrl} />
            </div>
            <div onClick={e => e.stopPropagation()}>
              <DeployButton
                botName={this.props.bot.name}
                botResource={this.props.bot.resource}
                deploymentStatus={this.props.bot.deploymentStatus}
                customStyles={styles.deployButton}
                readOnly={this.props.readOnly}
              />
            </div>
          </div>
          <div style={styles.botContent}>
            {renderIf(
              _.isEmpty(this.props.bot.packages) &&
                !_.isUndefined(this.props.bot.packages),
            )(() => <p>{`This bot has no packages yet`}</p>)}
            {renderIf(_.isUndefined(this.props.bot.packages))(() => (
              <ClipLoader color={'#0070D2'} />
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

const ComposedBot: Component<IProps> = compose<IProps>(
  pure,
  connect(readOnlySelector),
  Radium,
  setDisplayName('Bot'),
)(Bot);

export default ComposedBot;
