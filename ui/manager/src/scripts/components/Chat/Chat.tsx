import CloseIcon from '@material-ui/icons/Close';
import FindInPageIcon from '@material-ui/icons/FindInPage';
import clsx from 'clsx';
import * as _ from 'lodash';
import * as React from 'react';
import { useDispatch, useSelector } from 'react-redux';
import ClipLoader from 'react-spinners/ClipLoader';
import { isModalOpenSelector } from '../../../scripts/selectors/ModalSelectors';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import {
  closeChatAction,
  replyInChatAction,
  restartChatAction,
  setChatAnimation,
  setUserReplyAction,
  startChatAction,
} from '../../actions/ChatActions';
import {
  chatDataSelector,
  currentChatIdSelector,
  getChatContext,
  isChatOpenedSelector,
} from '../../selectors/ChatSelectors';
import { BOT, BOT_PATH } from '../utils/EddiTypes';
import isElementVisible from '../utils/helpers/isElementVisible';
import useStyles from './Chat.styles';
import ChatInputField from './ChatInputField/ChatInputField';
import ChatOptions from './ChatInputField/ChatOptions';
import ChatOutput from './ChatOutput/ChatOutput';
import ChatOutputs from './ChatOutputs/ChatOutputs';
import ChatQuickReplies from './ChatQuickReplies/ChatQuickReplies';
import Delayed from './Delay/Delay';

const Chat = () => {
  const context = useSelector(getChatContext);
  const isModalOpen = useSelector(isModalOpenSelector);
  const conversationId: string = useSelector(currentChatIdSelector);
  const { isOpened: isChatOpened } = useSelector(isChatOpenedSelector);
  const chatRef = React.useRef<HTMLDivElement>(null);
  const classes = useStyles();
  const [chatVisible, setChatVisible] = React.useState(false);

  // need to get bot id
  const isBotPage = location.pathname.includes('botview');
  const urlSearchParams = new URLSearchParams(location.search);
  const botId = isBotPage
    ? location.pathname.split('/')?.[2]
    : urlSearchParams.get('botId');

  const { data, isLoading, error } = useSelector(chatDataSelector);

  // need to get prev conversation id
  const botVersion = data?.[0]?.botVersion;
  const botResource = botVersion
    ? `${BOT}${BOT_PATH}/${botId}?version=${botVersion}`
    : null;

  const dispatch = useDispatch();

  // close side chat window
  const handleCloseChat = () => {
    dispatch(closeChatAction());
  };

  React.useLayoutEffect(() => {
    setTimeout(() => {
      const isChatVisible = isElementVisible('chat-options');
      if (chatVisible !== isChatVisible) {
        setChatVisible(isChatVisible);
      }
    }, 500);
  });

  // change animation delay (on/off)
  const handleSetChatAnimation = (state: boolean) => {
    dispatch(setChatAnimation(state));
  };

  // close chat if any modal is opened
  React.useEffect(() => {
    if (isModalOpen) {
      handleCloseChat();
    }
  }, [isModalOpen]);

  React.useEffect(() => {
    if (chatVisible) {
      startNewConversation();
    }
  }, [botId, context, chatVisible]);

  // create new conversation
  const startNewConversation = () => {
    if (botId) {
      dispatch(
        startChatAction(botId, !!context ? JSON.parse(context) : undefined),
      );
    }
  };

  // restart conversation
  const handleRestartChat = () => {
    dispatch(restartChatAction(botId, conversationId));
  };

  // send user reply
  const handleReplyInChat = (quickReply) => {
    dispatch(setUserReplyAction(quickReply.value));
    dispatch(
      replyInChatAction(
        botId,
        conversationId,
        quickReply.value,
        !!context ? JSON.parse(context) : undefined,
      ),
    );
  };

  return (
    <div
      className={clsx(
        classes.chatContainer,
        !isChatOpened ? classes.hiddenChat : null,
      )}
      ref={chatRef}>
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
        <Delayed wait={500} ignoreAnimation>
          <div className={classes.emptyState}>
            <FindInPageIcon fontSize="large" />
            <span className={classes.emptyText}>{'Bot not loaded'}</span>
          </div>
        </Delayed>
      )}
      {data && (
        <div className={classes.chat}>
          {data?.map((d, i: number) => {
            const outputs = d.conversationOutputs?.[0]?.output;
            const input = d.conversationOutputs?.[0]?.input;
            const count = outputs?.length + 2 + (input ? 1 : 0);
            const quickReplies = d.conversationOutputs?.[0]?.quickReplies;
            const lastStep = i === data?.length - 1;
            const userReply = d.userReply;
            const noQuickReplies =
              _.isEmpty(quickReplies) || _.isUndefined(quickReplies);

            return outputs || input || userReply ? (
              <div className={classes.step} key={d.conversationId + i}>
                <ChatOutputs outputs={outputs} />
                {!!userReply && (
                  <ChatOutput output={{ text: userReply }} input />
                )}
                <ChatQuickReplies
                  delay={count * 400}
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
            ) : (
              <div className={classes.step} key={d.conversationId + i}>
                {lastStep && noQuickReplies && (
                  <ChatInputField
                    delay={count * 500}
                    handleReplyInChat={handleReplyInChat}
                  />
                )}
              </div>
            );
          })}
        </div>
      )}
      <ChatOptions
        startNewConversation={startNewConversation}
        setChatAnimation={handleSetChatAnimation}
        restartChat={handleRestartChat}
        botResource={botResource}
      />
    </div>
  );
};

export default Chat;
