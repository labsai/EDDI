import * as React from 'react';
import { connect, useDispatch } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import { openChatAction } from '../../actions/ChatActions';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import { historyPush } from '../../history';
import { readOnlySelector } from '../../selectors/AuthenticationSelectors';
import Options from '../Assets/Buttons/BotOptions';
import DeployButton from '../Assets/Buttons/DeployButton';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import VersionSelectComponent from '../Assets/VersionSelectComponent';
import { getAPIUrl } from '../utils/ApiFunctions';
import { IBot } from '../utils/AxiosFunctions';
import { BOT } from '../utils/EddiTypes';
import { READY } from '../utils/helpers/BotHelper';
import Parser from '../utils/Parser';
import BotDescriptor from './BotDescriptor';
import useStyles from './BotView.styles';
import PackageList from './PackageList';

interface IPublicProps {
  bot: IBot;
}

interface IPrivateProps extends IPublicProps {
  readOnly: boolean;
}

const warningIcon = require('../../../public/images/WarningIcon.png');
const foundUnpublishedChanges = false; // todo : add function to check if there are unpublished changes.

const BotView = ({ bot, readOnly }: IPrivateProps) => {
  const classes = useStyles();
  const [apiUrl, setApiUrl] = React.useState('');

  const asyncSetApiUrl = async () => {
    const apiUrl = await getAPIUrl();
    setApiUrl(apiUrl);
  };

  React.useEffect(() => {
    asyncSetApiUrl();
  }, []);

  const openEditBotModal = () => {
    modalActionDispatchers.showEditDescriptorModalAction(bot);
  };

  const openBotLogsModal = () => {
    modalActionDispatchers.showEditDescriptorModalAction(bot);
  };

  const openEditJsonModal = () => {
    eddiApiActionDispatchers.fetchJsonSchemaAction(BOT);
    modalActionDispatchers.showEditJsonModal(
      bot.resource,
      JSON.stringify(
        {
          packages: bot.packages,
          channels: bot.channels,
        },
        null,
        '\t',
      ),
    );
  };

  const selectVersion = (newVersion: number) => {
    eddiApiActionDispatchers.fetchBotAction(
      Parser.replaceResourceVersion(bot.resource, newVersion),
    );
    historyPush(`${bot.id}`, [`version=${newVersion}`]);
  };

  const dispatch = useDispatch();

  const isCurrentVersion = bot.version !== bot.currentVersion;
  return (
    <div>
      {!!bot && (
        <div>
          <div className={classes.botHeader}>
            <div className={classes.botName}>{bot.name || bot.id}</div>
            <VersionSelectComponent
              selectedVersion={bot.version}
              currentVersion={bot.currentVersion}
              selectVersion={selectVersion}
            />
            <WhiteButton
              text={'Rename'}
              onClick={openEditBotModal}
              classes={{ button: classes.button }}
              disabled={isCurrentVersion || readOnly}
            />
            {/* <WhiteButton
              text={'Show logs'}
              onClick={openBotLogsModal}
              classes={{ button: classes.button }}
            /> */}
            <WhiteButton
              text={'Edit JSON'}
              onClick={openEditJsonModal}
              disabled={isCurrentVersion || readOnly}
              classes={{ button: classes.button }}
            />
            {foundUnpublishedChanges && (
              <div className={classes.unpublishedChanges}>
                <img src={warningIcon} className={classes.warningIcon} />
                <div className={classes.unpublishedChangesText}>
                  {'This Bot has unpublished changes'}
                </div>
              </div>
            )}
            <div className={classes.botHeaderSpacing} />
            <div className={classes.options}>
              <Options bot={bot} apiUrl={apiUrl} />
            </div>
            <WhiteButton
              text={'Open Chat'}
              classes={{ button: classes.chatButton }}
              disabled={bot.deploymentStatus !== READY}
              onClick={() => {
                dispatch(openChatAction());
                historyPush(location.pathname, [`botId=${bot.id}`]);
                /* window
                  .open(`${apiUrl}/chat/unrestricted/${bot.id}`, '_blank')
                  .focus(); */
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
          <BotDescriptor
            botCreated={bot.createdOn}
            botLastModified={bot.lastModifiedOn}
            botDescription={bot.description}
          />
          <PackageList bot={bot} readOnly={readOnly} />
        </div>
      )}
    </div>
  );
};

const ComposedBotView: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(readOnlySelector),
  setDisplayName('BotView'),
)(BotView);

export default ComposedBotView;
