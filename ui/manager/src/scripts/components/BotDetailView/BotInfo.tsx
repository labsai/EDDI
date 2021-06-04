import * as _ from 'lodash';
import Radium from 'radium';
import * as React from 'react';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { specificBotSelector } from '../../selectors/BotSelectors';
import styles from '../Bots/Botlist.styles';
import HomeButtonComponent from '../HomeButton/HomeButtonComponent';
import { IBot } from '../utils/AxiosFunctions';
import { BOT, BOT_PATH } from '../utils/EddiTypes';
import BotView from './BotView';

interface IPublicProps {
  botId: string;
  botVersion: string;
}

interface IPrivateProps extends IPublicProps {
  bot: IBot;
  error: Error;
  isLoading: boolean;
}

class BotInfo extends React.Component<IPrivateProps> {
  constructor(props) {
    super(props);
  }

  componentDidMount() {
    if (_.isEmpty(this.props.botVersion)) {
      eddiApiActionDispatchers.fetchCurrentBotAction(this.props.botId);
    } else {
      eddiApiActionDispatchers.fetchBotAction(
        `${BOT}${BOT_PATH}/${this.props.botId}?version=${this.props.botVersion}`,
      );
    }
  }

  render() {
    return (
      <div>
        <HomeButtonComponent />
        {this.props.isLoading ? (
          <div style={styles.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        ) : (
          <div>
            {!!this.props.error && <p>{'Error: Could not load bot'}</p>}
            {!this.props.error && _.isEmpty(this.props.bot) && (
              <p>{'Bot not found'}</p>
            )}
            {!this.props.error && !_.isEmpty(this.props.bot) && (
              <BotView bot={this.props.bot} />
            )}
          </div>
        )}
      </div>
    );
  }
}

const ComposedBotInfo: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(specificBotSelector),
  Radium,
  setDisplayName('BotInfo'),
)(BotInfo);

export default ComposedBotInfo;
