import * as React from 'react';
import BotView from './BotView';
import { IBot } from '../utils/AxiosFunctions';
import { compose, pure, setDisplayName } from 'recompose';
import HomeButtonComponent from '../HomeButton/HomeButtonComponent';
import Radium from 'radium';
import { specificBotSelector } from '../../selectors/BotSelectors';
import { connect } from 'react-redux';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import styles from '../Bots/Botlist.styles';
import * as _ from 'lodash';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import * as renderIf from 'render-if';
import { BOT, BOT_PATH } from '../utils/EddiTypes';

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
        {renderIf(this.props.isLoading)(() => (
          <div style={styles.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        ))}
        {renderIf(!this.props.isLoading)(() => (
          <div>
            {renderIf(this.props.error)(() => (
              <p>{'Error: Could not load bot'}</p>
            ))}
            {renderIf(!this.props.error && _.isEmpty(this.props.bot))(() => (
              <p>{'Bot not found'}</p>
            ))}
            {renderIf(!this.props.error && !_.isEmpty(this.props.bot))(() => (
              <BotView bot={this.props.bot} />
            ))}
          </div>
        ))}
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
