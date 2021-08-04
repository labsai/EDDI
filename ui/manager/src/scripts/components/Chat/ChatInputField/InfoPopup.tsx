import { Link, Typography } from '@material-ui/core';
import Popover from '@material-ui/core/Popover';
import { makeStyles } from '@material-ui/core/styles';
import OpenInNewIcon from '@material-ui/icons/OpenInNew';
import * as React from 'react';
import { useSelector } from 'react-redux';
import { historyPush } from '../../../../scripts/history';
import {
  DARK_GREY_COLOR,
  GREY_COLOR,
  SMALL_FONT,
} from '../../../../styles/DefaultStylingProperties';
import {
  currentChatIdSelector,
  getApiUrl,
  getBotEnvironment,
  getBotId,
  getBotVersion,
} from '../../../selectors/ChatSelectors';
import { getConversations } from '../../utils/AxiosFunctions';

const useStyles = makeStyles({
  chatInfo: {
    padding: '20px',
    display: 'flex',
    justifyContent: 'center',
    flexDirection: 'column',
  },
  chatItem: {
    color: GREY_COLOR,
    fontSize: SMALL_FONT,
    textAlign: 'left',
    display: 'flex',
    alignItems: 'center',

    '& strong': {
      paddingRight: '3px',
      color: DARK_GREY_COLOR,
    },
  },
  icon: {
    marginLeft: '3px',
  },
});

interface IInfoPopup {
  popupEl: null | HTMLElement;
  open: boolean;
  botResource: string;
  handleClose: (event: React.MouseEvent<HTMLElement>) => void;
}

const InfoPopup = ({ open, popupEl, handleClose, botResource }: IInfoPopup) => {
  const conversationId = useSelector(currentChatIdSelector);
  const botId = useSelector(getBotId);
  const botVersion = useSelector(getBotVersion);
  const botEnvironment = useSelector(getBotEnvironment);
  const [prevConversation, setPreviousConversation] =
    React.useState<string>(null);
  const classes = useStyles();

  const handleLinkClick = (conversationId: string) => {
    historyPush(
      `${location.origin}/conversationview/${conversationId}`,
      [],
      true,
    );
  };

  // fetching previous conversation id from api
  React.useEffect(() => {
    if (botResource) {
      getConversations(2, 0, null, botResource).then((responce: any) => {
        if (!responce) {
          return;
        }
        const conversationResource = responce?.[1]?.resource;
        const prevConversationId = conversationResource?.split('/')?.pop();
        if (prevConversationId) {
          setPreviousConversation(prevConversationId);
        }
      });
    }
  }, [botResource]);

  const handleClosePopup = (e: React.MouseEvent<HTMLButtonElement>) => {
    e.stopPropagation();
    handleClose(e);
  };

  return (
    <Popover
      id="info-popup"
      open={open}
      anchorEl={popupEl}
      onClose={handleClosePopup}
      anchorOrigin={{
        vertical: 'bottom',
        horizontal: 'center',
      }}
      transformOrigin={{
        vertical: 'top',
        horizontal: 'center',
      }}>
      {conversationId && (
        <div className={classes.chatInfo}>
          <Typography className={classes.chatItem}>
            <Link
              target="_blank"
              rel="noopener"
              onClick={() => handleLinkClick(conversationId)}
              color="inherit">
              <strong>{`Current conversation: `}</strong>
              {conversationId}
              <OpenInNewIcon className={classes.icon} fontSize="small" />
            </Link>
          </Typography>
          {prevConversation && (
            <Typography className={classes.chatItem}>
              <Link
                target="_blank"
                onClick={() => handleLinkClick(prevConversation)}
                rel="noopener"
                color="inherit">
                <strong>{`Previous conversation: `}</strong>
                {prevConversation}
                <OpenInNewIcon className={classes.icon} fontSize="small" />
              </Link>
            </Typography>
          )}
          {botId && (
            <Typography className={classes.chatItem}>
              <strong>{`Bot ID: `}</strong>
              {botId}
            </Typography>
          )}
          {botVersion && (
            <Typography className={classes.chatItem}>
              <strong>{`Bot version: `}</strong>
              {botVersion}
            </Typography>
          )}
          {botEnvironment && (
            <Typography className={classes.chatItem}>
              <strong>{`Bot environment: `}</strong>
              {botEnvironment}
            </Typography>
          )}
        </div>
      )}
    </Popover>
  );
};

export default InfoPopup;
