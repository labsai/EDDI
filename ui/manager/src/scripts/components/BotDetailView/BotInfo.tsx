import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { specificBotSelector } from '../../selectors/BotSelectors';
import useStyles from '../Bots/Botlist.styles';
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

const BotInfo = ({
  botId,
  botVersion,
  bot,
  error,
  isLoading,
}: IPrivateProps) => {
  const classes = useStyles();
  React.useEffect(() => {
    if (_.isEmpty(botVersion)) {
      eddiApiActionDispatchers.fetchCurrentBotAction(botId);
    } else {
      eddiApiActionDispatchers.fetchBotAction(
        `${BOT}${BOT_PATH}/${botId}?version=${botVersion}`,
      );
    }
  }, []);

  return (
    <div>
      <HomeButtonComponent />
      {isLoading ? (
        <div className={classes.loadingWrapper}>
          <ClimbingBoxLoader loading />
        </div>
      ) : (
        <div>
          {!!error && <p>{'Error: Could not load bot'}</p>}
          {!error && _.isEmpty(bot) && <p>{'Bot not found'}</p>}
          {!error && !_.isEmpty(bot) && <BotView bot={bot} />}
        </div>
      )}
    </div>
  );
};

const ComposedBotInfo: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(specificBotSelector),
  setDisplayName('BotInfo'),
)(BotInfo);

export default ComposedBotInfo;
