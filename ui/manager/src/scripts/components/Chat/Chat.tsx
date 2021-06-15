import CloseIcon from '@material-ui/icons/Close';
import FindInPageIcon from '@material-ui/icons/FindInPage';
import * as React from 'react';
import { useDispatch, useSelector } from 'react-redux';
import ClipLoader from 'react-spinners/ClipLoader';
import { chatDataSelector } from '../../selectors/ChatSelectors';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import {
  closeChatAction,
  replyInChatAction,
  startChatAction,
} from '../../actions/ChatActions';
import useStyles from './Chat.styles';
import ChatOutputs from './ChatOutputs/ChatOutputs';
import ChatQuickReplies from './ChatQuickReplies/ChatQuickReplies';

const Chat = () => {
  const classes = useStyles();
  const urlSearchParams = new URLSearchParams(location.search);
  const botId = urlSearchParams.get('botId');

  const { data, isLoading, error, step } = useSelector(chatDataSelector);
  console.log('data: ', data);

  const dispatch = useDispatch();

  const handleCloseChat = () => {
    dispatch(closeChatAction());
  };

  React.useEffect(() => {
    dispatch(startChatAction(botId));
  }, []);

  const handleReplyInChat = (quickReply) => {
    dispatch(
      replyInChatAction(botId, data?.[0].conversationId, quickReply.value),
    );
  };

  return (
    <div className={classes.chatContainer}>
      <div className={classes.closeChat} onClick={handleCloseChat}>
        <CloseIcon fontSize="large" />
      </div>
      {isLoading && (
        <div className={classes.loader}>
          <ClipLoader color={BLUE_COLOR} />
        </div>
      )}
      {error && <div className={classes.error}>{error}</div>}
      {!data && !isLoading && (
        <div className={classes.emptyState}>
          <FindInPageIcon fontSize="large" />
          <span className={classes.emptyText}>{'Bot not loaded'}</span>
        </div>
      )}
      <div className={classes.chat}>
        {data?.map((d, i: number) => {
          const outputs = d.conversationOutputs?.[0]?.output;
          const input = d.conversationOutputs?.[0]?.input;
          const quickReplies = d.conversationOutputs?.[0]?.quickReplies;
          return (
            outputs && (
              <div className={classes.step} key={d.conversationId + i}>
                <ChatOutputs outputs={outputs} input={input} />
                <ChatQuickReplies
                  quickReplies={quickReplies}
                  handleReplyInChat={handleReplyInChat}
                  hidden={i !== data.length - 1}
                />
                {/* <ChatInputField />
              <ChatButtons /> */}
              </div>
            )
          );
        })}
      </div>
    </div>
  );
};

export default Chat;
