import * as React from 'react';
import * as renderIf from 'render-if';
import styles from './Bot.styles';
import * as Radium from 'radium';
import { Link, browserHistory } from 'react-router-dom';
import { IBot, IPackage } from '../utils/AxiosFunctions';
import Packages from './Packages';
import { Component, compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import * as _ from 'lodash';
import * as moment from 'moment';
import WhiteButton from '../Assets/Buttons/WhiteButton';

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
  }

  render() {
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
            <WhiteButton
              text={'Publish'}
              disabled={true}
              customStyles={styles.publishButton}
            />
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
