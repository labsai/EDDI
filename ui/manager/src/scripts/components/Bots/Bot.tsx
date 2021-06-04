import * as _ from 'lodash';
import * as moment from 'moment';
import Radium from 'radium';
import * as React from 'react';
import { connect } from 'react-redux';
import ClipLoader from 'react-spinners/ClipLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { historyPush } from '../../history';
import { readOnlySelector } from '../../selectors/AuthenticationSelectors';
import Options from '../Assets/Buttons/BotOptions';
import DeployButton from '../Assets/Buttons/DeployButton';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import { IBot } from '../utils/AxiosFunctions';
import { READY } from '../utils/helpers/BotHelper';
import styles from './Bot.styles';
import Packages from './Packages';

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
              {this.props.bot.hasAvailableUpdates && (
                <div style={styles.warning}>
                  <img src={warningIcon} style={styles.warningIcon} />
                  <div style={styles.updateAvailable}>
                    {'Updates Available'}
                  </div>
                </div>
              )}
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
            <div
              style={styles.optionsMenu}
              onClick={(e) => e.stopPropagation()}>
              <Options bot={this.props.bot} apiUrl={this.props.apiUrl} />
            </div>
            <div onClick={(e) => e.stopPropagation()}>
              <WhiteButton
                text={'Open Chat'}
                customStyles={styles.chatButton}
                disabled={this.props.bot.deploymentStatus !== READY}
                onClick={() =>
                  window
                    .open(
                      `${this.props.apiUrl}/chat/unrestricted/${this.props.bot.id}`,
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
          </div>
          <div style={styles.botContent}>
            {_.isEmpty(this.props.bot.packages) &&
              !_.isUndefined(this.props.bot.packages) && (
                <p>{`This bot has no packages yet`}</p>
              )}
            {_.isUndefined(this.props.bot.packages) && (
              <ClipLoader color={'#0070D2'} />
            )}
            {!_.isEmpty(this.props.bot.packages) && (
              <Packages
                packages={this.props.bot.packages}
                bot={this.props.bot}
              />
            )}
          </div>
        </div>
      </div>
    );
  }
}

const ComposedBot: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(readOnlySelector),
  Radium,
  setDisplayName('Bot'),
)(Bot);

export default ComposedBot;
