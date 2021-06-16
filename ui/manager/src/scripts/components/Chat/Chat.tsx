import CloseIcon from '@material-ui/icons/Close';
import FindInPageIcon from '@material-ui/icons/FindInPage';
import * as _ from 'lodash';
import * as React from 'react';
import { useDispatch, useSelector } from 'react-redux';
import ClipLoader from 'react-spinners/ClipLoader';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import {
  closeChatAction,
  replyInChatAction,
  startChatAction,
} from '../../actions/ChatActions';
import { chatDataSelector } from '../../selectors/ChatSelectors';
import useStyles from './Chat.styles';
import ChatInputField from './ChatInputField/ChatInputField';
import ChatOutputs from './ChatOutputs/ChatOutputs';
import ChatQuickReplies from './ChatQuickReplies/ChatQuickReplies';

const Chat = () => {
  const chatRef = React.useRef<HTMLDivElement>(null);
  const classes = useStyles();
  const urlSearchParams = new URLSearchParams(location.search);
  const botId = urlSearchParams.get('botId');

  const { data, isLoading, error } = useSelector(chatDataSelector);

  const dispatch = useDispatch();

  const handleCloseChat = () => {
    dispatch(closeChatAction());
  };

  React.useEffect(() => {
    dispatch(startChatAction(botId));
  }, [botId]);

  const handleReplyInChat = (quickReply) => {
    dispatch(
      replyInChatAction(botId, data?.[0].conversationId, quickReply.value),
    );
  };

  return (
    <div className={classes.chatContainer} ref={chatRef}>
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
      {data && (
        <div className={classes.chat}>
          {data?.map((d, i: number) => {
            const outputs = d.conversationOutputs?.[0]?.output;
            const input = d.conversationOutputs?.[0]?.input;
            const count = outputs?.length + 2 + (input ? 1 : 0);
            const quickReplies = d.conversationOutputs?.[0]?.quickReplies;
            const lastStep = i === data?.length - 1;
            const noQuickReplies =
              _.isEmpty(quickReplies) || _.isUndefined(quickReplies);

            return (
              (outputs || input) && (
                <div className={classes.step} key={d.conversationId + i}>
                  <ChatOutputs outputs={outputs} input={input} />
                  <ChatQuickReplies
                    delay={count * 500}
                    quickReplies={quickReplies}
                    handleReplyInChat={handleReplyInChat}
                    hidden={!lastStep}
                  />
                  {lastStep && noQuickReplies && (
                    <ChatInputField
                      delay={count * 500}
                      handleReplyInChat={handleReplyInChat}
                    />
                  )}
                </div>
              )
            );
          })}
        </div>
      )}
    </div>
  );
};

export default Chat;
