import { Typography, Link } from '@material-ui/core';
import Popover from '@material-ui/core/Popover';
import { makeStyles } from '@material-ui/core/styles';
import * as React from 'react';
import { useSelector } from 'react-redux';
import {
  currentChatIdSelector,
  getApiUrl,
  getBotId,
} from '../../../selectors/ChatSelectors';
import {
  DARK_GREY_COLOR,
  SMALL_FONT,
} from '../../../../styles/DefaultStylingProperties';
import { getConversations } from '../../utils/AxiosFunctions';

const useStyles = makeStyles({
  chatInfo: {
    padding: '20px',
    display: 'flex',
    justifyContent: 'center',
    flexDirection: 'column',
  },
  chatId: {
    color: DARK_GREY_COLOR,
    fontSize: SMALL_FONT,
    textAlign: 'left',
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
  const apiUrl = useSelector(getApiUrl);
  const [prevConversation, setPreviousConversation] =
    React.useState<string>(null);
  const classes = useStyles();

  const getLink = (conversationId: string) =>
    `${apiUrl}/bots/unrestricted/${botId}/${conversationId}?returnCurrentStepOnly=false&returnDetailed=true`;

  // fetching previous conversation id from api
  React.useEffect(() => {
    if (botResource) {
      getConversations(2, 0, null, botResource).then((responce: any) => {
        if (!responce) {
          return;
        }
        const conversationResource = responce?.[1].resource;
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
          <Typography className={classes.chatId}>
            <Link
              href={getLink(conversationId)}
              target="_blank"
              rel="noopener"
              color="inherit">
              <strong>{'Current conversation: '}</strong>
            </Link>
            {conversationId}
          </Typography>
          {prevConversation && (
            <Typography className={classes.chatId}>
              <Link
                href={getLink(prevConversation)}
                target="_blank"
                rel="noopener"
                color="inherit">
                <strong>{'Previous conversation: '}</strong>
              </Link>
              {prevConversation}
            </Typography>
          )}
        </div>
      )}
    </Popover>
  );
};

export default InfoPopup;
