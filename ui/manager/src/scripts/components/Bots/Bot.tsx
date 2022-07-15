import * as _ from 'lodash';
import * as moment from 'moment';
import * as React from 'react';
import { connect, useDispatch } from 'react-redux';
import ClipLoader from 'react-spinners/ClipLoader';
import { compose, pure, setDisplayName } from 'recompose';
import modalActionDispatchers from '../../../scripts/actions/ModalActionDispatchers';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import { openChatAction } from '../../actions/ChatActions';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { historyPush } from '../../history';
import { readOnlySelector } from '../../selectors/AuthenticationSelectors';
import Options from '../Assets/Buttons/BotOptions';
import DeployButton from '../Assets/Buttons/DeployButton';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import { axiosExportBot, IBot } from '../utils/AxiosFunctions';
import { READY } from '../utils/helpers/BotHelper';
import useStyles from './Bot.styles';
import Packages from './Packages';
import exportBot from '../utils/helpers/ExportBot';
import { BOT_VIEW } from '../../constants/paths';

interface IPublicProps {
  bot: IBot;
  apiUrl: string;
}
interface IPrivateProps extends IPublicProps {
  readOnly: boolean;
}

const warningIcon = require('../../../public/images/WarningIcon.png');

const Bot = ({ bot, apiUrl, readOnly }: IPrivateProps) => {
  const classes = useStyles();
  React.useEffect(() => {
    if (_.isUndefined(bot.packages)) {
      eddiApiActionDispatchers.fetchBotDataAction(bot.resource);
    }
  }, []);
  React.useEffect(() => {
    if (bot.deploymentStatus === null) {
      eddiApiActionDispatchers.fetchBotDeploymentStatusAction(bot.resource);
    }
  }, [bot, apiUrl, readOnly]);

  const dispatch = useDispatch();

  const openBotLogsModal = () => {
    modalActionDispatchers.showBotLogsModal(bot);
  };

  const handleExportBot = () => {
    exportBot(bot, dispatch);
  };

  return (
    <div>
      <div className={classes.botBox}>
        <div
          className={classes.botHeader}
          onClick={() => historyPush(`${BOT_VIEW.replace(':id', bot.id)}/`)}>
          <div className={classes.link}>
            <div className={classes.botHeaderName}>{bot.name || bot.id}</div>
            <div className={classes.versionName}>
              {'V'}
              {bot.version}
            </div>
            {bot.hasAvailableUpdates && (
              <div className={classes.warning}>
                <img src={warningIcon} className={classes.warningIcon} />
                <div className={classes.updateAvailable}>
                  {'Updates Available'}
                </div>
              </div>
            )}
            <div className={classes.botIDNumber}>
              {'Id:'}
              {bot.id}
            </div>
            <div className={classes.botHeaderCenter} />
            <div className={classes.lastModified}>
              {'Last Modified: '}
              <span className={classes.lastModifiedDate}>
                {moment(bot.lastModifiedOn).format('DD.MM.YYYY')}
              </span>
            </div>
          </div>
          <div
            className={classes.optionsMenu}
            onClick={(e) => e.stopPropagation()}>
            <Options
              bot={bot}
              apiUrl={apiUrl}
              openBotLogs={openBotLogsModal}
              exportBot={handleExportBot}
            />
          </div>
          <div
            onClick={(e) => e.stopPropagation()}
            className={classes.buttonsContainer}>
            <WhiteButton
              text={'Open Chat'}
              classes={{ button: classes.chatButton }}
              disabled={bot.deploymentStatus !== READY}
              onClick={() => {
                dispatch(openChatAction());
                historyPush(location.pathname, [`botId=${bot.id}`]);
                /* window
                  .open(`${apiUrl}/chat/unrestricted/${bot.id}`, '_blank')
                  .focus() */
              }}
            />
            <DeployButton
              botName={bot.name}
              botResource={bot.resource}
              deploymentStatus={bot.deploymentStatus}
              classes={{ button: classes.deployButton }}
              readOnly={readOnly}
            />
          </div>
        </div>
        <div className={classes.botContent}>
          {_.isEmpty(bot.packages) && !_.isUndefined(bot.packages) && (
            <p>{`This bot has no packages yet`}</p>
          )}
          {_.isUndefined(bot.packages) && <ClipLoader color={BLUE_COLOR} />}
          {!_.isEmpty(bot.packages) && (
            <Packages packages={bot.packages} bot={bot} />
          )}
        </div>
      </div>
    </div>
  );
};

const ComposedBot: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(readOnlySelector),
  setDisplayName('Bot'),
)(Bot);

export default ComposedBot;
